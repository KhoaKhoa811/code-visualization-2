package codeviz.tracer;

import codeviz.tracer.Analyzer.Analysis;
import codeviz.tracer.Plan.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Part 7b entry point. {@link #prepare} compiles and plans (no execution) and
 * returns either instrumented classes, original classes for an output-only run,
 * or nothing on compile errors. {@link #trace} builds the trace-v1 JSON from the
 * raw records and exit code observed by the caller. Running the classes is the
 * caller's job; in production that is the isolated runner (Part 7c).
 */
public final class Tracer {
    /** Classes to run (binary name to bytes); {@code traced} says whether the recorder is needed. */
    public record Prepared(Analysis analysis, Map<String, byte[]> classes, boolean traced) {
        public boolean compiled() { return analysis.compileErrors().isEmpty(); }
    }

    private Tracer() { }

    public static Prepared prepare(String source) {
        Analysis analysis = Analyzer.analyze(source);
        if (!analysis.compileErrors().isEmpty()) return new Prepared(analysis, Map.of(), false);
        if (!analysis.unsupported().isEmpty()) return new Prepared(analysis, analysis.classes(), false);
        try {
            byte[] main = Instrumenter.instrument(analysis.classes().get("Main"), analysis.plan(), analysis.sites());
            return new Prepared(analysis, Map.of("Main", main), true);
        } catch (Instrumenter.MappingException | RuntimeException e) {
            List<Diagnostic> unsupported = new ArrayList<>(analysis.unsupported());
            unsupported.add(new Diagnostic("capture", "TRACE_MAPPING",
                    "The compiled code did not match the visualization plan (" + e.getMessage()
                            + "); the program can still run with output only.", null, null));
            Analysis outputOnly = new Analysis(analysis.source(), analysis.compileErrors(), List.copyOf(unsupported),
                    analysis.classes(), analysis.plan(), analysis.sites(), analysis.rootScope());
            return new Prepared(outputOnly, analysis.classes(), false);
        }
    }

    /** Trace-v1 JSON for a prepared program. {@code raw} is ignored unless it was traced. */
    public static String trace(Prepared prepared, String runId, List<String> raw, int exitCode) {
        if (!prepared.compiled()) return TraceBuilder.compileError(prepared.analysis(), runId);
        var outcome = new TraceBuilder.Outcome(exitCode);
        if (!prepared.traced()) return TraceBuilder.outputOnly(prepared.analysis(), runId, outcome);
        return TraceBuilder.traced(prepared.analysis(), runId, raw, outcome);
    }
}
