package com.codevisualization.backend.runner;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test supervisors whose runs use the real {@link TracedRunLifecycle} with a fake
 * container and phases: the runtime phase waits for {@link #release} or for
 * cancellation, so tests control slot occupancy deterministically.
 */
public final class TestRuns {
    public static final String SOURCE = "public class Main {\n    public static void main(String[] args) {\n        int x = 5;\n    }\n}\n";
    public static final String SHA = RunSupervisor.sha256(SOURCE.getBytes(StandardCharsets.UTF_8));

    public final CountDownLatch release = new CountDownLatch(1);
    public final AtomicLong clock = new AtomicLong();
    final List<TracedRunLifecycleTest.Workspace> workspaces = new CopyOnWriteArrayList<>();
    final List<String> runtimeStarts = new CopyOnWriteArrayList<>();
    public volatile boolean failStop;

    public RunSupervisor open() {
        return new RunSupervisor(() -> {
            var w = new TracedRunLifecycleTest.Workspace();
            w.raw = "M r1 0\n1 5\nR\n";
            w.failStop = failStop;
            workspaces.add(w);
            var holder = new TracedRunLifecycle[1];
            holder[0] = new TracedRunLifecycle(w, (command, output, budget) -> {
                if (command.equals(TracedRunLifecycle.PREPARE)) return new PhaseProcess.Result(PhaseGate.Cause.EXIT, 0);
                runtimeStarts.add(w.owner.owner().toString());
                try {
                    while (!release.await(10, TimeUnit.MILLISECONDS)) {
                        if (holder[0].cancellationRequested()) return new PhaseProcess.Result(PhaseGate.Cause.CANCELLED, null);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new PhaseProcess.Result(PhaseGate.Cause.EXIT, 0);
            }, new byte[]{1});
            return holder[0];
        }, clock::get);
    }

    /** Polls until the run reaches the phase or the timeout passes. */
    public static RunSupervisor.Snapshot await(RunSupervisor supervisor, RunSupervisor.Run run, RunSupervisor.Phase phase)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var snapshot = supervisor.snapshot(run);
        while (snapshot.phase() != phase && System.nanoTime() < deadline) {
            Thread.sleep(5);
            snapshot = supervisor.snapshot(run);
        }
        return snapshot;
    }
}
