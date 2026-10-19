package com.codevisualization.backend.runner;

import codeviz.tracer.Tracer;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The single owner of the local runner for submitted source (Part 7d): one
 * occupied slot, at most two FIFO queued runs that expire 30 s after acceptance,
 * and bounded retention of finished runs for polling. Snapshots follow the run-v1
 * contract; scheduling outcomes (busy, queue expiry) stay outside it. Replaces the
 * 6e fixture supervisor as the application's only admission path.
 */
public final class RunSupervisor implements AutoCloseable {
    public static final int QUEUE_LIMIT = 2;
    public static final int RETAINED_LIMIT = 4;
    static final Duration QUEUE_WAIT = Duration.ofSeconds(30);
    static final Duration RETENTION = Duration.ofMinutes(10);

    public enum Phase {
        QUEUED, PREPARING, COMPILING, EXECUTING, STOPPING, CLEANING, FINISHED;
        public String wire() { return name().toLowerCase(Locale.ROOT); }
    }

    /** One run-v1 lifecycle snapshot; {@code trace} is raw trace-v1 JSON or null. */
    public record Snapshot(String runId, String sourceSha256, long revision, Phase phase, boolean terminationConfirmed,
                           boolean cleanupVerified, boolean slotHeld, String trace, List<String> infrastructureFailures) {
        public Snapshot { infrastructureFailures = List.copyOf(infrastructureFailures); }
    }

    /** Queue full while the slot is occupied; nothing was accepted. */
    public static final class BusyException extends Exception {
        BusyException() { super("Runner busy: queue full"); }
    }

    private enum State { QUEUED, ACTIVE, HELD, RECOVERING, DONE, EXPIRED }

    /** A run accepted by this supervisor; all state is guarded by the supervisor. */
    public final class Run {
        private final String id = "run-" + UUID.randomUUID();
        private final String source, sha256;
        private final long accepted;
        private long ended;
        private State state = State.QUEUED;
        private TracedRunLifecycle lifecycle;
        private TracedRunLifecycle.Result result;
        private String cancelledTrace;
        private long revision = -1;
        private Object published;

        private Run(String source, String sha256) {
            this.source = source; this.sha256 = sha256; accepted = clock.getAsLong();
        }

        public String id() { return id; }
    }

