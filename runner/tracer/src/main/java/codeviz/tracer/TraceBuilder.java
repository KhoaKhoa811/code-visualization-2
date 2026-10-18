package codeviz.tracer;

import codeviz.tracer.Analyzer.Analysis;
import codeviz.tracer.Plan.Cond;
import codeviz.tracer.Plan.Diagnostic;
import codeviz.tracer.Plan.Site;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Trusted side of the tracer: turns the static site table, the untrusted raw
 * records and the process outcome into a trace-v1 envelope. Source ranges,
 * operators, declarations, scopes and previous values come from static facts and
 * this builder's own reconstructed state. Records that disagree with that state
 * end capture at the last safe operation; nothing is invented to repair them.
 */
final class TraceBuilder {
    static final long TRACE_LIMIT = 8L * 1024 * 1024;
    static final int RECORD_LIMIT = 256 * 1024;
    private static final String FRAME = "f0";
    static final String MAIN_METHOD = "Main.main(java.lang.String[]):void";

    private static final class Inconsistent extends Exception {
        Inconsistent(String message) { super(message); }
    }

    /** Terminal status, reason and (for stop causes) the diagnostic code and phase. */
    private record Execution(String status, String reason, String code, String phase) {
        Diagnostic diagnostic() { return code == null ? null : new Diagnostic(phase, code, reason, null, null); }
    }

    private static Execution execution(Tracer.Outcome outcome, String outputPhase) {
        return switch (outcome.cause()) {
            case EXIT -> outcome.exitCode() == 0
                    ? new Execution("completed", "Normal return from main.", null, null)
                    : new Execution("failed", "Program exited with status " + outcome.exitCode() + ".", null, null);
            case EXECUTION_TIMEOUT -> new Execution("limited", "Execution exceeded 5000 ms", "EXECUTION_TIMEOUT", "execution");
            case COMPILATION_TIMEOUT -> new Execution("limited", "Compilation exceeded 10000 ms", "COMPILATION_TIMEOUT", "compile");
            case OUTPUT_LIMIT -> new Execution("limited", "Program output exceeded 1048576 bytes", "OUTPUT_LIMIT", outputPhase);
            case CANCELLED -> new Execution("cancelled", "Run cancelled by user", "RUN_CANCELLED", "execution");
        };
    }

    private final Analysis analysis;
    private final String runId;
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final List<Integer> observableSeqs = new ArrayList<>();
    private final Map<String, List<Map<String, Object>>> arrays = new HashMap<>();
    private final Set<String> knownObjects = new HashSet<>();
    private final Map<String, String> pendingStrings = new HashMap<>();
    // Scope instances (static id -> instance id), outer to inner, and live bindings.
    private final List<String[]> scopes = new ArrayList<>();
    private final Map<String, Map<String, Object>> liveBindings = new HashMap<>(); // declarationId -> binding
    private final Map<Cond, Boolean> conditionValues = new HashMap<>();
    private Map<String, Object> initialState;
    private int nextScope = 1, nextBinding = 1;

    private TraceBuilder(Analysis analysis, String runId) { this.analysis = analysis; this.runId = runId; }

    // ---- envelopes ----------------------------------------------------------------

    /** Compile errors: nothing ran. */
    static String compileError(Analysis analysis, String runId) {
        return envelope(analysis.source(), runId, "compile_error", "Compilation failed.", "unavailable",
                "Source did not compile; nothing was executed.", analysis.compileErrors(), null, List.of(), List.of());
    }

    /** Stopped before a plan existed (compile-phase timeout, output limit or cancellation). */
    static String notRun(String source, String runId, Tracer.Outcome outcome) {
        Execution e = execution(outcome, "compile");
        List<Diagnostic> diagnostics = e.diagnostic() == null ? List.of() : List.of(e.diagnostic());
        return envelope(source, runId, e.status(), e.reason(), "unavailable",
                "The program did not run to a traceable point.", diagnostics, null, List.of(), List.of());
    }

    /** Output-only run of the original program: execution status, no capture. */
    static String outputOnly(Analysis analysis, String runId, Tracer.Outcome outcome) {
        Execution e = execution(outcome, "execution");
        List<Diagnostic> diagnostics = new ArrayList<>(analysis.unsupported());
        if (e.diagnostic() != null) diagnostics.add(e.diagnostic());
        return envelope(analysis.source(), runId, e.status(), e.reason(), "unavailable",
                "Visualization does not cover this program yet; output only.", diagnostics, null, List.of(), List.of());
    }

