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
    static final int MAX_FRAMES = 64; // Part 8d: active traced frames, main included (Part 4k budget)

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
    private final Map<String, String> elementTypes = new HashMap<>(); // array id -> trace elementType
    private final Set<String> knownObjects = new HashSet<>();
    private final Map<String, String> pendingStrings = new HashMap<>();
    // Part 8a/8c: array contents seen first in uncaptured code or as elements of a new array (captured
    // when first referenced), and the open uncaptured step whose array re-reads are still arriving.
    private record PendingArray(String elementType, List<String> tokens) { }
    private final Map<String, PendingArray> pendingArrays = new HashMap<>();
    // Part 8e: objects of traced classes (id -> field name -> value), their classes, first sights.
    private record PendingObject(String className, Map<String, String> tokens) { }
    private final Map<String, Map<String, Map<String, Object>>> objects = new HashMap<>();
    private final Map<String, String> objectClasses = new HashMap<>();
    private final Map<String, PendingObject> pendingObjects = new HashMap<>();
    private final Map<String, Site> classes = new HashMap<>(); // javaType -> CLASS site
    private Map<String, Object> openRegion, openRegionEvent;
    private final Set<String> stale = new LinkedHashSet<>();
    private boolean sawRegion;
    // Part 8d: one frame per active invocation, innermost first. Each owns its scope instances
    // (static id -> instance id, outer to inner), live bindings (declarationId -> binding) and
    // condition results, so recursive invocations never share them. The fields below always
    // refer to the innermost frame.
    private record Frame(String id, String methodId, List<String[]> scopes, Map<String, Map<String, Object>> bindings,
                         Map<Cond, Boolean> conditions) {
        Frame(String id, String methodId) { this(id, methodId, new ArrayList<>(), new HashMap<>(), new HashMap<>()); }
    }
    private final Deque<Frame> frames = new ArrayDeque<>();
    private String frameId;
    private List<String[]> scopes;
    private Map<String, Map<String, Object>> liveBindings;
    private Map<Cond, Boolean> conditionValues;
    private Map<String, Object> initialState;
    private int nextScope = 1, nextBinding = 1, nextFrame = 1;
    // A traced entry whose parameter records are still arriving.
    private record PendingCall(Site call, Site method, List<Map<String, Object>> values) { }
    private PendingCall pendingCall;

    private void push(Frame frame) { frames.push(frame); use(frame); }

    private void use(Frame frame) {
        frameId = frame.id(); scopes = frame.scopes(); liveBindings = frame.bindings(); conditionValues = frame.conditions();
    }

    private TraceBuilder(Analysis analysis, String runId) {
        this.analysis = analysis; this.runId = runId;
        for (Site s : analysis.sites().values()) if (s.kind == Plan.Kind.CLASS) classes.put(s.javaType, s);
    }

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
        diagnostics.addAll(analysis.uncaptured());
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
            // No records at all (killed before the first flush) is absence, not inconsistency:
            // initialState stays null and capture is unavailable.
            if (!raw.isEmpty()) {
                if (!raw.get(0).startsWith("M ")) throw new Inconsistent("missing main entry record");
                entry(raw.get(0));
                for (int i = 1; i < raw.size() && terminal == null; i++) {
                    String line = raw.get(i);
                    // Array re-reads and the strings and arrays they reveal belong to the open uncaptured step.
                    if (line.startsWith("A")) { arrayContents(line); continue; }
                    if (line.startsWith("S")) { stringSight(line); continue; }
                    if (line.startsWith("O")) { objectContents(line); continue; }
                    closeRegion();
                    if (line.equals("R")) {
                        if (frames.size() != 1 || pendingCall != null) throw new Inconsistent("main returned inside a call");
                        terminal = "R";
                    }
                    else if (line.equals("L")) terminal = "L";
                    else if (line.equals("D")) terminal = "D";
                    else if (line.equals("T")) terminal = "T";
                    else if (line.startsWith("c")) callEntry(line);
                    else if (line.startsWith("x")) { failure = exception(line); terminal = "x"; }
                    else if (line.startsWith("u")) region(line);
                    else operation(line);
                }
            }
        } catch (Inconsistent | RuntimeException e) {
            stopReason = "Recording stopped being consistent with reconstructed state: " + e.getMessage();
            diagnostics.add(new Diagnostic("capture", "CAPTURE_INCONSISTENT", stopReason, null, null));
        }
        closeRegion(); // a statement still running when recording ended: unread arrays become unknown
        diagnostics.addAll(0, analysis.uncaptured());
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
            captureStatus = "complete";
            captureReason = sawRegion ? "All covered operations were recorded; some statements ran without capture."
                    : "All covered operations were recorded.";
        } else if (exited && stopReason == null && "x".equals(terminal) && outcome.exitCode() != 0) {
            // Uncaught: every active frame ends, innermost first (Part 8d).
            for (Frame frame : frames) addBookkeeping("FRAME_END", ordered("frameId", frame.id(), "reason", "unwind"));
            executionStatus = "failed"; executionReason = "Uncaught " + failure.get("exceptionType") + ".";
            captureStatus = "complete"; captureReason = "All covered operations were recorded through the failure.";
        } else if (exited && "L".equals(terminal)) {
            executionStatus = "limited"; executionReason = "Raw trace data limit reached; execution was stopped.";
            captureStatus = "partial"; captureReason = "Capture stops at the last safe operation before the limit.";
            diagnostics.add(new Diagnostic("capture", "TRACE_LIMIT", "Raw trace data exceeded the recorder limit; execution was halted.", null, null));
        } else if (exited && "T".equals(terminal)) {
            executionStatus = "limited"; executionReason = "Captured object limit reached; execution was stopped.";
            captureStatus = "partial"; captureReason = "Capture stops at the last safe operation before the object limit.";
            diagnostics.add(new Diagnostic("capture", "TRAVERSAL_LIMIT", "More than 1024 captured identities (arrays, strings and objects); execution was halted.", null, null));
        } else if (exited && "D".equals(terminal)) {
            executionStatus = "limited"; executionReason = "Call depth limit reached; execution was stopped.";
            captureStatus = "partial"; captureReason = "Capture stops at the last safe operation before the call that exceeded the limit.";
            diagnostics.add(new Diagnostic("capture", "CALL_DEPTH_LIMIT", "More than " + MAX_FRAMES
                    + " traced frames (main included) would be active; execution was halted.", null, null));
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
        elementTypes.put(args, "java.lang.String");
        String root = "s" + nextScope++;
        push(new Frame(FRAME, MAIN_METHOD));
        scopes.add(new String[]{analysis.rootScope(), root});
        Map<String, Object> argsBinding = binding(FRAME, "Main.main:args", "args", "java.lang.String[]", root, ref(args));
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

    // ---- uncaptured statements (Part 8a) ---------------------------------------------

    /** {@code u<site>}: the statement runs without capture; assigned covered locals become unknown. */
    private void region(String line) throws Inconsistent {
        Site site = site(line.substring(1));
        if (site.kind != Plan.Kind.REGION) throw new Inconsistent("region record for another site");
        declarePending(site);
        syncScopes(site);
        List<Object> unknownBindings = new ArrayList<>();
        for (String declarationId : site.unknownDeclarations) {
            Map<String, Object> binding = liveBindings.get(declarationId);
            if (binding == null) continue; // not declared yet on this path
            binding.put("value", unknown());
            unknownBindings.add(binding.get("bindingId"));
        }
        Map<String, Object> data = ordered("unknownBindings", unknownBindings, "arrays", new ArrayList<>(), "unknownArrays", new ArrayList<>(),
                "objects", new ArrayList<>(), "unknownObjects", new ArrayList<>());
        addObservable(site, "UNCAPTURED", data);
        sawRegion = true;
        if (site.mayWriteArrays) {
            openRegion = data;
            openRegionEvent = events.get(events.size() - 1);
            stale.addAll(arrays.keySet());
            stale.addAll(objects.keySet());
        }
    }

    /** {@code O<id> <class> <n> (<field> <value>)...}: an object's fields, re-read or seen for the first time (Part 8e). */
    @SuppressWarnings("unchecked")
    private void objectContents(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length < 3) throw new Inconsistent("object record");
        String id = "o" + Integer.parseInt(t[0].substring(1));
        String className = classOf(t[1]);
        int n = Integer.parseInt(t[2]);
        if (t.length != 3 + 2 * n || n != classes.get(className).fields.size()) throw new Inconsistent("object fields");
        Map<String, String> tokens = new HashMap<>();
        for (int i = 0; i < n; i++) tokens.put(t[3 + 2 * i], t[4 + 2 * i]);
        if (!knownObjects.contains(id)) {
            if (pendingObjects.putIfAbsent(id, new PendingObject(className, tokens)) != null || pendingArrays.containsKey(id))
                throw new Inconsistent("object seen twice");
            return;
        }
        if (openRegion == null || !stale.remove(id)) throw new Inconsistent("unexpected object re-read");
        if (!className.equals(objectClasses.get(id))) throw new Inconsistent("object class changed");
        Map<String, Map<String, Object>> state = objects.get(id);
        Map<String, Map<String, Object>> values = beforeOpenRegion(() -> fieldValues(className, tokens));
        List<Object> changed = new ArrayList<>();
        for (var entry : values.entrySet()) {
            if (!entry.getValue().equals(state.get(entry.getKey()))) {
                state.put(entry.getKey(), entry.getValue());
                changed.add(ordered("name", entry.getKey(), "value", entry.getValue()));
            }
        }
        if (!changed.isEmpty()) ((List<Object>) openRegion.get("objects")).add(ordered("objectId", id, "fields", changed));
    }

    /** Field values of a class's object from record tokens, in declaration order. */
    private Map<String, Map<String, Object>> fieldValues(String className, Map<String, String> tokens) throws Inconsistent {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Site field : classes.get(className).fields) {
            String token = tokens.get(field.name);
            if (token == null) throw new Inconsistent("missing field " + field.name);
            out.put(field.name, element(field.javaType, token));
        }
        return out;
    }

    /** Trace class name for a JVM binary name of a traced class: Node, or Main.Node for Main$Node. */
    private String classOf(String binary) throws Inconsistent {
        String type = binary.startsWith("Main$") ? "Main." + binary.substring(5) : binary;
        if (!classes.containsKey(type)) throw new Inconsistent("object of an untraced class " + binary);
        return type;
    }

    /** The trace object of a known object, fields in declaration order. */
    private Map<String, Object> objectValue(String id) {
        List<Object> fields = new ArrayList<>();
        Map<String, Map<String, Object>> values = objects.get(id);
        for (Site field : classes.get(objectClasses.get(id)).fields)
            fields.add(ordered("name", field.name, "javaType", field.javaType, "value", values.get(field.name)));
        return ordered("objectId", id, "kind", "object", "className", objectClasses.get(id), "fields", fields);
    }

    /** {@code A<id> <class> <len> <values>}: a re-read after the open statement, or a first sight. */
    @SuppressWarnings("unchecked")
    private void arrayContents(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length < 3) throw new Inconsistent("array contents record");
        String id = "o" + Integer.parseInt(t[0].substring(1));
        String elementType = elementTypeOf(t[1]);
        int length = Integer.parseInt(t[2]);
        if (length < 0 || t.length != 3 + length) throw new Inconsistent("array contents length");
        List<String> tokens = List.of(t).subList(3, t.length);
        if (!knownObjects.contains(id)) {
            if (pendingArrays.putIfAbsent(id, new PendingArray(elementType, tokens)) != null) throw new Inconsistent("array seen twice");
            return;
        }
        if (openRegion == null || !stale.remove(id)) throw new Inconsistent("unexpected array re-read");
        if (!elementType.equals(elementTypes.get(id))) throw new Inconsistent("array type changed");
        List<Map<String, Object>> state = arrays.get(id);
        if (state.size() != length) throw new Inconsistent("array length changed");
        List<Map<String, Object>> elements = beforeOpenRegion(elementType, tokens);
        if (!state.equals(elements)) {
            for (int i = 0; i < length; i++) state.set(i, elements.get(i));
            ((List<Object>) openRegion.get("arrays")).add(ordered("objectId", id, "elements", new ArrayList<>(elements)));
        }
    }

    /**
     * Typed re-read contents. Objects they reveal (created by the uncaptured statement) are
     * captured before that statement's step, so its data only references known objects.
     */
    private interface Capture<T> { T get() throws Inconsistent; }

    private List<Map<String, Object>> beforeOpenRegion(String elementType, List<String> tokens) throws Inconsistent {
        return beforeOpenRegion(() -> elements(elementType, tokens));
    }

    private <T> T beforeOpenRegion(Capture<T> capture) throws Inconsistent {
        int last = events.size() - 1;
        if (events.get(last) != openRegionEvent) throw new Inconsistent("array re-read after another event");
        events.remove(last);
        observableSeqs.remove(observableSeqs.size() - 1);
        try {
            return capture.get();
        } finally {
            openRegionEvent.put("seq", events.size() + 1);
            events.add(openRegionEvent);
            observableSeqs.add(events.size());
        }
    }

    /** Trace elementType for a JVM array class name: {@code [I} int, {@code [[D} double[], {@code [Ljava.lang.String;} String. */
    private String elementTypeOf(String className) throws Inconsistent {
        if (!className.startsWith("[")) throw new Inconsistent("array class");
        String c = className.substring(1);
        String type = switch (c) {
            case "I" -> "int"; case "J" -> "long"; case "F" -> "float"; case "D" -> "double"; case "Z" -> "boolean";
            case "B" -> "byte"; case "C" -> "char"; case "S" -> "short"; case "Ljava.lang.String;" -> "java.lang.String";
            default -> c.startsWith("[") ? elementTypeOf(c) + "[]"
                    : c.startsWith("L") && c.endsWith(";") ? classOf(c.substring(1, c.length() - 1)) : null;
        };
        if (type == null) throw new Inconsistent("array element type " + c);
        return type;
    }

    /** Ends the open statement; arrays it may have changed but that were not re-read become unknown. */
    @SuppressWarnings("unchecked")
    private void closeRegion() {
        if (openRegion == null) return;
        for (String id : stale) {
            if (objects.containsKey(id)) {
                objects.get(id).replaceAll((name, value) -> unknown());
                ((List<Object>) openRegion.get("unknownObjects")).add(id);
                continue;
            }
            List<Map<String, Object>> state = arrays.get(id);
            for (int i = 0; i < state.size(); i++) state.set(i, unknown());
            ((List<Object>) openRegion.get("unknownArrays")).add(id);
        }
        stale.clear();
        openRegion = null;
        openRegionEvent = null;
    }

    private static Map<String, Object> unknown() { return ordered("kind", "unknown"); }

    private Map<String, Object> exception(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length != 3) throw new Inconsistent("malformed failure record");
        Site site = site(t[0].substring(1));
        declarePending(site);
        syncScopes(site);
        Map<String, Object> data = ordered("exceptionType", t[1], "message", unhex(t[2]));
        addObservable(site, "EXCEPTION", data);
        return data;
    }

    /**
     * Declarations without initializer run no code; each is recorded just before the
     * first operation that follows it in its block (Part 8b), unassigned.
     */
    private void declarePending(Site site) throws Inconsistent {
        for (Site d : site.declareBefore) {
            syncScopes(d);
            if (liveBindings.containsKey(d.declarationId)) reenterInnermost(d);
            String scope = scopes.get(scopes.size() - 1)[1];
            Map<String, Object> binding = binding(frameId, d.declarationId, d.name, d.javaType, scope, ordered("kind", "unassigned"));
            liveBindings.put(d.declarationId, binding);
            addObservable(d, "DECLARE", ordered("binding", copy(binding)));
        }
    }

    private String[] pendingUpdate; // Part 8b: arithmetic of a non-int ++/--, before its store

    // ---- calls (Part 8d) ------------------------------------------------------------------

    /** {@code c<method> <callSite>}: a traced invocation starts; its parameter records follow. */
    private void callEntry(String line) throws Inconsistent {
        String[] t = line.substring(1).split(" ");
        expect(t, 2);
        Site method = site(t[0]), call = site(t[1]);
        if (method.kind != Plan.Kind.METHOD || call.kind != Plan.Kind.CALL || call.callee != method)
            throw new Inconsistent("call record");
        if (frames.size() >= MAX_FRAMES) throw new Inconsistent("call depth");
        declarePending(call);
        syncScopes(call);
        pendingCall = new PendingCall(call, method, new ArrayList<>());
        if (method.params.isEmpty()) enterCallee();
    }

    private void parameter(Site site, String[] t) throws Inconsistent {
        List<Map<String, Object>> values = pendingCall.values();
        if (site != pendingCall.method().params.get(values.size())) throw new Inconsistent("parameter record");
        expect(t, 2);
        if (pendingCall.method().constructor && values.isEmpty()) { // Part 8e: the new object, allocated by this call
            String id = object(t[1]);
            if (knownObjects.contains(id) || !pendingObjects.containsKey(id)) throw new Inconsistent("constructor of a known object");
            captureObject(id, pendingCall.call());
        }
        values.add(typed(site.javaType, t[1]));
        if (values.size() == pendingCall.method().params.size()) enterCallee();
    }

    /** One CALL step in the caller's frame, then the callee's frame with its parameter bindings. */
    private void enterCallee() {
        Site method = pendingCall.method();
        String frame = "f" + nextFrame++, scope = "s" + nextScope++;
        Frame callee = new Frame(frame, method.methodId);
        callee.scopes().add(new String[]{method.scopes.get(0), scope});
        List<Object> parameters = new ArrayList<>();
        for (int i = 0; i < method.params.size(); i++) {
            Site p = method.params.get(i);
            Map<String, Object> binding = binding(frame, p.declarationId, p.name, p.javaType, scope, pendingCall.values().get(i));
            callee.bindings().put(p.declarationId, binding);
            parameters.add(copy(binding));
        }
        addObservable(pendingCall.call(), "CALL", ordered(
                "frame", ordered("frameId", frame, "methodId", method.methodId, "callerFrameId", frameId),
                "scope", ordered("scopeId", scope, "frameId", frame, "parentScopeId", null),
                "parameters", parameters));
        pendingCall = null;
        push(callee);
    }

    private void operation(String line) throws Inconsistent {
        String[] t = line.split(" ");
        Site site = site(t[0]);
        if (pendingCall != null) { parameter(site, t); return; }
        if (pendingUpdate == null) declarePending(site);
        syncScopes(site);
        switch (site.kind) {
            case DECLARE -> {
                expect(t, 2);
                if (liveBindings.containsKey(site.declarationId)) reenterInnermost(site);
                String scope = scopes.get(scopes.size() - 1)[1];
                Map<String, Object> binding = binding(frameId, site.declarationId, site.name, site.javaType, scope, typed(site.javaType, t[1]));
                liveBindings.put(site.declarationId, binding);
                addObservable(site, "DECLARE", ordered("binding", copy(binding)));
            }
            case ASSIGN -> {
                expect(t, 2);
                Map<String, Object> binding = live(site);
                Object previous = binding.get("value");
                Object value = typed(site.javaType, t[1]);
                if (site.operand != null) { // x += c compiled to iinc: the addition, then the assignment
                    int now = Integer.parseInt(t[1]), before = now - site.delta;
                    if (!"unknown".equals(((Map<?, ?>) previous).get("kind")) && !previous.equals(intTyped(before)))
                        throw new Inconsistent("compound assignment previous value");
                    addObservable(site, "EXPRESSION", ordered("operator", site.operator, "operands",
                            List.of(operand(intTyped(before)), operand(intTyped(Integer.parseInt(site.operand)))), "result", intTyped(now)));
                }
                binding.put("value", value);
                addObservable(site, "ASSIGN", ordered("bindingId", binding.get("bindingId"), "previous", previous, "value", value));
            }
            case UPDATE -> {
                if (site.viaArith) { updateViaArithmetic(site, t); return; }
                expect(t, 2);
                Map<String, Object> binding = live(site);
                int value = Integer.parseInt(t[1]);
                int delta = site.operator.equals("++") ? 1 : -1;
                Object stored = binding.get("value");
                boolean known = !"unknown".equals(((Map<?, ?>) stored).get("kind"));
                if (known && value != intValue(stored) + delta) throw new Inconsistent("update value");
                binding.put("value", intTyped(value));
                // After uncaptured code the old value is unknown, but iinc makes the postfix result exact.
                addObservable(site, "UPDATE", ordered("bindingId", binding.get("bindingId"), "previous", known ? intTyped(value - delta) : unknown(),
                        "value", intTyped(value), "result", intTyped(site.position.equals("prefix") ? value : value - delta),
                        "operator", site.operator, "position", site.position));
            }
            case ALLOCATE -> {
                if (t.length < 3) throw new Inconsistent("allocation record");
                String id = object(t[1]);
                if (knownObjects.contains(id) || pendingArrays.containsKey(id)) throw new Inconsistent("reused object identity");
                String elementType = Analyzer.component(site.javaType);
                int length = Integer.parseInt(t[2]);
                if (length < 0) throw new Inconsistent("allocation length");
                List<Map<String, Object>> elements = new ArrayList<>(length);
                if (t.length == 3) for (int i = 0; i < length; i++) elements.add(defaultValue(elementType));
                else if (t.length == 3 + length) elements = elements(elementType, List.of(t).subList(3, t.length)); // inner arrays first
                else throw new Inconsistent("allocation contents");
                knownObjects.add(id);
                arrays.put(id, elements);
                elementTypes.put(id, elementType);
                addObservable(site, "ALLOCATE", ordered("object",
                        ordered("objectId", id, "kind", "array", "elementType", elementType, "elements", new ArrayList<>(elements))));
            }
            case ARRAY_READ -> {
                expect(t, 4);
                List<Map<String, Object>> array = array(t[1], site);
                int index = Integer.parseInt(t[2]);
                Map<String, Object> value = element(Analyzer.component(site.javaType), t[3]);
                if (!array.get(index).equals(value)) throw new Inconsistent("array read disagrees with recorded state");
                addObservable(site, "ARRAY_READ", ordered("objectId", object(t[1]), "index", index, "value", value));
            }
            case ARRAY_WRITE -> {
                expect(t, 4);
                List<Map<String, Object>> array = array(t[1], site);
                int index = Integer.parseInt(t[2]);
                Map<String, Object> value = element(Analyzer.component(site.javaType), t[3]);
                Map<String, Object> previous = array.set(index, value);
                addObservable(site, "ARRAY_WRITE", ordered("objectId", object(t[1]), "index", index,
                        "previous", previous, "value", value));
            }
            case FIELD_READ -> {
                expect(t, 3);
                Map<String, Map<String, Object>> state = object(t[1], site);
                Map<String, Object> value = element(site.javaType, t[2]);
                if (!state.get(site.name).equals(value)) throw new Inconsistent("field read disagrees with recorded state");
                addObservable(site, "FIELD_READ", ordered("objectId", object(t[1]), "field", site.name, "value", value));
            }
            case FIELD_WRITE -> {
                expect(t, 3);
                Map<String, Map<String, Object>> state = object(t[1], site);
                Map<String, Object> value = element(site.javaType, t[2]);
                Map<String, Object> previous = state.put(site.name, value);
                addObservable(site, "FIELD_WRITE", ordered("objectId", object(t[1]), "field", site.name, "previous", previous, "value", value));
            }
            case SWITCH -> {
                expect(t, 2);
                Map<String, Object> value = scalar(site.javaType, t[1]);
                int selector = Integer.parseInt(t[1]);
                Plan.Case chosen = null;
                String matched = "none";
                for (Plan.Case c : site.cases) if (chosen == null && c.values().contains(selector)) { chosen = c; matched = "case"; }
                for (Plan.Case c : site.cases) if (chosen == null && c.isDefault()) { chosen = c; matched = "default"; }
                addObservable(chosen == null ? site.start : chosen.start(), chosen == null ? site.end : chosen.end(), "SWITCH",
                        ordered("value", value, "matched", matched));
            }
            case ARRAY_LENGTH -> {
                expect(t, 3);
                List<Map<String, Object>> array = array(t[1], site);
                int length = Integer.parseInt(t[2]);
                if (array.size() != length) throw new Inconsistent("array length disagrees with recorded state");
                addObservable(site, "ARRAY_LENGTH", ordered("objectId", object(t[1]), "length", length));
            }
            case ARITHMETIC -> {
                String type = site.javaType;
                List<Object> operands = new ArrayList<>();
                String result;
                if (site.operator.equals("-") && t.length == 3) { // unary minus
                    result = t[2];
                    if (!Values.same(type, result, Values.negate(type, t[1]))) throw new Inconsistent("negation result");
                    operands.add(operand(Values.typed(type, t[1])));
                } else {
                    expect(t, 4);
                    result = t[3];
                    String expected = Values.compute(site.operator, type, t[1], t[2]);
                    if (expected == null || !Values.same(type, result, expected)) throw new Inconsistent("arithmetic result");
                    operands.add(operand(Values.typed(type, t[1])));
                    if (site.operator.equals("~")) { // compiled as x ^ -1
                        if (!Values.same(type, t[2], "-1")) throw new Inconsistent("complement operand");
                    } else {
                        boolean shift = site.operator.startsWith("<") || site.operator.startsWith(">");
                        operands.add(operand(Values.typed(shift ? "int" : type, t[2])));
                    }
                }
                addObservable(site, "EXPRESSION", ordered("operator", site.operator, "operands", operands,
                        "result", Values.typed(type, result)));
            }
            case CAST -> {
                expect(t, 3);
                if (!Values.same(Analyzer.stack(site.javaType), t[2], Values.cast(site.fromType, t[1], site.javaType)))
                    throw new Inconsistent("cast result");
                addObservable(site, "EXPRESSION", ordered("operator", site.operator,
                        "operands", List.of(operand(Values.typed(site.fromType, t[1]))), "result", Values.typed(site.javaType, t[2])));
            }
            case LEAF -> leaf(site, t);
            case RETURN -> {
                if (frames.size() < 2) throw new Inconsistent("return from main");
                Object result = null;
                if (site.javaType.equals("void")) expect(t, 1);
                else { expect(t, 2); result = typed(site.javaType, t[1]); }
                addObservable(site, "RETURN", ordered("result", result, "implicit", site.implicit));
                frames.pop();
                use(frames.peek());
            }
            case REGION -> throw new Inconsistent("operation record for an uncaptured statement");
            case METHOD, PARAM, CALL, CLASS, FIELD -> throw new Inconsistent("record for a declaration site");
        }
    }

    /** ++/-- on a byte, short, char, long, float or double local: arithmetic record, then store record. */
    private void updateViaArithmetic(Site site, String[] t) throws Inconsistent {
        String stackType = Analyzer.stack(site.javaType);
        if (pendingUpdate == null) {
            expect(t, 4);
            String expected = Values.compute(site.operator.equals("++") ? "+" : "-", stackType, t[1], t[2]);
            if (expected == null || !Values.same(stackType, t[3], expected)) throw new Inconsistent("update arithmetic");
            pendingUpdate = t;
            return;
        }
        String[] arithmetic = pendingUpdate;
        pendingUpdate = null;
        expect(t, 2);
        if (!Values.same(stackType, t[1], Values.cast(stackType, arithmetic[3], site.javaType))) throw new Inconsistent("update value");
        Map<String, Object> binding = live(site);
        Map<String, Object> previous = Values.typed(site.javaType, arithmetic[1]);
        Object stored = binding.get("value");
        if (!"unknown".equals(((Map<?, ?>) stored).get("kind")) && !stored.equals(previous)) throw new Inconsistent("update previous value");
        Map<String, Object> value = Values.typed(site.javaType, t[1]);
        binding.put("value", value);
        addObservable(site, "UPDATE", ordered("bindingId", binding.get("bindingId"), "previous", stored,
                "value", value, "result", site.position.equals("prefix") ? value : previous,
                "operator", site.operator, "position", site.position));
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
            if (List.of("long", "float", "double").contains(site.operandType)) { // recorded at lcmp/fcmp/dcmp
                expect(t, 4);
                operands.add(operand(Values.typed(site.operandType, t[2])));
                operands.add(operand(Values.typed(site.operandType, t[3])));
            } else if (op >= 159 && op <= 164) { // two recorded ints/booleans
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
        addBookkeeping("SCOPE_ENTER", Map.of("scope", ordered("scopeId", id, "frameId", frameId, "parentScopeId", parent)));
    }

    private void exitScope() {
        String[] top = scopes.remove(scopes.size() - 1);
        liveBindings.values().removeIf(b -> b.get("scopeId").equals(top[1]));
        addBookkeeping("SCOPE_EXIT", Map.of("scopeId", top[1]));
    }

    // ---- values -------------------------------------------------------------------

    private Map<String, Object> typed(String javaType, String token) throws Inconsistent { return element(javaType, token); }

    /** A recorded value of a static type: a scalar, or a reference to a captured object of that type. */
    private Map<String, Object> element(String type, String token) throws Inconsistent {
        switch (type) {
            case "byte", "short", "char", "int", "long", "float", "double", "boolean" -> { return scalar(type, token); }
            default -> {
                Map<String, Object> value = reference(token);
                if (value.get("objectId") instanceof String id) {
                    boolean fits = type.endsWith("[]") ? Analyzer.component(type).equals(elementTypes.get(id))
                            : type.equals("java.lang.String") ? !arrays.containsKey(id) && !objectClasses.containsKey(id)
                            : type.equals(objectClasses.get(id));
                    if (!fits) throw new Inconsistent("reference of another type");
                }
                return value;
            }
        }
    }

    private List<Map<String, Object>> elements(String elementType, List<String> tokens) throws Inconsistent {
        List<Map<String, Object>> out = new ArrayList<>(tokens.size());
        for (String token : tokens) out.add(element(elementType, token));
        return out;
    }

    private static Map<String, Object> defaultValue(String type) {
        return switch (type) {
            case "float" -> Values.typed(type, "00000000");
            case "double" -> Values.typed(type, "0000000000000000");
            case "byte", "short", "char", "int", "long", "boolean" -> Values.typed(type, "0");
            default -> ordered("kind", "null");
        };
    }

    private Map<String, Object> scalar(String type, String token) throws Inconsistent {
        try { return Values.typed(type, token); }
        catch (IllegalArgumentException malformed) { throw new Inconsistent(type + " value"); }
    }

    private Map<String, Object> reference(String token) throws Inconsistent {
        if (token.equals("n")) return ordered("kind", "null");
        String id = object(token);
        if (!knownObjects.contains(id) && pendingArrays.containsKey(id)) captureArray(id);
        if (!knownObjects.contains(id) && pendingObjects.containsKey(id)) captureObject(id, null);
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

    /**
     * An array created by uncaptured code or inside a new array, seen for the first time by
     * covered code. The objects it references are captured first.
     */
    private void captureArray(String id) throws Inconsistent {
        PendingArray pending = pendingArrays.remove(id);
        knownObjects.add(id); // known first: a reference back to it (a cycle) resolves within this step
        elementTypes.put(id, pending.elementType());
        List<Map<String, Object>> elements = elements(pending.elementType(), pending.tokens());
        arrays.put(id, elements);
        addBookkeeping("OBJECT_CAPTURE", Map.of("object", ordered("objectId", id, "kind", "array",
                "elementType", pending.elementType(), "elements", new ArrayList<>(elements))));
    }

    /**
     * An object first seen by covered code (Part 8e), with everything newly reachable from it, as
     * OBJECT_CAPTURE bookkeeping; or, for a traced constructor ({@code allocation} is its call
     * site), the ALLOCATE step of the new object, whose fields still hold their defaults.
     */
    private void captureObject(String id, Site allocation) throws Inconsistent {
        PendingObject pending = pendingObjects.remove(id);
        knownObjects.add(id); // known first: cycles resolve within this step
        objectClasses.put(id, pending.className());
        objects.put(id, new HashMap<>());
        objects.put(id, fieldValues(pending.className(), pending.tokens()));
        if (allocation == null) addBookkeeping("OBJECT_CAPTURE", Map.of("object", objectValue(id)));
        else addObservable(allocation, "ALLOCATE", ordered("object", objectValue(id)));
    }

    private Map<String, Map<String, Object>> object(String token, Site site) throws Inconsistent {
        String id = object(token);
        if (!knownObjects.contains(id) && pendingObjects.containsKey(id)) captureObject(id, null);
        Map<String, Map<String, Object>> state = objects.get(id);
        if (state == null || !site.owner.equals(objectClasses.get(id))) throw new Inconsistent("field of an unknown object");
        if (!state.containsKey(site.name)) throw new Inconsistent("unknown field " + site.name);
        return state;
    }

    private List<Map<String, Object>> array(String token, Site site) throws Inconsistent {
        String id = object(token);
        if (!knownObjects.contains(id) && pendingArrays.containsKey(id)) captureArray(id);
        List<Map<String, Object>> array = arrays.get(id);
        if (array == null) throw new Inconsistent("unknown array");
        if (site.javaType != null && !Analyzer.component(site.javaType).equals(elementTypes.get(id)))
            throw new Inconsistent("array of another type");
        return array;
    }

    private Map<String, Object> live(Site site) throws Inconsistent {
        Map<String, Object> binding = liveBindings.get(site.declarationId);
        if (binding == null) throw new Inconsistent("assignment to a binding that is not live");
        return binding;
    }

    private static int intValue(Object typed) { return Integer.parseInt((String) ((Map<?, ?>) typed).get("value")); }


    private static Map<String, Object> intTyped(int v) { return ordered("kind", "int", "value", Integer.toString(v)); }
    private static Map<String, Object> bool(boolean v) { return ordered("kind", "boolean", "value", v); }
    private static Map<String, Object> ref(String id) { return ordered("kind", "ref", "objectId", id); }
    private static Map<String, Object> operand(Map<String, Object> value) { return ordered("value", value); }

    private Map<String, Object> binding(String frame, String declarationId, String name, String javaType, String scope, Object value) {
        return ordered("bindingId", "b" + nextBinding++, "declarationId", declarationId, "frameId", frame,
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
                "frameId", frameId, "kind", kind, "data", data));
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
                "schemaVersion", "1.4.0",
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