    private final Supplier<TracedRunLifecycle> factory;
    private final LongSupplier clock;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final ArrayDeque<Run> queue = new ArrayDeque<>();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), task -> daemon(task, "run-supervisor"));
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(
            task -> daemon(task, "run-queue-expiry"));
    private Run occupied;
    private boolean closed;

    /** Refuses admission while any container with the application owner label exists. */
    public static RunSupervisor open(Path docker) throws IOException {
        if (!docker.isAbsolute()) throw new IllegalArgumentException("Absolute Docker executable required");
        try (var control = new BoundedCommandRunner()) {
            String inventory = control.execute(List.of(docker.toString(), "--context", "desktop-linux", "ps",
                    "--all", "--no-trunc", "--filter", "label=" + ContainerProfile.LABEL,
                    "--format", "{{.ID}}"), Duration.ofSeconds(5));
            if (!inventory.isBlank()) throw new IOException("Existing runner ownership requires reconciliation before admission");
        }
        byte[] runtime = TracedRunLifecycle.bundledRuntime();
        return new RunSupervisor(() -> new TracedRunLifecycle(docker, runtime), System::nanoTime);
    }

    RunSupervisor(Supplier<TracedRunLifecycle> factory, LongSupplier clock) {
        this.factory = factory; this.clock = clock;
        expiry.scheduleAtFixedRate(this::housekeeping, 100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Admits exact source whose claimed SHA-256 matches its UTF-8 bytes, then starts
     * it or queues it. Admission and busy checks happen before any container work.
     */
    public synchronized Run submit(String source, String claimedSha256)
            throws TracedRunLifecycle.AdmissionException, BusyException {
        byte[] bytes = TracedRunLifecycle.admit(source);
        String sha = sha256(bytes);
        if (!sha.equals(claimedSha256)) throw new TracedRunLifecycle.AdmissionException("SOURCE_HASH_MISMATCH", "Source hash mismatch");
        if (closed) throw new IllegalStateException("Supervisor closed");
        housekeeping();
        if (occupied != null && queue.size() == QUEUE_LIMIT) throw new BusyException();
        Run run = new Run(source, sha);
        runs.put(run.id, run);
        if (occupied == null) start(run); else queue.addLast(run);
        return run;
    }

    public synchronized Optional<Run> find(String id) {
        housekeeping();
        return Optional.ofNullable(runs.get(id));
    }

    /** Expired queued runs are a scheduling outcome without a snapshot. */
    public synchronized boolean expired(Run run) { return run.state == State.EXPIRED; }

    /** A queued run finishes cancelled without a slot or container; an active run latches cancellation. */
    public synchronized boolean cancel(Run run) {
        housekeeping();
        if (run.state == State.QUEUED && queue.remove(run)) {
            run.cancelledTrace = Tracer.notRun(run.source, run.id, new Tracer.Outcome(Tracer.Cause.CANCELLED, 0));
            end(run, State.DONE);
            return true;
        }
        return run.state == State.ACTIVE && run.lifecycle.cancel();
    }

    /** Explicit recovery of a held slot; returns true once the slot is released. */
    public boolean recover(Run run) {
        TracedRunLifecycle lifecycle;
        synchronized (this) {
            if (occupied != run || run.state != State.HELD || run.result == null) return false;
            run.state = State.RECOVERING;
            lifecycle = run.lifecycle;
        }
        TracedRunLifecycle.Result recovered;
        try { recovered = lifecycle.recover(); }
        catch (RuntimeException failure) {
            synchronized (this) { run.state = State.HELD; }
            return false;
        }
        synchronized (this) {
            run.result = recovered;
            if (recovered.terminationConfirmed() && recovered.cleanupVerified()) { release(run); return true; }
            run.state = State.HELD;
            return false;
        }
    }

    /** Program output once whole-container termination is confirmed; empty before. */
    public synchronized Optional<TracedRunLifecycle.Result> output(Run run) {
        if (run.state == State.DONE && run.result == null) return Optional.of(new TracedRunLifecycle.Result(
                run.id, run.cancelledTrace, new byte[0], new byte[0], true, true, List.of()));
        return run.result != null && run.result.terminationConfirmed() ? Optional.of(run.result) : Optional.empty();
    }

    /** Current run-v1 snapshot; the revision increases whenever the published facts change. */
    public synchronized Snapshot snapshot(Run run) {
        if (run.state == State.EXPIRED) throw new IllegalStateException("Queue-expired runs have no snapshot");
        Phase phase;
        boolean terminated = false, cleaned = false, held = true;
        String trace = null;
        List<String> failures = List.of();
        if (run.state == State.QUEUED) {
            phase = Phase.QUEUED; held = false;
        } else if (run.result == null && run.state == State.DONE) {
            phase = Phase.FINISHED; terminated = true; cleaned = true; held = false; trace = run.cancelledTrace;
        } else if (run.result == null) {
            String current = run.lifecycle == null ? "not_started" : run.lifecycle.progress().phase();
            phase = switch (current) {
                case "compiling" -> Phase.COMPILING;
                case "executing" -> Phase.EXECUTING;
                case "stopping" -> Phase.STOPPING;
                case "cleaning", "finished" -> Phase.CLEANING;
                default -> Phase.PREPARING;
            };
            terminated = phase == Phase.CLEANING;
        } else {
            var r = run.result;
            terminated = r.terminationConfirmed();
            cleaned = terminated && r.cleanupVerified() && run.state == State.DONE;
            phase = !terminated ? Phase.STOPPING : cleaned ? Phase.FINISHED : Phase.CLEANING;
            held = !cleaned;
            trace = terminated ? r.trace() : null;
            failures = r.infrastructureFailures().stream().map(Enum::name).toList();
        }
        var key = List.of(phase, terminated, cleaned, held, trace != null, failures);
        if (!key.equals(run.published)) { run.published = key; run.revision++; }
        return new Snapshot(run.id, run.sha256, run.revision, phase, terminated, cleaned, held, trace, failures);
    }

    synchronized void housekeeping() {
        long now = clock.getAsLong();
        var queued = queue.iterator();
        while (queued.hasNext()) {
            Run run = queued.next();
            if (now - run.accepted >= QUEUE_WAIT.toNanos()) { queued.remove(); end(run, State.EXPIRED); }
        }
        var ended = runs.values().stream().filter(r -> r.state == State.DONE || r.state == State.EXPIRED).toList();
        int excess = ended.size() - RETAINED_LIMIT;
        for (Run run : ended) {
            if (excess-- > 0 || now - run.ended >= RETENTION.toNanos()) runs.remove(run.id);
        }
    }

    private void end(Run run, State state) { run.state = state; run.ended = clock.getAsLong(); }

    private void start(Run run) {
        occupied = run; run.state = State.ACTIVE;
        long preparationDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try {
            run.lifecycle = factory.get();
            worker.execute(() -> {
                try {
                    var result = run.lifecycle.runReserved(run.source, run.id, preparationDeadline);
                    synchronized (this) {
                        run.result = result;
                        if (result.terminationConfirmed() && result.cleanupVerified()) release(run);
                        else run.state = State.HELD;
                    }
                } catch (TracedRunLifecycle.AdmissionException | RuntimeException failure) {
                    synchronized (this) { run.state = State.HELD; }
                }
            });
        } catch (RuntimeException failure) { run.state = State.HELD; }
    }

    private void release(Run run) {
        if (occupied != run) throw new IllegalStateException("Wrong slot owner");
        end(run, State.DONE); occupied = null;
        housekeeping();
        if (!closed && !queue.isEmpty()) start(queue.removeFirst());
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Run queued : queue) end(queued, State.EXPIRED);
        queue.clear();
        if (occupied != null && occupied.lifecycle != null) occupied.lifecycle.cancel();
        expiry.shutdown(); worker.shutdown();
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
    }
}
