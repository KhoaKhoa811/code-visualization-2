package com.codevisualization.backend.runner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "codeviz.docker.live", matches = "true")
class CancellationRecoveryLiveTest {
    private Path docker() { return Path.of(System.getProperty("codeviz.docker.executable")); }

    @Test void cancelsRealCompilerAndRuntimeAndRemovesTheirContainers() throws Exception {
        for (FixedFixture fixture : List.of(FixedFixture.COMPILE_TIMEOUT, FixedFixture.RUNTIME_TIMEOUT)) {
            var lifecycle = new FixedFixtureLifecycle(docker());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<FixedFixtureLifecycle.Result> result = executor.submit(() -> lifecycle.run(fixture));
                // Preparation alone may use its full 10 s budget before a phase starts.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean observed = false;
                try (var probe = new BoundedCommandRunner()) {
                    while (!observed && !result.isDone() && System.nanoTime() < deadline) {
                        var progress = lifecycle.progress();
                        if (fixture == FixedFixture.RUNTIME_TIMEOUT) {
                            observed = progress.phase().equals("executing") && progress.outputBytes() > 0;
                        } else if (progress.phase().equals("compiling") && progress.containerId() != null) {
                            // docker top requires a PID column in custom ps output.
                            String processes = probe.execute(List.of(docker().toString(), "--context", "desktop-linux",
                                    "top", progress.containerId(), "-eo", "pid,comm"), Duration.ofSeconds(2));
                            observed = processes.lines().map(String::strip)
                                    .anyMatch(line -> line.endsWith(" javac") || line.endsWith("\tjavac"));
                        }
                        if (!observed) Thread.sleep(20);
                    }
                }
                assertTrue(observed, "Actual isolated worker must start before cancellation");
                long cancelled = System.nanoTime();
                assertTrue(lifecycle.cancel()); assertFalse(lifecycle.cancel());
                var finished = result.get(8, TimeUnit.SECONDS);
                System.out.println("Cancelled " + fixture + ": " + finished.name());
                assertEquals(FixedFixtureLifecycle.Outcome.CANCELLED, finished.outcome());
                assertTrue(finished.terminationConfirmed()); assertTrue(finished.cleanupVerified());
                assertTrue(finished.infrastructureFailures().isEmpty());
                assertTrue(System.nanoTime() - cancelled < TimeUnit.SECONDS.toNanos(5));
            } finally {
                lifecycle.cancel(); executor.shutdown();
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }

    @Test void knownIdRecoveryAndStartupInventoryPreventUnownedAdmission() throws Exception {
        var control = new BoundedCommandRunner();
        var adapter = new DockerControlAdapter(docker(), control);
        System.out.println("Recovery probe: " + adapter.identity().name());
        try {
            adapter.create(); adapter.startIdle();
            assertThrows(java.io.IOException.class, () -> FixtureRunSupervisor.open(docker()));
            // Deliberate invalid repeat quarantines the live idle environment.
            assertThrows(java.io.IOException.class, adapter::startIdle);
            assertTrue(adapter.quarantined());
        } finally {
            control.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!control.settled() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(control.settled());
            adapter.recover(); // Ownership inspection, force removal, exact-ID absence.
        }
        try (var supervisor = FixtureRunSupervisor.open(docker())) {
            var run = supervisor.submit(FixedFixture.NORMAL);
            FixtureRunSupervisorTest.await(run, FixtureRunSupervisor.State.FINISHED);
            assertEquals(FixedFixtureLifecycle.Outcome.COMPLETED, run.snapshot().result().outcome());
        }
    }
}
