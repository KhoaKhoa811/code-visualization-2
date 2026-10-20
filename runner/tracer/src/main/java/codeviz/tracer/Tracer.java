package codeviz.tracer;

import codeviz.tracer.Analyzer.Analysis;
import codeviz.tracer.Plan.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tracer entry points. {@link #prepare} compiles and plans (no execution) and
 * returns either instrumented classes, original classes for an output-only run,
 * or nothing on compile errors; in production it runs inside the isolated runner
 * through {@link Prepare}. The backend reads the resulting plan with
 * {@link #fromPlan} and builds trace-v1 with {@link #trace} or {@link #notRun}
 * from what it observed. Running the classes is never this class's job.
 */
public final class Tracer {
    /** Why the run ended, as decided by the orchestrator (never by the user JVM). */
    public enum Cause { EXIT, EXECUTION_TIMEOUT, OUTPUT_LIMIT, CANCELLED, COMPILATION_TIMEOUT, INPUT_DISCONNECTED, INTERACTIVE_TIMEOUT }

    /** Stop cause plus the process exit code (meaningful for {@link Cause#EXIT}). */
    public record Outcome(Cause cause, int exitCode) {
        public static Outcome exited(int exitCode) { return new Outcome(Cause.EXIT, exitCode); }
    }

    /** Classes to run (binary name to bytes); {@code traced} says whether the recorder is needed. */
    public record Prepared(Analysis analysis, Map<String, byte[]> classes, boolean traced) {
        public boolean compiled() { return analysis.compileErrors().isEmpty(); }
        /** Part 8g: the source refers to System.in. */
        public boolean interactive() { return analysis.interactive(); }
    }

    private Tracer() { }

    public static Prepared prepare(String source) {
        Analysis analysis = Analyzer.analyze(source);
        if (!analysis.compileErrors().isEmpty()) return new Prepared(analysis, Map.of(), false);
        if (!analysis.unsupported().isEmpty()) return new Prepared(analysis, analysis.classes(), false);
        try {
            // Main and every traced class are instrumented (Part 8e); other classes run untraced (Part 8a).
            Map<String, byte[]> classes = new java.util.HashMap<>(analysis.classes());
            for (var entry : analysis.classes().entrySet()) {
                if (Instrumenter.planned(entry.getKey(), analysis.plans()))
                    classes.put(entry.getKey(), Instrumenter.instrument(entry.getValue(), analysis.plans(), analysis.sites(), analysis.classes()));
            }
            return new Prepared(analysis, Map.copyOf(classes), true);
        } catch (Instrumenter.MappingException | RuntimeException e) {
            List<Diagnostic> unsupported = new ArrayList<>(analysis.unsupported());
            unsupported.add(new Diagnostic("capture", "TRACE_MAPPING",
                    "The compiled code did not match the visualization plan (" + e.getMessage()
                            + "); the program can still run with output only.", null, null));
            Analysis outputOnly = new Analysis(analysis.source(), analysis.compileErrors(), List.copyOf(unsupported),
                    analysis.uncaptured(), analysis.classes(), analysis.plans(), analysis.sites(), analysis.rootScope(), analysis.interactive());
            return new Prepared(outputOnly, analysis.classes(), false);
        }
    }

    /** The plan file content that lets {@link #fromPlan} rebuild this preparation without classes. */
    public static String plan(Prepared prepared) {
        return PlanCodec.write(prepared.analysis(), prepared.traced(), TraceBuilder.sha256(prepared.analysis().source()));
    }

    /** Rebuilds a preparation from a plan produced for exactly {@code source}; classes are not included. */
    public static Prepared fromPlan(String source, String planJson) {
        PlanCodec.Decoded decoded = PlanCodec.read(source, planJson);
        return new Prepared(decoded.analysis(), Map.of(), decoded.traced());
    }

    /** Trace-v1 JSON after a run. {@code rawText} is ignored unless the program was traced. */
    public static String trace(Prepared prepared, String runId, String rawText, Outcome outcome) {
        return trace(prepared, runId, rawText, outcome, new byte[0]);
    }

    /** Part 8g: {@code input} is every byte delivered to the program's standard input, in order. */
    public static String trace(Prepared prepared, String runId, String rawText, Outcome outcome, byte[] input) {
        if (!prepared.compiled()) return TraceBuilder.compileError(prepared.analysis(), runId);
        if (!prepared.traced()) return TraceBuilder.outputOnly(prepared.analysis(), runId, outcome);
        return TraceBuilder.traced(prepared.analysis(), runId, completeLines(rawText), outcome, input);
    }

    /** Convenience for tests: raw records already split into lines, normal exit. */
    public static String trace(Prepared prepared, String runId, List<String> raw, int exitCode) {
        return trace(prepared, runId, raw, exitCode, new byte[0]);
    }

    public static String trace(Prepared prepared, String runId, List<String> raw, int exitCode, byte[] input) {
        return trace(prepared, runId, raw.isEmpty() ? "" : String.join("\n", raw) + "\n", Outcome.exited(exitCode), input);
    }

    /** Trace-v1 JSON when the run stopped before a plan existed (compile-phase stop). */
    public static String notRun(String source, String runId, Outcome outcome) {
        return TraceBuilder.notRun(source, runId, outcome);
    }

    /** Only newline-terminated records are complete; a killed recorder may leave a torn last line. */
    static List<String> completeLines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        int end = text.lastIndexOf('\n');
        if (end < 0) return List.of();
        return List.of(text.substring(0, end).split("\n", -1));
    }
}
