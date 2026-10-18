package com.codevisualization.backend.runner;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixtureRunSupervisorTest {
    static class Control implements FixtureControl {
        final ContainerProfile.Identity owner = ContainerProfile.Identity.fresh();
        volatile boolean failCleanup, failRecovery;
        final AtomicInteger recoveries = new AtomicInteger();
        public void prepare(FixedFixture fixture) { }
        public List<String> phaseCommand(FixedFixture fixture, boolean compile) { return List.of(compile ? "compile" : "run"); }
        public void stop() { }
        public void remove() throws IOException { if (failCleanup) throw new IOException("cleanup"); }
        public void recover() throws IOException {
            recoveries.incrementAndGet(); if (failRecovery) throw new IOException("recovery");
        }
        public ContainerProfile.Identity identity() { return owner; }
        public String containerId() { return "a".repeat(64); }
    }

    static void await(FixtureRunSupervisor.Handle handle, FixtureRunSupervisor.State state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handle.snapshot().state() != state && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(state, handle.snapshot().state(), handle.snapshot().toString());
    }

    @Test void holdsSlotAcrossFailureAndReleasesOnceAfterExplicitRecovery() throws Exception {
        var first = new Control(); first.failCleanup = true; first.failRecovery = true;
        var created = new AtomicInteger();
        try (var supervisor = new FixtureRunSupervisor(() -> new FixedFixtureLifecycle(
                created.getAndIncrement() == 0 ? first : new Control(),
                (cmd, out, time) -> new PhaseProcess.Result(PhaseGate.Cause.TIMEOUT, null)), System::nanoTime)) {
            var active = supervisor.submit(FixedFixture.NORMAL);
            await(active, FixtureRunSupervisor.State.HELD);
            var next = supervisor.submit(FixedFixture.NORMAL);
            assertEquals(1, created.get()); assertEquals(FixtureRunSupervisor.State.QUEUED, next.snapshot().state());
            assertFalse(supervisor.recover(active)); assertEquals(1, created.get());
            first.failRecovery = false;
            assertTrue(supervisor.recover(active)); assertFalse(supervisor.recover(active));
            await(next, FixtureRunSupervisor.State.FINISHED);
            assertEquals(2, created.get()); assertEquals(2, first.recoveries.get());
            assertEquals(FixedFixtureLifecycle.Outcome.COMPILATION_TIMEOUT, active.snapshot().result().outcome());
            assertTrue(active.snapshot().result().infrastructureFailures().contains(FixedFixtureLifecycle.Failure.CLEANUP_UNCONFIRMED));
            assertTrue(active.snapshot().result().infrastructureFailures().contains(FixedFixtureLifecycle.Failure.RECOVERY_FAILED));
        }
    }

    @Test void boundsQueueAndExpiresWithoutLaunchingQueuedWork() throws Exception {
        var clock = new AtomicLong(); var control = new Control(); control.failCleanup = true;
        var created = new AtomicInteger();
        try (var supervisor = new FixtureRunSupervisor(() -> {
            created.incrementAndGet(); return new FixedFixtureLifecycle(control,
                    (cmd, out, time) -> new PhaseProcess.Result(PhaseGate.Cause.TIMEOUT, null));
        }, clock::get)) {
            var active = supervisor.submit(FixedFixture.NORMAL); await(active, FixtureRunSupervisor.State.HELD);
            var a = supervisor.submit(FixedFixture.NORMAL); var b = supervisor.submit(FixedFixture.NORMAL);
            assertThrows(IllegalStateException.class, () -> supervisor.submit(FixedFixture.NORMAL));
            clock.set(TimeUnit.SECONDS.toNanos(30)); supervisor.expireQueued();
            assertEquals(FixtureRunSupervisor.State.QUEUE_EXPIRED, a.snapshot().state());
            assertEquals(FixtureRunSupervisor.State.QUEUE_EXPIRED, b.snapshot().state());
            assertFalse(supervisor.cancel(a)); assertEquals(1, created.get());
            assertTrue(supervisor.recover(active)); assertEquals(1, created.get());
        }
    }

    @Test void queuedCancellationAndShutdownNeverLaunchWaitingWork() throws Exception {
        var entered = new CountDownLatch(1);
        var created = new AtomicInteger();
        var current = new java.util.concurrent.atomic.AtomicReference<FixedFixtureLifecycle>();
        try (var supervisor = new FixtureRunSupervisor(() -> {
            created.incrementAndGet();
            var lifecycle = new FixedFixtureLifecycle(new Control(), (cmd, out, time) -> {
                entered.countDown();
                // Models PhaseProcess: the phase observes cancellation by polling, not interruption.
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!current.get().cancellationRequested() && System.nanoTime() < end) {
                    try { Thread.sleep(5); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
                return new PhaseProcess.Result(PhaseGate.Cause.CANCELLED, null);
            });
            current.set(lifecycle); return lifecycle;
        }, System::nanoTime)) {
            var active = supervisor.submit(FixedFixture.NORMAL); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = supervisor.submit(FixedFixture.NORMAL);
            assertTrue(supervisor.cancel(queued)); assertFalse(supervisor.cancel(queued));
            assertNull(queued.snapshot().result());
            var another = supervisor.submit(FixedFixture.NORMAL);
            supervisor.close(); await(active, FixtureRunSupervisor.State.FINISHED);
            assertEquals(FixtureRunSupervisor.State.CANCELLED, another.snapshot().state());
            assertEquals(1, created.get());
            assertThrows(IllegalStateException.class, () -> supervisor.submit(FixedFixture.NORMAL));
        }
    }
}
