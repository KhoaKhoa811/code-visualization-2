package com.codevisualization.backend.runner;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedFixtureLifecycleTest {
    private static class Control implements FixtureControl {
        boolean failPrepare, failStop, failRemove, stopped, removed;
        private final ContainerProfile.Identity owner = ContainerProfile.Identity.fresh();
        public void prepare(FixedFixture fixture) throws IOException { if (failPrepare) throw new IOException("setup"); }
        public List<String> phaseCommand(FixedFixture fixture, boolean compile) { return List.of(compile ? "compile" : "runtime"); }
        public void stop() throws IOException { stopped = true; if (failStop) throw new IOException("stop"); }
        public void remove() throws IOException { removed = true; if (failRemove) throw new IOException("remove"); }
        public ContainerProfile.Identity identity() { return owner; }
        public String containerId() { return "a".repeat(64); }
    }

    @Test void compilerFailureOrLimitNeverRequestsRuntime() {
        for (var cause : PhaseGate.Cause.values()) {
            var calls = new AtomicInteger(); var control = new Control();
            var result = new FixedFixtureLifecycle(control, (command, output, budget) -> {
                calls.incrementAndGet(); assertEquals(10_000, budget.toMillis());
                return new PhaseProcess.Result(cause, cause == PhaseGate.Cause.EXIT ? 1 : null);
            }).run(FixedFixture.NORMAL);
            assertEquals(1, calls.get()); assertFalse(result.runtimeRequested());
            assertTrue(result.terminationConfirmed()); assertTrue(result.cleanupVerified());
        }
    }

    @Test void phasesShareOutputAndUseDistinctBudgets() {
        var calls = new AtomicInteger();
        var lifecycle = new FixedFixtureLifecycle(new Control(), (command, output, budget) -> {
            int index = calls.getAndIncrement();
            assertEquals(index == 0 ? 10_000 : 5_000, budget.toMillis());
            if (index == 0) output.append(true, new byte[]{67}, 1);
            else assertEquals(1, output.size());
            return new PhaseProcess.Result(PhaseGate.Cause.EXIT, 0);
        });
        var result = lifecycle.run(FixedFixture.NORMAL);
        assertEquals(2, calls.get()); assertTrue(result.runtimeRequested());
        assertEquals(FixedFixtureLifecycle.Outcome.COMPLETED, result.outcome());
        assertArrayEquals(new byte[]{67}, result.stderr());
        result.stderr()[0] = 0;
        assertEquals(67, result.stderr()[0]);
        assertThrows(IllegalStateException.class, () -> lifecycle.run(FixedFixture.NORMAL));
    }

    @Test void cleanupFailureCannotReplaceTimeoutOrClaimRelease() {
        var control = new Control(); control.failRemove = true;
        var result = new FixedFixtureLifecycle(control, (command, output, budget) ->
                new PhaseProcess.Result(PhaseGate.Cause.TIMEOUT, null)).run(FixedFixture.NORMAL);
        assertEquals(FixedFixtureLifecycle.Outcome.COMPILATION_TIMEOUT, result.outcome());
        assertTrue(result.terminationConfirmed()); assertFalse(result.cleanupVerified());
        assertEquals(List.of(FixedFixtureLifecycle.Failure.CLEANUP_UNCONFIRMED), result.infrastructureFailures());
    }

    @Test void setupAndStopFailuresRemainVisibleAndPreventRemoval() {
        var control = new Control(); control.failPrepare = true; control.failStop = true;
        var result = new FixedFixtureLifecycle(control, (command, output, budget) -> {
            fail("No phase after setup failure"); return null;
        }).run(FixedFixture.NORMAL);
        assertEquals(FixedFixtureLifecycle.Outcome.INFRASTRUCTURE_FAILURE, result.outcome());
        assertFalse(result.terminationConfirmed()); assertFalse(result.cleanupVerified());
        assertFalse(control.removed); assertTrue(control.stopped);
        assertEquals(2, result.infrastructureFailures().size());
    }
}