    /** Traced run: rebuild trace-v1 from raw records. */
    static String traced(Analysis analysis, String runId, List<String> raw, Tracer.Outcome outcome) {
        return new TraceBuilder(analysis, runId).build(raw, outcome);
    }

    private String build(List<String> raw, Tracer.Outcome outcome) {
        String terminal = null;
        String stopReason = null;
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Object> failure = null;
        try {
            if (raw.isEmpty() || !raw.get(0).startsWith("M ")) throw new Inconsistent("missing main entry record");
            entry(raw.get(0));
            for (int i = 1; i < raw.size() && terminal == null; i++) {
                String line = raw.get(i);
                if (line.equals("R")) terminal = "R";
                else if (line.equals("L")) terminal = "L";
                else if (line.startsWith("S")) stringSight(line);
                else if (line.startsWith("x")) { failure = exception(line); terminal = "x"; }
                else operation(line);
            }
        } catch (Inconsistent | RuntimeException e) {
            stopReason = "Recording stopped being consistent with reconstructed state: " + e.getMessage();
            diagnostics.add(new Diagnostic("capture", "CAPTURE_INCONSISTENT", stopReason, null, null));
        }
        Execution stop = execution(outcome, "execution");
        if (stop.diagnostic() != null) diagnostics.add(0, stop.diagnostic());
        if (initialState == null) {
            return envelope(analysis.source(), runId, stop.status(), stop.reason(), "unavailable",
                    "No trustworthy initial state was recorded.", diagnostics, null, List.of(), List.of());
        }

        boolean exited = outcome.cause() == Tracer.Cause.EXIT;
        String executionStatus, executionReason, captureStatus, captureReason;
        if (exited && stopReason == null && "R".equals(terminal) && outcome.exitCode() == 0) {
            addBookkeeping("FRAME_END", ordered("frameId", FRAME, "reason", "main_return"));
            executionStatus = "completed"; executionReason = "Normal return from main.";
            captureStatus = "complete"; captureReason = "All covered operations were recorded.";
        } else if (exited && stopReason == null && "x".equals(terminal) && outcome.exitCode() != 0) {
            addBookkeeping("FRAME_END", ordered("frameId", FRAME, "reason", "unwind"));
            executionStatus = "failed"; executionReason = "Uncaught " + failure.get("exceptionType") + ".";
            captureStatus = "complete"; captureReason = "All covered operations were recorded through the failure.";
        } else if (exited && "L".equals(terminal)) {
            executionStatus = "limited"; executionReason = "Raw trace data limit reached; execution was stopped.";
            captureStatus = "partial"; captureReason = "Capture stops at the last safe operation before the limit.";
            diagnostics.add(new Diagnostic("capture", "TRACE_LIMIT", "Raw trace data exceeded the recorder limit; execution was halted.", null, null));
        } else {
            // A latched stop cause always wins: never complete capture after a timeout, limit or cancellation.
            executionStatus = stop.status(); executionReason = stop.reason();
            captureStatus = "partial";
            captureReason = stopReason != null ? stopReason
                    : exited ? "Recording ended without a terminal record."
                    : "Capture stops at the last safe operation recorded before the run was stopped.";
        }

        // Builder-side size limits (trace-data quota and per-record bound).
        int limitAt = sizeLimitIndex();
        if (limitAt >= 0) {
            truncateTo(limitAt);
            executionStatus = "limited"; executionReason = "Trace data limit reached.";
            captureStatus = "partial"; captureReason = "Capture stops at the last safe operation within the trace limit.";
            diagnostics.add(new Diagnostic("capture", "TRACE_LIMIT", "Trace exceeded "
                    + TRACE_LIMIT + " bytes or a record exceeded " + RECORD_LIMIT + " bytes.", null, null));
        }

        List<Integer> stepEnds = new ArrayList<>(observableSeqs);
        if (captureStatus.equals("partial")) {
            int last = observableSeqs.isEmpty() ? 0 : observableSeqs.get(observableSeqs.size() - 1);
            truncateTo(last); // drop unsafe trailing bookkeeping
            stepEnds = new ArrayList<>(observableSeqs);
        } else if (!stepEnds.isEmpty()) {
            stepEnds.set(stepEnds.size() - 1, events.size()); // final cursor applies terminal bookkeeping
        }
        return envelope(analysis.source(), runId, executionStatus, executionReason, captureStatus, captureReason,
                diagnostics, initialState, events, stepEnds);
    }

