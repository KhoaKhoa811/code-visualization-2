package com.codevisualization.backend.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One local owner, one occupied slot, two waiting fixtures; no HTTP or history store. */
public final class FixtureRunSupervisor implements AutoCloseable {
    public enum State { QUEUED, RUNNING, HELD, RECOVERING, FINISHED, CANCELLED, QUEUE_EXPIRED }
    public record Snapshot(UUID runId, State state, FixedFixtureLifecycle.Result result) { }
    public final class Handle {
        private final UUID id = UUID.randomUUID();
        private final FixedFixture fixture;
        private final long accepted;
        private State state = State.QUEUED;
        private FixedFixtureLifecycle lifecycle;
        private FixedFixtureLifecycle.Result result;
        private Handle(FixedFixture fixture) { this.fixture = fixture; accepted = clock.getAsLong(); }
        public Snapshot snapshot() {
            synchronized (FixtureRunSupervisor.this) { return new Snapshot(id, state, result); }
        }
    }

    private final Supplier<FixedFixtureLifecycle> factory;
    private final LongSupplier clock;
    private final ArrayDeque<Handle> queue = new ArrayDeque<>();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), task -> daemon(task, "fixture-supervisor"));
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(
            task -> daemon(task, "fixture-queue-expiry"));
    private Handle occupied;
    private boolean closed;

    public static FixtureRunSupervisor open(Path docker) throws IOException {
        if (!docker.isAbsolute()) throw new IllegalArgumentException("Absolute Docker executable required");
        try (var control = new BoundedCommandRunner()) {
            String inventory = control.execute(List.of(docker.toString(), "--context", "desktop-linux", "ps",
                    "--all", "--no-trunc", "--filter", "label=" + ContainerProfile.LABEL,
                    "--format", "{{.ID}}"), Duration.ofSeconds(5));
            if (!inventory.isBlank()) throw new IOException("Existing runner ownership requires reconciliation before admission");
        }
        return new FixtureRunSupervisor(() -> new FixedFixtureLifecycle(docker), System::nanoTime);
    }

    FixtureRunSupervisor(Supplier<FixedFixtureLifecycle> factory, LongSupplier clock) {
        this.factory = factory; this.clock = clock;
        expiry.scheduleAtFixedRate(this::expireQueued, 100, 100, TimeUnit.MILLISECONDS);
    }

    public synchronized Handle submit(FixedFixture fixture) {
        java.util.Objects.requireNonNull(fixture);
        if (closed) throw new IllegalStateException("Supervisor closed");
        expireQueued();
        if (occupied != null && queue.size() == 2) throw new IllegalStateException("Runner busy: queue full");
        Handle handle = new Handle(fixture);
        if (occupied == null) start(handle); else queue.addLast(handle);
        return handle;
    }

    public synchronized boolean cancel(Handle handle) {
        expireQueued();
        if (queue.remove(handle)) { handle.state = State.CANCELLED; return true; }
        return occupied == handle && handle.state == State.RUNNING && handle.lifecycle != null
                && handle.lifecycle.cancel();
    }

    public boolean recover(Handle handle) {
        synchronized (this) {
            if (occupied != handle || handle.state != State.HELD || handle.result == null) return false;
            handle.state = State.RECOVERING;
        }
        FixedFixtureLifecycle.Result recovered;
        try { recovered = handle.lifecycle.recover(); }
        catch (RuntimeException failure) {
            synchronized (this) { handle.state = State.HELD; }
            return false;
        }
        synchronized (this) {
            handle.result = recovered;
            if (recovered.terminationConfirmed() && recovered.cleanupVerified()) {
                release(handle); return true;
            }
            handle.state = State.HELD; return false;
        }
    }

    synchronized void expireQueued() {
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            Handle handle = iterator.next();
            if (clock.getAsLong() - handle.accepted >= Duration.ofSeconds(30).toNanos()) {
                iterator.remove(); handle.state = State.QUEUE_EXPIRED;
            }
        }
    }

    private void start(Handle handle) {
        occupied = handle; handle.state = State.RUNNING;
        long preparationDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try {
            handle.lifecycle = factory.get();
            worker.execute(() -> {
                try {
                    var result = handle.lifecycle.runReserved(handle.fixture, preparationDeadline);
                    synchronized (this) {
                        handle.result = result;
                        if (result.terminationConfirmed() && result.cleanupVerified()) release(handle);
                        else handle.state = State.HELD;
                    }
                } catch (RuntimeException failure) {
                    synchronized (this) { handle.state = State.HELD; }
                }
            });
        } catch (RuntimeException failure) { handle.state = State.HELD; }
    }

    private void release(Handle handle) {
        if (occupied != handle) throw new IllegalStateException("Wrong slot owner");
        handle.state = State.FINISHED; occupied = null;
        expireQueued();
        if (!closed && !queue.isEmpty()) start(queue.removeFirst());
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Handle queued : queue) queued.state = State.CANCELLED;
        queue.clear();
        if (occupied != null && occupied.lifecycle != null) occupied.lifecycle.cancel();
        expiry.shutdown(); worker.shutdown();
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
    }
}
