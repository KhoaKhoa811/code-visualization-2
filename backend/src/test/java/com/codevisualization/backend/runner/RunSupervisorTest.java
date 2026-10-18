package com.codevisualization.backend.runner;

import static com.codevisualization.backend.runner.RunSupervisor.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunSupervisorTest {
    private static final long QUEUE_WAIT = RunSupervisor.QUEUE_WAIT.toNanos();

    @Test void runPassesThroughContractPhasesAndPublishesTheTraceOnlyAtTheEnd() throws Exception {
        var runs = new TestRuns();
        try (var supervisor = runs.open()) {
            var run = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            var executing = TestRuns.await(supervisor, run, EXECUTING);
            assertEquals(EXECUTING, executing.phase());
            assertTrue(executing.slotHeld());
            assertNull(executing.trace());
            assertEquals(TestRuns.SHA, executing.sourceSha256());
            runs.release.countDown();
            var finished = TestRuns.await(supervisor, run, FINISHED);
            assertTrue(finished.terminationConfirmed() && finished.cleanupVerified() && !finished.slotHeld());
            assertTrue(finished.trace().contains("\"runId\":\"" + run.id() + "\""), finished.trace());
            assertTrue(finished.trace().contains("\"status\":\"completed\""));
            assertTrue(finished.revision() > executing.revision());
            assertEquals(finished.revision(), supervisor.snapshot(run).revision()); // unchanged facts keep the revision
            assertTrue(supervisor.output(run).isPresent());
        }
    }

    @Test void admissionChecksHashAndSourceBeforeAnyContainerWork() {
        var runs = new TestRuns();
        try (var supervisor = runs.open()) {
            assertEquals("SOURCE_HASH_MISMATCH", assertThrows(TracedRunLifecycle.AdmissionException.class,
                    () -> supervisor.submit(TestRuns.SOURCE, "0".repeat(64))).code());
            assertEquals("SOURCE_INVALID", assertThrows(TracedRunLifecycle.AdmissionException.class,
                    () -> supervisor.submit("class Main {} \ud800", TestRuns.SHA)).code());
            assertTrue(runs.workspaces.isEmpty());
        }
    }

    @Test void oneSlotTwoQueuedThenBusyAndFifoPromotion() throws Exception {
        var runs = new TestRuns();
        try (var supervisor = runs.open()) {
            var first = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            var second = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            var third = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            assertThrows(RunSupervisor.BusyException.class, () -> supervisor.submit(TestRuns.SOURCE, TestRuns.SHA));
            var queued = supervisor.snapshot(second);
            assertEquals(QUEUED, queued.phase());
            assertFalse(queued.slotHeld());
            assertEquals(1, runs.workspaces.size()); // queued runs own no container
            runs.release.countDown();
            for (var run : List.of(first, second, third)) assertEquals(FINISHED, TestRuns.await(supervisor, run, FINISHED).phase());
            assertEquals(3, runs.workspaces.size());
        }
    }

    @Test void queuedCancellationFinishesWithoutASlotOrContainer() throws Exception {
        var runs = new TestRuns();
        try (var supervisor = runs.open()) {
            var first = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            var queued = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            assertTrue(supervisor.cancel(queued));
            assertFalse(supervisor.cancel(queued));
            var cancelled = supervisor.snapshot(queued);
            assertEquals(FINISHED, cancelled.phase());
            assertFalse(cancelled.slotHeld());
            assertTrue(cancelled.trace().contains("\"status\":\"cancelled\"") && cancelled.trace().contains("RUN_CANCELLED"));
            runs.release.countDown();
            TestRuns.await(supervisor, first, FINISHED);
            assertEquals(1, runs.workspaces.size());
        }
    }

    @Test void activeCancellationStopsTheRuntimeAndReleasesTheSlot() throws Exception {
        var runs = new TestRuns();
        try (var supervisor = runs.open()) {
            var run = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            TestRuns.await(supervisor, run, EXECUTING);
            assertTrue(supervisor.cancel(run));
            var finished = TestRuns.await(supervisor, run, FINISHED);
            assertTrue(finished.trace().contains("\"status\":\"cancelled\""), finished.trace());
            assertFalse(supervisor.cancel(run));
            assertEquals(List.of("prepare", "read /work/out/plan.json", "read /work/raw.trace kill", "stop", "remove"),
                    runs.workspaces.get(0).calls);
        }
    }

    @Test void queueExpiryIsASchedulingOutcomeAndNeverLaunches() throws Exception {
        var runs = new TestRuns();
        try (var supervisor = runs.open()) {
            var first = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            var queued = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            runs.clock.addAndGet(QUEUE_WAIT);
            supervisor.housekeeping();
            assertTrue(supervisor.expired(queued));
            assertThrows(IllegalStateException.class, () -> supervisor.snapshot(queued));
            assertFalse(supervisor.cancel(queued));
            runs.release.countDown();
            TestRuns.await(supervisor, first, FINISHED);
            assertEquals(1, runs.workspaces.size());
        }
    }

    @Test void unconfirmedStopHoldsTheSlotUntilExplicitRecovery() throws Exception {
        var runs = new TestRuns();
        runs.failStop = true;
        runs.release.countDown();
        try (var supervisor = runs.open()) {
            var held = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            var stopping = TestRuns.await(supervisor, held, STOPPING);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (stopping.infrastructureFailures().isEmpty() && System.nanoTime() < deadline) stopping = supervisor.snapshot(held);
            assertEquals(List.of("TERMINATION_UNCONFIRMED"), stopping.infrastructureFailures());
            assertTrue(stopping.slotHeld());
            assertNull(stopping.trace());
            var waiting = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            assertEquals(QUEUED, supervisor.snapshot(waiting).phase()); // held slot blocks promotion
            runs.failStop = false;
            assertTrue(supervisor.recover(held));
            var recovered = supervisor.snapshot(held);
            assertEquals(FINISHED, recovered.phase());
            assertEquals(List.of("TERMINATION_UNCONFIRMED"), recovered.infrastructureFailures());
            assertTrue(recovered.trace().contains("\"status\":\"completed\""));
            assertFalse(supervisor.recover(held));
            assertEquals(FINISHED, TestRuns.await(supervisor, waiting, FINISHED).phase());
        }
    }

    @Test void finishedRunsAreRetainedBoundedly() throws Exception {
        var runs = new TestRuns();
        runs.release.countDown();
        try (var supervisor = runs.open()) {
            String firstId = null;
            for (int i = 0; i < RunSupervisor.RETAINED_LIMIT + 1; i++) {
                var run = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
                if (firstId == null) firstId = run.id();
                TestRuns.await(supervisor, run, FINISHED);
            }
            assertTrue(supervisor.find(firstId).isEmpty());
            var last = supervisor.submit(TestRuns.SOURCE, TestRuns.SHA);
            TestRuns.await(supervisor, last, FINISHED);
            runs.clock.addAndGet(RunSupervisor.RETENTION.toNanos());
            assertTrue(supervisor.find(last.id()).isEmpty());
        }
    }
}