    // ---- records ------------------------------------------------------------------

    private void entry(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length != 3 || !t[2].equals("0")) throw new Inconsistent("main arguments are not supported");
        String args = object(t[1]);
        knownObjects.add(args);
        arrays.put(args, new ArrayList<>());
        String root = "s" + nextScope++;
        scopes.add(new String[]{analysis.rootScope(), root});
        Map<String, Object> argsBinding = binding("Main.main:args", "args", "java.lang.String[]", root, ref(args));
        initialState = ordered(
                "objects", List.of(ordered("objectId", args, "kind", "array", "elementType", "java.lang.String", "elements", List.of())),
                "frames", List.of(ordered("frameId", FRAME, "methodId", MAIN_METHOD, "callerFrameId", null)),
                "scopes", List.of(ordered("scopeId", root, "frameId", FRAME, "parentScopeId", null)),
                "bindings", List.of(argsBinding));
    }

    private void stringSight(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length != 2 || !t[1].startsWith("h")) throw new Inconsistent("malformed string record");
        pendingStrings.put("o" + t[0].substring(1), t[1]);
    }

    private Map<String, Object> exception(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length != 3) throw new Inconsistent("malformed failure record");
        Site site = site(t[0].substring(1));
        syncScopes(site);
        Map<String, Object> data = ordered("exceptionType", t[1], "message", unhex(t[2]));
        addObservable(site, "EXCEPTION", data);
        return data;
    }

    private void operation(String line) throws Inconsistent {
        String[] t = line.split(" ");
        Site site = site(t[0]);
        syncScopes(site);
        switch (site.kind) {
            case DECLARE -> {
                expect(t, 2);
                if (liveBindings.containsKey(site.declarationId)) reenterInnermost(site);
                String scope = scopes.get(scopes.size() - 1)[1];
                Map<String, Object> binding = binding(site.declarationId, site.name, site.javaType, scope, typed(site.javaType, t[1]));
                liveBindings.put(site.declarationId, binding);
                addObservable(site, "DECLARE", ordered("binding", copy(binding)));
            }
            case ASSIGN -> {
                expect(t, 2);
                Map<String, Object> binding = live(site);
                Object previous = binding.get("value");
                Object value = typed(site.javaType, t[1]);
                binding.put("value", value);
                addObservable(site, "ASSIGN", ordered("bindingId", binding.get("bindingId"), "previous", previous, "value", value));
            }
            case UPDATE -> {
                expect(t, 2);
                Map<String, Object> binding = live(site);
                int previous = intValue(binding.get("value"));
                int value = Integer.parseInt(t[1]);
                if (value != previous + (site.operator.equals("++") ? 1 : -1)) throw new Inconsistent("update value");
                binding.put("value", intTyped(value));
                addObservable(site, "UPDATE", ordered("bindingId", binding.get("bindingId"), "previous", intTyped(previous),
                        "value", intTyped(value), "result", intTyped(site.position.equals("prefix") ? value : previous),
                        "operator", site.operator, "position", site.position));
            }
            case ALLOCATE -> {
                if (t.length < 3) throw new Inconsistent("allocation record");
                String id = object(t[1]);
                if (knownObjects.contains(id)) throw new Inconsistent("reused object identity");
                int length = Integer.parseInt(t[2]);
                List<Map<String, Object>> elements = new ArrayList<>(length);
                if (t.length == 3) for (int i = 0; i < length; i++) elements.add(intTyped(0));
                else if (t.length == 3 + length) for (int i = 0; i < length; i++) elements.add(intTyped(Integer.parseInt(t[3 + i])));
                else throw new Inconsistent("allocation contents");
                knownObjects.add(id);
                arrays.put(id, elements);
                addObservable(site, "ALLOCATE", ordered("object",
                        ordered("objectId", id, "kind", "array", "elementType", "int", "elements", new ArrayList<>(elements))));
            }
            case ARRAY_READ -> {
                expect(t, 4);
                List<Map<String, Object>> array = array(t[1]);
                int index = Integer.parseInt(t[2]);
                Map<String, Object> value = intTyped(Integer.parseInt(t[3]));
                if (!array.get(index).equals(value)) throw new Inconsistent("array read disagrees with recorded state");
                addObservable(site, "ARRAY_READ", ordered("objectId", object(t[1]), "index", index, "value", value));
            }
            case ARRAY_WRITE -> {
                expect(t, 4);
                List<Map<String, Object>> array = array(t[1]);
                int index = Integer.parseInt(t[2]);
                Map<String, Object> value = intTyped(Integer.parseInt(t[3]));
                Map<String, Object> previous = array.set(index, value);
                addObservable(site, "ARRAY_WRITE", ordered("objectId", object(t[1]), "index", index,
                        "previous", previous, "value", value));
            }
            case ARRAY_LENGTH -> {
                expect(t, 3);
                List<Map<String, Object>> array = array(t[1]);
                int length = Integer.parseInt(t[2]);
                if (array.size() != length) throw new Inconsistent("array length disagrees with recorded state");
                addObservable(site, "ARRAY_LENGTH", ordered("objectId", object(t[1]), "length", length));
            }
            case ARITHMETIC -> {
                List<Object> operands = new ArrayList<>();
                int result;
                if (site.operator.equals("-") && t.length == 3) { // unary minus
                    int v = Integer.parseInt(t[1]);
                    result = Integer.parseInt(t[2]);
                    if (result != -v) throw new Inconsistent("negation result");
                    operands.add(operand(intTyped(v)));
                } else {
                    expect(t, 4);
                    int l = Integer.parseInt(t[1]), r = Integer.parseInt(t[2]);
                    result = Integer.parseInt(t[3]);
                    if (result != arithmetic(site.operator, l, r)) throw new Inconsistent("arithmetic result");
                    operands.add(operand(intTyped(l)));
                    operands.add(operand(intTyped(r)));
                }
                addObservable(site, "EXPRESSION", ordered("operator", site.operator, "operands", operands, "result", intTyped(result)));
            }
            case LEAF -> leaf(site, t);
        }
    }

    private void leaf(Site site, String[] t) throws Inconsistent {
        if (t.length < 3) throw new Inconsistent("condition record");
        boolean jump = t[1].equals("1");
        boolean value = jump == site.jumpMeansTrue;
        Cond leaf = site.leaf;
        Cond root = leaf.root();
        if (root.leftmostLeaf() == leaf) clearCondition(root); // a new evaluation of this condition
        if (site.operator != null) {
            List<Object> operands = new ArrayList<>();
            int op = site.jumpOpcode;
            if (op >= 159 && op <= 164) { // two recorded ints/booleans
                expect(t, 4);
                operands.add(operand(scalar(site.operandType, t[2])));
                operands.add(operand(scalar(site.operandType, t[3])));
            } else if (op >= 153 && op <= 158) { // compared with constant 0/false
                expect(t, 3);
                operands.add(operand(scalar(site.operandType, t[2])));
                operands.add(operand(scalar(site.operandType, "0")));
            } else if (op == 165 || op == 166) {
                expect(t, 4);
                operands.add(operand(reference(t[2])));
                operands.add(operand(reference(t[3])));
            } else {
                expect(t, 3);
                operands.add(operand(reference(t[2])));
                operands.add(operand(ordered("kind", "null")));
            }
            addObservable(site, "EXPRESSION", ordered("operator", site.operator, "operands", operands, "result", bool(value)));
        } else {
            expect(t, 3);
            if (!t[2].equals(value ? "1" : "0")) throw new Inconsistent("boolean condition value");
            if (leaf.parent == null)
                addObservable(site, "EXPRESSION", ordered("operator", "condition", "operands", List.of(operand(bool(value))), "result", bool(value)));
        }
        conditionValues.put(leaf, value);
        for (Cond node = leaf.parent; node != null; node = node.parent) {
            if (conditionValues.containsKey(node)) break;
            Boolean left = conditionValues.get(node.children.get(0));
            Boolean result;
            List<Object> operands = new ArrayList<>();
            switch (node.op) {
                case NOT -> { result = !left; operands.add(operand(bool(left))); }
                case AND, OR -> {
                    if (left == null) return;
                    boolean shortCircuit = node.op == Cond.Op.AND ? !left : left;
                    operands.add(operand(bool(left)));
                    if (shortCircuit) { result = left; operands.add(Map.of("skipped", true)); }
                    else {
                        Boolean right = conditionValues.get(node.children.get(1));
                        if (right == null) return; // right operand still being evaluated
                        result = right;
                        operands.add(operand(bool(right)));
                    }
                }
                default -> throw new Inconsistent("condition structure");
            }
            conditionValues.put(node, result);
            addObservable(node.start, node.end, "EXPRESSION",
                    ordered("operator", node.op == Cond.Op.NOT ? "!" : node.op == Cond.Op.AND ? "&&" : "||",
                            "operands", operands, "result", bool(result)));
        }
    }

    private void clearCondition(Cond node) {
        conditionValues.remove(node);
        for (Cond child : node.children) clearCondition(child);
    }

    // ---- scopes -------------------------------------------------------------------

    /** Make the active scope chain equal the site's static chain. */
    private void syncScopes(Site site) throws Inconsistent {
        List<String> target = site.scopes;
        if (target.isEmpty() || !target.get(0).equals(scopes.get(0)[0])) throw new Inconsistent("scope chain");
        int common = 0;
        while (common < scopes.size() && common < target.size() && scopes.get(common)[0].equals(target.get(common))) common++;
        while (scopes.size() > common) exitScope();
        for (int i = common; i < target.size(); i++) enterScope(target.get(i));
    }

    /** A declaration ran again while its binding lives: its block was re-entered. */
    private void reenterInnermost(Site site) {
        String staticId = scopes.get(scopes.size() - 1)[0];
        exitScope();
        enterScope(staticId);
    }

    private void enterScope(String staticId) {
        String parent = scopes.get(scopes.size() - 1)[1];
        String id = "s" + nextScope++;
        scopes.add(new String[]{staticId, id});
        addBookkeeping("SCOPE_ENTER", Map.of("scope", ordered("scopeId", id, "frameId", FRAME, "parentScopeId", parent)));
    }

    private void exitScope() {
        String[] top = scopes.remove(scopes.size() - 1);
        liveBindings.values().removeIf(b -> b.get("scopeId").equals(top[1]));
        addBookkeeping("SCOPE_EXIT", Map.of("scopeId", top[1]));
    }

    // ---- values -------------------------------------------------------------------

    private Map<String, Object> typed(String javaType, String token) throws Inconsistent {
        return switch (javaType) {
            case "int" -> intTyped(Integer.parseInt(token));
            case "boolean" -> scalar("boolean", token);
            default -> reference(token);
        };
    }

    private Map<String, Object> scalar(String type, String token) throws Inconsistent {
        if (type.equals("boolean")) {
            if (!token.equals("0") && !token.equals("1")) throw new Inconsistent("boolean value");
            return bool(token.equals("1"));
        }
        return intTyped(Integer.parseInt(token));
    }

    private Map<String, Object> reference(String token) throws Inconsistent {
        if (token.equals("n")) return ordered("kind", "null");
        String id = object(token);
        if (!knownObjects.contains(id)) {
            String pending = pendingStrings.remove(id);
            if (pending == null) throw new Inconsistent("reference to an unrecorded object");
            knownObjects.add(id);
            List<Integer> units = new ArrayList<>();
            String hex = pending.substring(1);
            for (int i = 0; i < hex.length(); i += 4) units.add(Integer.parseInt(hex.substring(i, i + 4), 16));
            addBookkeeping("OBJECT_CAPTURE", Map.of("object", ordered("objectId", id, "kind", "string", "utf16", units)));
        }
        return ref(id);
    }

    private static String object(String token) throws Inconsistent {
        if (!token.startsWith("r")) throw new Inconsistent("object reference");
        return "o" + Integer.parseInt(token.substring(1));
    }

    private List<Map<String, Object>> array(String token) throws Inconsistent {
        List<Map<String, Object>> array = arrays.get(object(token));
        if (array == null) throw new Inconsistent("unknown array");
        return array;
    }

    private Map<String, Object> live(Site site) throws Inconsistent {
        Map<String, Object> binding = liveBindings.get(site.declarationId);
        if (binding == null) throw new Inconsistent("assignment to a binding that is not live");
        return binding;
    }

    private static int intValue(Object typed) { return Integer.parseInt((String) ((Map<?, ?>) typed).get("value")); }

    private static int arithmetic(String op, int l, int r) {
        return switch (op) {
            case "+" -> l + r; case "-" -> l - r; case "*" -> l * r; case "/" -> l / r; default -> l % r;
        };
    }

    private static Map<String, Object> intTyped(int v) { return ordered("kind", "int", "value", Integer.toString(v)); }
    private static Map<String, Object> bool(boolean v) { return ordered("kind", "boolean", "value", v); }
    private static Map<String, Object> ref(String id) { return ordered("kind", "ref", "objectId", id); }
    private static Map<String, Object> operand(Map<String, Object> value) { return ordered("value", value); }

    private Map<String, Object> binding(String declarationId, String name, String javaType, String scope, Object value) {
        return ordered("bindingId", "b" + nextBinding++, "declarationId", declarationId, "frameId", FRAME,
                "scopeId", scope, "name", name, "javaType", javaType, "value", value);
    }

    private Site site(String token) throws Inconsistent {
        Site site = analysis.sites().get(Integer.parseInt(token));
        if (site == null) throw new Inconsistent("unknown site " + token);
        return site;
    }

    private static void expect(String[] t, int length) throws Inconsistent {
        if (t.length != length) throw new Inconsistent("record has " + t.length + " fields, expected " + length);
    }

    private static String unhex(String token) throws Inconsistent {
        if (!token.startsWith("h") || (token.length() - 1) % 4 != 0) throw new Inconsistent("hex text");
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < token.length(); i += 4) b.append((char) Integer.parseInt(token.substring(i, i + 4), 16));
        return b.toString();
    }

    private static Map<String, Object> copy(Map<String, Object> map) { return new LinkedHashMap<>(map); }

    // ---- events -------------------------------------------------------------------

    private void addObservable(Site site, String kind, Map<String, Object> data) {
        addObservable(site.start, site.end, kind, data);
    }

    private void addObservable(int start, int end, String kind, Map<String, Object> data) {
        int seq = events.size() + 1;
        events.add(ordered("seq", seq, "step", observableSeqs.size() + 1, "source", ordered("start", start, "end", end),
                "frameId", FRAME, "kind", kind, "data", data));
        observableSeqs.add(seq);
    }

    private void addBookkeeping(String kind, Map<String, Object> data) {
        events.add(ordered("seq", events.size() + 1, "kind", kind, "data", data));
    }

    /** Index (event count) to keep when a size limit is exceeded, or -1 if within limits. */
    private int sizeLimitIndex() {
        long total = Json.write(initialState).getBytes(StandardCharsets.UTF_8).length;
        for (int i = 0; i < events.size(); i++) {
            int bytes = Json.write(events.get(i)).getBytes(StandardCharsets.UTF_8).length;
            total += bytes + 12; // separator plus its stepEnds entry
            if (bytes > RECORD_LIMIT || total > TRACE_LIMIT) return i;
        }
        return -1;
    }

    private void truncateTo(int count) {
        int keep = 0;
        for (int i = Math.min(count, events.size()) - 1; i >= 0; i--) {
            if (events.get(i).containsKey("step")) { keep = i + 1; break; }
        }
        events.subList(keep, events.size()).clear();
        observableSeqs.removeIf(seq -> seq > events.size());
    }

    static String envelope(String source, String runId, String executionStatus, String executionReason,
                           String captureStatus, String captureReason, List<Diagnostic> diagnostics,
                           Map<String, Object> initialState, List<Map<String, Object>> events, List<Integer> stepEnds) {
        List<Object> diagnosticJson = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            Map<String, Object> map = ordered("phase", d.phase(), "code", d.code(), "message", d.message());
            if (d.start() != null) map.put("source", ordered("start", d.start(), "end", d.end()));
            diagnosticJson.add(map);
        }
        return Json.write(ordered(
                "schemaVersion", "1.0.0",
                "runId", runId,
                "source", ordered("file", Analyzer.FILE, "text", source, "sha256", sha256(source)),
                "execution", ordered("status", executionStatus, "reason", executionReason),
                "capture", ordered("status", captureStatus, "reason", captureReason, "safeEventCount", events.size()),
                "diagnostics", diagnosticJson,
                "initialState", initialState,
                "events", events,
                "stepEnds", stepEnds));
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : digest) b.append(String.format("%02x", x));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}
