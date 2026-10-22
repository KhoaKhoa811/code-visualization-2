package com.codevisualization.backend.runner;

import codeviz.tracer.Tracer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TracedRunLifecycleTest {
    private static final String SOURCE = "public class Main {\n    public static void main(String[] args) {\n        int x = 5;\n    }\n}\n";

    static class Workspace implements WorkspaceControl {
        final ContainerProfile.Identity owner = ContainerProfile.Identity.fresh();
        final List<String> calls = new ArrayList<>();
        String plan, raw = "";
        boolean failStop, failRawRead, failRecover;
        Map<String, byte[]> staged;

        public void prepareWorkspace(Map<String, byte[]> files, Duration remaining) {
            staged = files; calls.add("prepare");
            if (plan == null) plan = Tracer.plan(Tracer.prepare(new String(files.get("Main.java"), java.nio.charset.StandardCharsets.UTF_8)));
        }
        public List<String> workspaceCommand(List<String> fixed) { return fixed; }
        public String readFile(String path, int limit, Duration remaining, boolean killFirst) throws IOException {
            calls.add("read " + path + (killFirst ? " kill" : ""));
            if (path.endsWith("plan.json")) return plan;
            if (failRawRead) throw new IOException("unreadable");
            return raw;
        }
        public void stop() throws IOException { calls.add("stop"); if (failStop) throw new IOException("stop"); }
        public void remove() { calls.add("remove"); }
        public void recover() throws IOException { calls.add("recover"); if (failRecover) throw new IOException("recover"); }
        public ContainerProfile.Identity identity() { return owner; }
        public String containerId() { return "a".repeat(64); }
    }

    private static Workspace workspace(String source) {
        var w = new Workspace();
        w.plan = Tracer.plan(Tracer.prepare(source));
        return w;
    }

    private static PhaseProcess.Result exit(int code) { return new PhaseProcess.Result(PhaseGate.Cause.EXIT, code); }

    private static FixedFixtureLifecycle.PhaseExecutor phases(List<List<String>> launched, PhaseProcess.Result... results) {
        return (command, output, budget) -> { launched.add(command); return results[launched.size() - 1]; };
    }

    @Test void tracedRunBuildsTraceOnlyAfterKillReadAndCleanup() throws Exception {
        var w = workspace(SOURCE);
        w.raw = "M r1 0\n1 5\nR\n";
        var launched = new ArrayList<List<String>>();
        var result = new TracedRunLifecycle(w, phases(launched, exit(0), exit(0)), new byte[]{1}).run(SOURCE);
        assertEquals(List.of(TracedRunLifecycle.PREPARE, TracedRunLifecycle.RUN_TRACED), launched);
        assertEquals(List.of("prepare", "read /work/out/plan.json", "read /work/raw.trace kill", "stop", "remove"), w.calls);
        assertEquals(List.of("tracer-runtime.jar", "Main.java"), List.copyOf(w.staged.keySet()));
        assertTrue(result.trace().contains("\"status\":\"complete\""), result.trace());
        assertTrue(result.terminationConfirmed() && result.cleanupVerified());
    }

    @Test void compileTimeoutNeverRunsTheProgramOrReadsAPlan() throws Exception {
        var w = workspace(SOURCE);
        var launched = new ArrayList<List<String>>();
        var result = new TracedRunLifecycle(w, phases(launched, new PhaseProcess.Result(PhaseGate.Cause.TIMEOUT, null)), new byte[]{1}).run(SOURCE);
        assertEquals(1, launched.size());
        assertEquals(List.of("prepare", "stop", "remove"), w.calls);
        assertTrue(result.trace().contains("COMPILATION_TIMEOUT") && result.trace().contains("\"status\":\"unavailable\""));
    }

    @Test void runtimeTimeoutKeepsOnlyTheSafePrefix() throws Exception {
        var w = workspace(SOURCE);
        w.raw = "M r1 0\n1 5\n";
        var result = new TracedRunLifecycle(w, phases(new ArrayList<>(), exit(0),
                new PhaseProcess.Result(PhaseGate.Cause.TIMEOUT, null)), new byte[]{1}).run(SOURCE);
        assertTrue(w.calls.contains("read /work/raw.trace kill"));
        assertTrue(result.trace().contains("\"status\":\"limited\"") && result.trace().contains("EXECUTION_TIMEOUT"));
        assertTrue(result.trace().contains("\"status\":\"partial\"") && result.trace().contains("\"kind\":\"DECLARE\""));
    }

    @Test void compileErrorsSkipTheRuntimePhase() throws Exception {
        String broken = SOURCE.replace("int x = 5;", "int x = ;");
        var w = workspace(broken);
        var launched = new ArrayList<List<String>>();
        var result = new TracedRunLifecycle(w, phases(launched, exit(0)), new byte[]{1}).run(broken);
        assertEquals(1, launched.size());
        assertTrue(result.trace().contains("\"status\":\"compile_error\""));
    }

    @Test void outputOnlyProgramsRunPlainWithoutReadingRecords() throws Exception {
        // Threads keep a program output-only (Part 8a integrity guard).
        String threads = "public class Main {\n    public static void main(String[] args) { Thread.yield(); }\n}\n";
        var w = workspace(threads);
        var launched = new ArrayList<List<String>>();
        var result = new TracedRunLifecycle(w, phases(launched, exit(0), exit(0)), new byte[]{1}).run(threads);
        assertEquals(TracedRunLifecycle.RUN_PLAIN, launched.get(1));
        assertFalse(w.calls.stream().anyMatch(c -> c.contains("raw.trace")));
        assertTrue(result.trace().contains("TRACE_INTEGRITY"));
    }

    @Test void cancellationBetweenPhasesPreventsTheRuntime() throws Exception {
        var w = workspace(SOURCE);
        var launched = new ArrayList<List<String>>();
        var holder = new TracedRunLifecycle[1];
        holder[0] = new TracedRunLifecycle(w, (command, output, budget) -> {
            launched.add(command);
            assertTrue(holder[0].cancel());
            return exit(0);
        }, new byte[]{1});
        var result = holder[0].run(SOURCE);
        assertEquals(1, launched.size());
        assertTrue(result.trace().contains("\"status\":\"cancelled\"") && result.trace().contains("RUN_CANCELLED"));
    }

    // ---- Part 6g: input-wait limit -------------------------------------------------------------

    private static final String READS = "import java.util.Scanner;\npublic class Main {\n    public static void main(String[] args) {\n"
            + "        Scanner in = new Scanner(System.in);\n        int n = in.nextInt();\n    }\n}\n";

    /** A workspace whose kernel view answers stdinWaiting from a fixed sequence (the last answer repeats). */
    static final class WaitingWorkspace extends Workspace {
        final List<Boolean> answers;
        int asked;
        WaitingWorkspace(List<Boolean> answers) { this.answers = answers; }
        @Override public synchronized Boolean stdinWaiting(Duration remaining) {
            return answers.get(Math.min(asked++, answers.size() - 1));
        }
    }

    /** The runtime phase runs until the lifecycle cancels it, or {@code exitAfter} passes. */
    private static FixedFixtureLifecycle.PhaseExecutor untilCancelled(TracedRunLifecycle[] holder, Duration exitAfter) {
        return (command, output, budget) -> {
            if (command == TracedRunLifecycle.PREPARE) return exit(0);
            long end = System.nanoTime() + exitAfter.toNanos();
            while (System.nanoTime() < end) {
                if (holder[0].cancellationRequested()) return new PhaseProcess.Result(PhaseGate.Cause.CANCELLED, null);
                try { Thread.sleep(20); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            }
            return exit(0);
        };
    }

    @Test void aReadBlockedLongerThanTheInputWaitLimitStopsTheRun() throws Exception {
        var w = new WaitingWorkspace(List.of(true));
        w.plan = Tracer.plan(Tracer.prepare(READS));
        var holder = new TracedRunLifecycle[1];
        holder[0] = new TracedRunLifecycle(w, untilCancelled(holder, Duration.ofSeconds(20)), new byte[]{1});
        holder[0].inputWait(Duration.ofMillis(1500));
        long start = System.nanoTime();
        var result = holder[0].run(READS, true);
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(result.trace().contains("\"status\":\"limited\"") && result.trace().contains("INPUT_WAIT_TIMEOUT"), result.trace());
        assertTrue(result.trace().contains("Input wait exceeded 30000 ms"));
        assertTrue(elapsed >= 1500 && elapsed < 10_000, "stopped after the limit: " + elapsed + " ms");
        assertNull(holder[0].waiting(), "no waiting state after the runtime phase");
    }

    @Test void onlyUnbrokenWaitingCountsAndUnknownNeverStopsTheRun() throws Exception {
        List<List<Boolean>> cases = List.of(List.of(true, false, true, false, true, false), java.util.Arrays.asList((Boolean) null, null));
        for (List<Boolean> answers : cases) {
            var w = new WaitingWorkspace(answers);
            w.plan = Tracer.plan(Tracer.prepare(READS));
            var holder = new TracedRunLifecycle[1];
            holder[0] = new TracedRunLifecycle(w, untilCancelled(holder, Duration.ofMillis(3500)), new byte[]{1});
            holder[0].inputWait(Duration.ofMillis(1500));
            var result = holder[0].run(READS, true);
            assertFalse(result.trace().contains("INPUT_WAIT_TIMEOUT"), answers + ": " + result.trace());
            assertTrue(w.asked >= 3, "the kernel was asked about once a second");
        }
    }

    @Test void unconfirmedTerminationWithholdsTheTrace() throws Exception {
        var w = workspace(SOURCE);
        w.raw = "M r1 0\n1 5\nR\n";
        w.failStop = true;
        var result = new TracedRunLifecycle(w, phases(new ArrayList<>(), exit(0), exit(0)), new byte[]{1}).run(SOURCE);
        assertNull(result.trace());
        assertEquals(List.of(TracedRunLifecycle.Failure.TERMINATION_UNCONFIRMED), result.infrastructureFailures());
        assertFalse(w.calls.contains("remove"));
    }

    @Test void recoveryProvesTerminationThenBuildsTheWithheldTrace() throws Exception {
        var w = workspace(SOURCE);
        w.raw = "M r1 0\n1 5\nR\n";
        w.failStop = true;
        w.failRecover = true;
        var lifecycle = new TracedRunLifecycle(w, phases(new ArrayList<>(), exit(0), exit(0)), new byte[]{1});
        var held = lifecycle.runReserved(SOURCE, "run-7d", System.nanoTime() + Duration.ofSeconds(10).toNanos());
        assertNull(held.trace());
        assertSame(held, lifecycle.recover()); // failed recovery keeps the held result
        w.failRecover = false;
        var recovered = lifecycle.recover();
        assertTrue(recovered.terminationConfirmed() && recovered.cleanupVerified());
        assertEquals(List.of(TracedRunLifecycle.Failure.TERMINATION_UNCONFIRMED), recovered.infrastructureFailures());
        assertTrue(recovered.trace().contains("\"runId\":\"run-7d\"") && recovered.trace().contains("\"status\":\"completed\""));
        assertSame(recovered, lifecycle.recover());
    }

    @Test void planForOtherSourceIsAWorkerFailureAndNothingRuns() throws Exception {
        var w = workspace(SOURCE.replace("5", "6"));
        var launched = new ArrayList<List<String>>();
        var result = new TracedRunLifecycle(w, phases(launched, exit(0)), new byte[]{1}).run(SOURCE);
        assertEquals(1, launched.size());
        assertEquals(List.of(TracedRunLifecycle.Failure.WORKER_FAILED), result.infrastructureFailures());
        assertNull(result.trace());
        assertTrue(result.terminationConfirmed() && result.cleanupVerified());
    }

    @Test void expiredPreparationDeadlineIsASetupFailureWithoutContainerWork() throws Exception {
        var w = workspace(SOURCE);
        var result = new TracedRunLifecycle(w, phases(new ArrayList<>()), new byte[]{1})
                .runReserved(SOURCE, "run-late", System.nanoTime() - 1);
        assertEquals(List.of(TracedRunLifecycle.Failure.SETUP_FAILED), result.infrastructureFailures());
        assertTrue(w.calls.isEmpty());
        assertTrue(result.terminationConfirmed() && result.cleanupVerified());
    }

    @Test void unreadableRecordsLeaveCaptureUnavailable() throws Exception {
        var w = workspace(SOURCE);
        w.failRawRead = true;
        var result = new TracedRunLifecycle(w, phases(new ArrayList<>(), exit(0), exit(0)), new byte[]{1}).run(SOURCE);
        assertEquals(List.of(TracedRunLifecycle.Failure.TRANSPORT_FAILED), result.infrastructureFailures());
        assertTrue(result.trace().contains("\"status\":\"unavailable\""));
    }

    @Test void admissionRejectsOversizedOrMalformedSourceBeforeAnyContainerWork() {
        var w = workspace(SOURCE);
        var lifecycle = new TracedRunLifecycle(w, phases(new ArrayList<>()), new byte[]{1});
        var big = assertThrows(TracedRunLifecycle.AdmissionException.class,
                () -> lifecycle.run("x".repeat(TracedRunLifecycle.SOURCE_LIMIT + 1)));
        assertEquals("SOURCE_LIMIT", big.code());
        // 87,382 three-byte characters exceed the byte cap while staying under the char count.
        assertEquals("SOURCE_LIMIT", assertThrows(TracedRunLifecycle.AdmissionException.class,
                () -> lifecycle.run("€".repeat(87_382))).code());
        assertEquals("SOURCE_INVALID", assertThrows(TracedRunLifecycle.AdmissionException.class,
                () -> lifecycle.run("class Main {} \ud800")).code());
        assertTrue(w.calls.isEmpty());
    }
}
