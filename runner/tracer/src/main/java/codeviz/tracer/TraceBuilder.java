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
            case INTERACTIVE_TIMEOUT -> new Execution("limited", "Execution exceeded 120000 ms", "EXECUTION_TIMEOUT", "execution");
            case COMPILATION_TIMEOUT -> new Execution("limited", "Compilation exceeded 10000 ms", "COMPILATION_TIMEOUT", "compile");
            case OUTPUT_LIMIT -> new Execution("limited", "Program output exceeded 1048576 bytes", "OUTPUT_LIMIT", outputPhase);
            case CANCELLED -> new Execution("cancelled", "Run cancelled by user", "RUN_CANCELLED", "execution");
            case INPUT_DISCONNECTED -> new Execution("cancelled", "Input controller disconnected", "INPUT_DISCONNECTED", "execution");
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
    // Part 8f: mirrors of library objects (id -> mirror and back), string texts, first sights.
    private final Map<String, Object> mirrors = new HashMap<>();
    private final Map<Object, String> mirrorIds = new IdentityHashMap<>();
    private final Map<String, String> stringTexts = new HashMap<>();
    private record PendingLibrary(String className, List<String> tokens) { }
    private final Map<String, PendingLibrary> pendingLibrary = new HashMap<>();
    private static final Map<String, String> BOXES = Map.of("java.lang.Integer", "int", "java.lang.Long", "long",
            "java.lang.Double", "double", "java.lang.Float", "float", "java.lang.Short", "short", "java.lang.Byte", "byte",
            "java.lang.Character", "char", "java.lang.Boolean", "boolean");
    private Map<String, Object> openRegion, openRegionEvent;
    private final Set<String> stale = new LinkedHashSet<>();
    private boolean sawRegion;
    // Part 8d: one frame per active invocation, innermost first. Each owns its scope instances
    // (static id -> instance id, outer to inner), live bindings (declarationId -> binding) and
    // condition results, so recursive invocations never share them. The fields below always
    // refer to the innermost frame.
    private record Frame(String id, String methodId, List<String[]> scopes, Map<String, Map<String, Object>> bindings,
                         Map<Cond, Boolean> conditions, Deque<Concat> concats) {
        Frame(String id, String methodId) { this(id, methodId, new ArrayList<>(), new HashMap<>(), new HashMap<>(), new ArrayDeque<>()); }
    }
    /** A string concatenation whose operands are being appended (Part 8f). */
    private record Concat(Site site, List<String> tokens) { }
    private final Deque<Frame> frames = new ArrayDeque<>();
    private String frameId;
    private List<String[]> scopes;
    private Map<String, Map<String, Object>> liveBindings;
    private Map<Cond, Boolean> conditionValues;
    private Map<String, Object> initialState;
    private int nextScope = 1, nextBinding = 1, nextFrame = 1;
    // A traced entry whose parameter records are still arriving.
    // Part 10d: via is the library call that runs the callee (a lambda body or Main::helper), call is then null.
    private record PendingCall(Site call, Site method, List<Map<String, Object>> values, Site via) {
        PendingCall(Site call, Site method, List<Map<String, Object>> values) { this(call, method, values, null); }
    }
    private PendingCall pendingCall;

    private void push(Frame frame) { frames.push(frame); use(frame); }

    private void use(Frame frame) {
        frameId = frame.id(); scopes = frame.scopes(); liveBindings = frame.bindings(); conditionValues = frame.conditions();
    }

    private final byte[] input; // Part 8g: bytes delivered to standard input, replayed by a Scanner or (8i) BufferedReader mirror
    private boolean inputCreated;

    // Part 8h: exception objects (id -> class binary name, message or null), first sights not yet captured,
    // superclasses of the source's Throwable classes, the exception in flight (thrown and not yet caught)
    // and whether the last statement ran without capture, so it may have thrown one unseen.
    private record ExceptionObject(String className, String message) { }
    private final Map<String, ExceptionObject> exceptions = new HashMap<>();
    private final Map<String, ExceptionObject> pendingExceptions = new HashMap<>();
    private final Map<String, String> exceptionSupers = new HashMap<>();
    private String inFlight;
    private boolean regionThrow, finallyRunning;

    private TraceBuilder(Analysis analysis, String runId, byte[] input) {
        this.analysis = analysis; this.runId = runId; this.input = input.clone();
        for (Site s : analysis.sites().values()) {
            if (s.kind == Plan.Kind.CLASS) classes.put(s.javaType, s);
            if (s.kind == Plan.Kind.EXCEPTION_CLASS) exceptionSupers.put(s.name, s.owner);
        }
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
    static String traced(Analysis analysis, String runId, List<String> raw, Tracer.Outcome outcome, byte[] input) {
        return new TraceBuilder(analysis, runId, input).build(raw, outcome);
    }

    private String build(List<String> raw, Tracer.Outcome outcome) {
        String terminal = null;
        String stopReason = null;
        List<Diagnostic> diagnostics = new ArrayList<>();
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
                    if (line.startsWith("C") || line.startsWith("M") || line.startsWith("B")) { libraryContents(line); continue; }
                    if (line.startsWith("E")) { exceptionSight(line); continue; }
                    if (line.startsWith("Y")) { entryContents(line); continue; } // Part 10d
                    closeRegion();
                    if (line.equals("R")) {
                        if (frames.size() != 1 || pendingCall != null) throw new Inconsistent("main returned inside a call");
                        if (inFlight != null) throw new Inconsistent("main returned with an exception in flight");
                        terminal = "R";
                    }
                    else if (line.equals("L")) terminal = "L";
                    else if (line.equals("D")) terminal = "D";
                    else if (line.equals("T")) terminal = "T";
                    else if (line.startsWith("c")) { regionThrow = false; expectNotPropagating(); callEntry(line); }
                    else if (line.startsWith("x")) exception(line);
                    else if (line.equals("w")) unwind();
                    else if (line.startsWith("u")) { expectNotPropagating(); region(line); }
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
        } else if (exited && stopReason == null && terminal == null && inFlight != null && outcome.exitCode() != 0) {
            // Uncaught: every frame still active ends, innermost first (Part 8d); helpers it left already ended (8h).
            for (Frame frame : frames) addBookkeeping("FRAME_END", ordered("frameId", frame.id(), "reason", "unwind"));
            executionStatus = "failed"; executionReason = "Uncaught " + exceptions.get(inFlight).className() + ".";
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
                "objects", new ArrayList<>(), "unknownObjects", new ArrayList<>(), "collections", new ArrayList<>(), "unknownCollections", new ArrayList<>());
        addObservable(site, "UNCAPTURED", data);
        sawRegion = true;
        regionThrow = true; // until the next recorded operation, a catch or finally may receive what it threw (Part 8h)
        if (site.mayWriteArrays) {
            openRegion = data;
            openRegionEvent = events.get(events.size() - 1);
            stale.addAll(arrays.keySet());
            stale.addAll(objects.keySet());
            stale.addAll(mirrors.keySet());
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
            if (mirrors.containsKey(id)) { // a library object no longer known: no further calls on it are expected
                ((List<Object>) openRegion.get("unknownCollections")).add(id);
                continue;
            }
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

    // ---- exceptions (Part 8h) ---------------------------------------------------------------

    /** {@code x<site> <class> <message> <ref>}: a covered operation failed or a throw statement ran; the exception is in flight. */
    private void exception(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length != 4) throw new Inconsistent("malformed failure record");
        Site site = site(t[0].substring(1));
        if (site.kind == Plan.Kind.REGION || site.kind == Plan.Kind.CATCH || site.kind == Plan.Kind.FINALLY) throw new Inconsistent("failure at this site");
        // Part 10d: a library call that failed (perhaps in a callback) ends its callback log unreplayed.
        if (!callbackLogs.isEmpty() && callbackLogs.peek().via() == site && callbackLogs.peek().callerFrame().equals(frameId)) callbackLogs.pop();
        regionThrow = false;
        declarePending(site);
        syncScopes(site);
        String id = exceptionReference(t[3]);
        ExceptionObject e = exceptions.get(id);
        if (!e.className().equals(t[1]) || !Objects.equals(e.message(), messageOf(t[2]))) throw new Inconsistent("failure record disagrees with its exception");
        addObservable(site, "EXCEPTION", ordered("exceptionType", e.className(), "message", e.message() == null ? "" : e.message(), "objectId", id));
        inFlight = id;
        finallyRunning = false;
    }

    /** {@code E<id> <class> <message>}: an exception seen for the first time. */
    private void exceptionSight(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length != 3) throw new Inconsistent("exception record");
        String id = "o" + Integer.parseInt(t[0].substring(1));
        if (knownObjects.contains(id) || pendingExceptions.putIfAbsent(id, new ExceptionObject(t[1], messageOf(t[2]))) != null)
            throw new Inconsistent("exception seen twice");
    }

    /** A message token: h<hex>, n for null; ? (computed by user code) is never accepted. */
    private static String messageOf(String token) throws Inconsistent {
        if (token.equals("n")) return null;
        if (token.equals("?")) throw new Inconsistent("an exception message only user code computes");
        return unhex(token);
    }

    /** The id of a recorded exception object, captured (OBJECT_CAPTURE) when first referenced. */
    private String exceptionReference(String token) throws Inconsistent {
        String id = object(token);
        if (!knownObjects.contains(id)) {
            ExceptionObject pending = pendingExceptions.remove(id);
            if (pending == null) throw new Inconsistent("reference to an unrecorded exception");
            knownObjects.add(id);
            exceptions.put(id, pending);
            addBookkeeping("OBJECT_CAPTURE", Map.of("object", exceptionValue(id)));
        }
        if (!exceptions.containsKey(id)) throw new Inconsistent("not an exception");
        return id;
    }

    private Map<String, Object> exceptionValue(String id) {
        ExceptionObject e = exceptions.get(id);
        return ordered("objectId", id, "kind", "exception", "className", e.className(), "message", e.message());
    }

    /** An exception may be propagating: one recorded in flight, or one the last uncaptured statement may have thrown. */
    private void expectPropagating(String what) throws Inconsistent {
        if (inFlight == null && !regionThrow) throw new Inconsistent(what + " without an exception in flight");
    }

    /** While an exception is in flight, only the finally block that received it runs code. */
    private void expectNotPropagating() throws Inconsistent {
        if (inFlight != null && !finallyRunning) throw new Inconsistent("an operation while an exception is in flight");
    }

    /** {@code w}: the exception in flight left the innermost traced helper. */
    private void unwind() throws Inconsistent {
        expectPropagating("unwinding");
        if (frames.size() < 2 || pendingCall != null) throw new Inconsistent("unwinding main");
        addBookkeeping("FRAME_END", ordered("frameId", frameId, "reason", "unwind"));
        callbackFrames.remove(frameId); // Part 10d: a callback that threw never returned
        frames.pop();
        use(frames.peek());
    }

    /** A catch parameter receives the exception in flight: a CATCH step declaring it. */
    private void caught(Site site, String[] t) throws Inconsistent {
        expect(t, 2);
        expectPropagating("a catch");
        String id = exceptionReference(t[1]);
        if (inFlight != null && !inFlight.equals(id)) throw new Inconsistent("caught another exception than the one in flight");
        String className = exceptions.get(id).className();
        if (site.caught.stream().noneMatch(type -> subclass(className, type))) throw new Inconsistent("catch clause does not catch " + className);
        if (liveBindings.containsKey(site.declarationId)) reenterInnermost(site);
        String scope = scopes.get(scopes.size() - 1)[1];
        Map<String, Object> binding = binding(frameId, site.declarationId, site.name, site.javaType, scope, ref(id));
        liveBindings.put(site.declarationId, binding);
        addObservable(site, "CATCH", ordered("binding", copy(binding)));
        inFlight = null;
        regionThrow = false;
        finallyRunning = false;
        frames.peek().concats().clear(); // a concatenation the exception interrupted never completes
    }

    /** A finally handler receives the exception in flight (no step: its block's operations follow). */
    private void finallyEntry(String[] t) throws Inconsistent {
        expect(t, 2);
        expectPropagating("a finally handler");
        String id = exceptionReference(t[1]);
        if (inFlight != null && !inFlight.equals(id)) throw new Inconsistent("finally received another exception than the one in flight");
        inFlight = id;
        regionThrow = false;
        finallyRunning = true;
        frames.peek().concats().clear();
    }

    /** Whether a Throwable class (binary name) is {@code type} or a subclass: the source's classes, then the JDK's. */
    private boolean subclass(String className, String type) {
        String c = className;
        for (int guard = 0; exceptionSupers.containsKey(c) && guard < 64; guard++) {
            if (c.equals(type)) return true;
            c = exceptionSupers.get(c);
        }
        if (c.equals(type)) return true;
        if (exceptionSupers.containsKey(type)) return false; // the chain left the source's classes without reaching it
        try {
            ClassLoader platform = ClassLoader.getPlatformClassLoader();
            Class<?> actual = Class.forName(c, false, platform), wanted = Class.forName(type, false, platform);
            return Throwable.class.isAssignableFrom(actual) && wanted.isAssignableFrom(actual);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
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
        if (t[1].startsWith("v")) { callbackEntry(site(t[0]), site(t[1].substring(1))); return; } // Part 10d
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

    /** {@code c<method> v<library site>}: a function the covered library call runs (Part 10d). */
    private void callbackEntry(Site method, Site via) throws Inconsistent {
        if (method.kind != Plan.Kind.METHOD || via.kind != Plan.Kind.LIBRARY || !via.callbacks) throw new Inconsistent("callback record");
        if (frames.size() >= MAX_FRAMES) throw new Inconsistent("call depth");
        declarePending(via);
        syncScopes(via);
        pendingCall = new PendingCall(null, method, new ArrayList<>(), via);
        if (method.params.isEmpty()) enterCallee();
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
        Site via = pendingCall.via();
        Map<String, Object> frameData = ordered("frameId", frame, "methodId", method.methodId, "callerFrameId", frameId);
        if (via != null) { // Part 10d: a callback, logged for the replay of its library call
            frameData.put("via", via.methodId);
            CallbackLog log = callbackLogs.peek();
            if (log == null || log.via() != via || !log.callerFrame().equals(frameId)) callbackLogs.push(log = new CallbackLog(via, frameId, new ArrayList<>()));
            Callback callback = new Callback(method, new ArrayList<>(pendingCall.values()));
            log.calls().add(callback);
            callbackFrames.put(frame, callback);
        }
        // A lambda's CALL step shows the lambda; Main::helper run by a library call shows that call.
        Site shown = via == null ? pendingCall.call() : method.lambda ? method : via;
        addObservable(shown, "CALL", ordered(
                "frame", frameData,
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
        if (site.kind == Plan.Kind.CATCH) { caught(site, t); return; }
        if (site.kind == Plan.Kind.FINALLY) { finallyEntry(t); return; }
        regionThrow = false;
        expectNotPropagating();
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
                if (Library.CLASSES.contains(Analyzer.erasure(site.javaType)) || site.javaType.startsWith("java.util.")) { // Part 8f
                    if (t.length < 3) throw new Inconsistent("library allocation record");
                    String id = object(t[1]);
                    if (knownObjects.contains(id)) throw new Inconsistent("reused object identity");
                    buildMirror(id, t[2], List.of(t).subList(3, t.length));
                    addObservable(site, "ALLOCATE", ordered("object", libraryValue(id)));
                    return;
                }
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
            case LIBRARY -> libraryCall(site, t);
            case ITERATE -> iterate(site, t);
            case NEXT -> next(site, t);
            case CONCAT_ARG -> {
                expect(t, 2);
                Deque<Concat> concats = frames.peek().concats();
                if (concats.isEmpty() || concats.peek().site() != site.callee) concats.push(new Concat(site.callee, new ArrayList<>()));
                concats.peek().tokens().add(t[1]);
            }
            case CONCAT -> {
                expect(t, 2);
                Deque<Concat> concats = frames.peek().concats();
                if (concats.isEmpty() || concats.peek().site() != site) throw new Inconsistent("concatenation without operands");
                List<String> tokens = concats.pop().tokens();
                StringBuilder expected = new StringBuilder();
                List<Object> operands = new ArrayList<>();
                for (String token : tokens) {
                    Map<String, Object> value = libraryValueOf(token);
                    operands.add(operand(value));
                    expected.append(text(token, value));
                }
                Map<String, Object> result = reference(t[1]);
                if (!expected.toString().equals(stringTexts.get((String) result.get("objectId")))) throw new Inconsistent("concatenation result");
                addObservable(site, "EXPRESSION", ordered("operator", "+", "operands", operands, "result", result));
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
                Callback callback = callbackFrames.remove(frameId); // Part 10d: a callback's result, for its library call's replay
                if (callback != null) { callback.result = site.javaType.equals("void") ? null : t[1]; callback.returned = true; }
                addObservable(site, "RETURN", ordered("result", result, "implicit", site.implicit));
                frames.pop();
                use(frames.peek());
            }
            case REGION -> throw new Inconsistent("operation record for an uncaptured statement");
            case METHOD, PARAM, CALL, CLASS, FIELD, EXCEPTION_CLASS -> throw new Inconsistent("record for a declaration site");
            case THROW, CATCH, FINALLY -> throw new Inconsistent("operation record for an exception site");
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
            case "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float", "java.lang.Short", "java.lang.Byte",
                 "java.lang.Character", "java.lang.Boolean" -> { // Part 8f: a boxed value, or null
                Map<String, Object> value = libraryValueOf(token);
                if (!"null".equals(value.get("kind")) && !BOXES.get(type).equals(value.get("kind"))) throw new Inconsistent("boxed value of another type");
                return value;
            }
            default -> {
                if (function(token)) return functionValue(token); // Part 10d: a lambda, method reference or library-made function
                if (stream(token)) return streamValue(token);
                Map<String, Object> value = reference(token);
                if (value.get("objectId") instanceof String id) {
                    boolean fits = type.endsWith("[]") ? Analyzer.component(type).equals(elementTypes.get(id))
                            : type.equals("java.lang.String") ? stringTexts.containsKey(id)
                            : mirrors.containsKey(id) ? libraryFits(type, mirrors.get(id))
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
        if (!knownObjects.contains(id) && pendingLibrary.containsKey(id)) captureLibrary(id);
        if (!knownObjects.contains(id) && pendingExceptions.containsKey(id)) exceptionReference(token);
        if (!knownObjects.contains(id)) {
            String pending = pendingStrings.remove(id);
            if (pending == null) throw new Inconsistent("reference to an unrecorded object");
            knownObjects.add(id);
            List<Integer> units = new ArrayList<>();
            String hex = pending.substring(1);
            for (int i = 0; i < hex.length(); i += 4) units.add(Integer.parseInt(hex.substring(i, i + 4), 16));
            stringTexts.put(id, unhex(pending));
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

    // ---- library objects (Part 8f) ---------------------------------------------------------

    /** {@code C|M|B<id> <class> <body>}: a library object's contents, first seen or re-read. */
    @SuppressWarnings("unchecked")
    private void libraryContents(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length < 3) throw new Inconsistent("library record");
        String id = "o" + Integer.parseInt(t[0].substring(1));
        List<String> body = List.of(t).subList(2, t.length);
        if (!knownObjects.contains(id)) {
            if (pendingLibrary.putIfAbsent(id, new PendingLibrary(t[1], body)) != null) throw new Inconsistent("library object seen twice");
            return;
        }
        if (openRegion == null || !stale.remove(id) || !mirrors.containsKey(id)) throw new Inconsistent("unexpected library re-read");
        Map<String, Object> before = libraryValue(id);
        Map<String, Object> after = beforeOpenRegion(() -> { buildMirror(id, t[1], body); return libraryValue(id); });
        if (!before.equals(after)) ((List<Object>) openRegion.get("collections")).add(after);
    }

    /** A library object first seen by traced code (created by uncaptured code or held by another). */
    private void captureLibrary(String id) throws Inconsistent {
        PendingLibrary pending = pendingLibrary.remove(id);
        buildMirror(id, pending.className(), pending.tokens());
        addBookkeeping("OBJECT_CAPTURE", Map.of("object", libraryValue(id)));
    }

    /** Builds (or rebuilds) a mirror from recorded contents. Registered first: elements may refer back. */
    private void buildMirror(String id, String className, List<String> body) throws Inconsistent {
        Object mirror;
        if (className.equals("java.util.Scanner") || className.equals("java.io.BufferedReader")) {
            // Parts 8g, 8i: one reader of standard input per run replays its reads over the delivered input.
            if (inputCreated || !body.equals(List.of("0"))) throw new Inconsistent("a second reader of standard input");
            inputCreated = true;
            var delivered = new java.io.ByteArrayInputStream(input);
            mirror = className.equals("java.util.Scanner") ? new java.util.Scanner(delivered, java.nio.charset.StandardCharsets.UTF_8)
                    : new java.io.BufferedReader(new java.io.InputStreamReader(delivered, java.nio.charset.StandardCharsets.UTF_8));
            mirrors.put(id, mirror);
            mirrorIds.put(mirror, id);
            knownObjects.add(id);
            return;
        }
        // Part 10d: "/r" marks a sorted collection in the JDK's reverse order; "/c" one ordered by user code.
        if (className.endsWith("/c")) throw new Inconsistent("a collection ordered by a user comparator");
        if (className.startsWith("java.util.ImmutableCollections$")) className = "java.util.ArrayList"; // Part 10d: Stream.toList(), adopted from the replay
        boolean reversed = className.endsWith("/r");
        if (reversed) className = className.substring(0, className.length() - 2);
        try { mirror = reversed ? Library.createReversed(className) : Library.create(className); }
        catch (ReflectiveOperationException | RuntimeException e) { throw new Inconsistent("library class " + className); }
        Object old = mirrors.put(id, mirror);
        if (old != null) mirrorIds.remove(old);
        mirrorIds.put(mirror, id);
        knownObjects.add(id);
        if (mirror instanceof StringBuilder sb) {
            if (body.size() != 1) throw new Inconsistent("builder contents");
            sb.append(unhex(body.get(0)));
            return;
        }
        int n = Integer.parseInt(body.get(0));
        if (mirror instanceof Map) {
            if (body.size() != 1 + 2 * n) throw new Inconsistent("map contents");
            @SuppressWarnings("unchecked") Map<Object, Object> map = (Map<Object, Object>) mirror;
            for (int i = 0; i < n; i++) map.put(mirror(body.get(1 + 2 * i)), mirror(body.get(2 + 2 * i)));
        } else {
            if (body.size() != 1 + n) throw new Inconsistent("collection contents");
            @SuppressWarnings("unchecked") java.util.Collection<Object> c = (java.util.Collection<Object>) mirror;
            for (int i = 0; i < n; i++) c.add(mirror(body.get(1 + i)));
        }
    }

    /** The mirror value of a token: a box, a Str, a nested mirror, a Ref or null. */
    private Object mirror(String token) throws Inconsistent {
        if (token.equals("n")) return null;
        if (!token.startsWith("r")) return box(token);
        String id = (String) reference(token).get("objectId");
        if (stringTexts.containsKey(id)) return new Library.Str(stringTexts.get(id), id);
        if (mirrors.containsKey(id)) return mirrors.get(id);
        return new Library.Ref(id);
    }

    /** A boxed value token (I5, J5, D<bits>, F<bits>, C65, Z1, S5, B5) as its Java box. */
    private static Object box(String token) throws Inconsistent {
        try {
            String v = token.substring(1);
            return switch (token.charAt(0)) {
                case 'I' -> Integer.parseInt(v); case 'J' -> Long.parseLong(v); case 'D' -> Values.d(v); case 'F' -> Values.f(v);
                case 'C' -> (char) Integer.parseInt(v); case 'Z' -> v.equals("1"); case 'S' -> Short.parseShort(v); case 'B' -> Byte.parseByte(v);
                default -> throw new Inconsistent("value token " + token);
            };
        } catch (RuntimeException e) { throw new Inconsistent("value token " + token); }
    }

    /** The trace value of a self-describing token (boxes are shown as values). */
    private Map<String, Object> libraryValueOf(String token) throws Inconsistent {
        if (token.equals("n")) return ordered("kind", "null");
        if (function(token)) return functionValue(token); // Part 10d
        if (stream(token)) return streamValue(token);
        if (token.startsWith("r")) return reference(token);
        return traceValue(box(token));
    }

    /** The trace value of a mirror element. */
    private Map<String, Object> traceValue(Object v) throws Inconsistent {
        if (v == null) return ordered("kind", "null");
        if (v instanceof Library.Str str) return ref(str.id());
        if (v instanceof Library.Ref r) return ref(r.id());
        if (mirrorIds.containsKey(v)) return ref(mirrorIds.get(v));
        String token = switch (v) {
            case Integer i -> Integer.toString(i); case Long l -> Long.toString(l); case Short x -> Short.toString(x);
            case Byte x -> Byte.toString(x); case Character c -> Integer.toString(c); case Boolean b -> b ? "1" : "0";
            case Float f -> Values.f(f); case Double d -> Values.d(d);
            default -> throw new Inconsistent("library value " + v.getClass().getName());
        };
        String kind = switch (v) {
            case Integer i -> "int"; case Long l -> "long"; case Short x -> "short"; case Byte x -> "byte"; case Character c -> "char";
            case Boolean b -> "boolean"; case Float f -> "float"; default -> "double";
        };
        return scalar(kind, token);
    }

    /** String.valueOf of a concatenation operand, from its token and trace value. */
    private String text(String token, Map<String, Object> value) throws Inconsistent {
        if (token.equals("n")) return "null";
        if (token.startsWith("r")) {
            String text = stringTexts.get((String) value.get("objectId"));
            if (text == null) throw new Inconsistent("concatenated a non-string object");
            return text;
        }
        return String.valueOf(box(token));
    }

    /** The trace object of a library mirror. HashSet/HashMap ascend; PriorityQueue in poll order (never its layout). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> libraryValue(String id) throws Inconsistent {
        Object mirror = mirrors.get(id);
        String className = mirror.getClass().getName();
        if (mirror instanceof java.util.Scanner || mirror instanceof java.io.BufferedReader) return ordered("objectId", id, "kind", "input", "className", className);
        if (mirror instanceof StringBuilder sb) {
            List<Integer> units = new ArrayList<>();
            for (int i = 0; i < sb.length(); i++) units.add((int) sb.charAt(i));
            return ordered("objectId", id, "kind", "builder", "className", className, "utf16", units);
        }
        if (mirror instanceof Map.Entry<?, ?> e) // Part 10d
            return ordered("objectId", id, "kind", "entry", "mapId", entryMaps.get(id), "key", traceValue(e.getKey()), "value", traceValue(e.getValue()));
        if (mirror instanceof Map<?, ?> map) {
            List<Map.Entry<Object, Object>> entries = new ArrayList<>(((Map<Object, Object>) map).entrySet());
            if (mirror instanceof java.util.HashMap && !(mirror instanceof java.util.LinkedHashMap))
                entries.sort((a, b) -> ((Comparable<Object>) a.getKey()).compareTo(b.getKey()));
            List<Object> out = new ArrayList<>();
            for (var e : entries) out.add(ordered("key", traceValue(e.getKey()), "value", traceValue(e.getValue())));
            return ordered("objectId", id, "kind", "map", "className", className, "entries", out);
        }
        List<Object> elements = new ArrayList<>((java.util.Collection<Object>) mirror);
        if (mirror instanceof java.util.PriorityQueue<?> q && q.comparator() != null) elements.sort((java.util.Comparator<Object>) q.comparator()); // Part 10d
        else if (mirror instanceof java.util.PriorityQueue
                || mirror instanceof java.util.HashSet && !(mirror instanceof java.util.LinkedHashSet))
            elements.sort((a, b) -> ((Comparable<Object>) a).compareTo(b));
        List<Object> out = new ArrayList<>();
        for (Object e : elements) out.add(traceValue(e));
        return ordered("objectId", id, "kind", "collection", "className", className, "elements", out);
    }

    private static boolean libraryFits(String type, Object mirror) {
        String raw = Analyzer.erasure(type);
        if (raw.equals("java.util.Map.Entry")) return mirror instanceof Map.Entry; // Part 10d: a nested type's binary name differs
        try { return Class.forName(raw).isInstance(mirror); } catch (ClassNotFoundException e) { return false; }
    }

    /**
     * {@code <site> <receiver|-> <n> <args...> <result|v>}: replays the call on the mirror (or the
     * string, or statically), checks the recorded result, and records the receiver's new contents.
     */
    private void libraryCall(Site site, String[] t) throws Inconsistent {
        if (t.length < 4) throw new Inconsistent("library record");
        int n = Integer.parseInt(t[2]);
        if (t.length != 4 + n) throw new Inconsistent("library record arguments");
        if (site.name.equals("getMessage") && site.instance) { exceptionMessage(site, t); return; }
        java.lang.reflect.Method method;
        try { method = Library.method(site.methodId); } catch (ReflectiveOperationException | RuntimeException e) { throw new Inconsistent("library method " + site.methodId); }
        Class<?>[] params = method.getParameterTypes();
        if (params.length != n) throw new Inconsistent("library arity");
        String receiverId = null;
        Object target = null;
        if (site.instance && function(t[1])) { // Part 10d: calling a function value (f.apply(x)), replayed by its stand-in
            target = functionMirror(t[1], method.getDeclaringClass());
        } else if (site.instance && stream(t[1])) { // Part 10d: a stream or optional, replayed on its twin
            target = streamTwins.get(t[1]);
            if (target == null) throw new Inconsistent("a stream made by code that was not recorded");
        } else if (site.instance) {
            Map<String, Object> receiver = reference(t[1]);
            receiverId = (String) receiver.get("objectId");
            if (receiverId == null) throw new Inconsistent("library call on null"); // a null receiver fails instead
            target = stringTexts.containsKey(receiverId) ? stringTexts.get(receiverId) : mirrors.get(receiverId);
            if (target == null) throw new Inconsistent("library receiver");
        } else if (!t[1].equals("-")) throw new Inconsistent("static library receiver");
        Object[] args = new Object[n];
        List<Object> arguments = new ArrayList<>();
        Map<String, Object> javaArrays = new LinkedHashMap<>(); // Part 10d: array arguments, replayed on copies of their state
        String changedArgument = null; // Part 10d: a mirror argument a static call may change (Collections.sort)
        for (int i = 0; i < n; i++) {
            String token = t[3 + i];
            if (function(token)) { // Part 10d: a function the call runs, replayed by a stand-in returning recorded results
                arguments.add(functionValue(token));
                args[i] = functionMirror(token, params[i]);
                continue;
            }
            arguments.add(libraryValueOf(token));
            Object value = mirror(token);
            // A string argument of a String or StringBuilder method, or of a String/CharSequence parameter, is its text.
            if ((params[i] == String.class || params[i] == CharSequence.class || target instanceof String) && value instanceof Library.Str str) value = str.text();
            if (params[i].isArray() && value instanceof Library.Ref r && arrays.containsKey(r.id()))
                value = javaArrays.containsKey(r.id()) ? javaArrays.get(r.id()) : javaArray(r.id(), params[i], javaArrays);
            if (!site.instance && value != null && mirrorIds.containsKey(value)) changedArgument = mirrorIds.get(value);
            args[i] = value;
        }
        Map<String, Object> before = mirrors.containsKey(receiverId) ? libraryValue(receiverId)
                : changedArgument != null ? libraryValue(changedArgument) : null;
        // Part 10d: the callbacks recorded during this call, consumed in order by the stand-ins.
        CallbackLog log = !callbackLogs.isEmpty() && callbackLogs.peek().via() == site && callbackLogs.peek().callerFrame().equals(frameId)
                ? callbackLogs.pop() : null;
        Deque<Callback> queue = new ArrayDeque<>(log == null ? List.of() : log.calls());
        Object replayed;
        replayQueues.push(queue);
        try { replayed = method.invoke(target, args); }
        catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
            throw new Inconsistent("library replay of " + site.methodId + (cause instanceof ReplayMismatch m ? ": " + m.getMessage() : ""));
        } finally { replayQueues.pop(); }
        if (!queue.isEmpty()) throw new Inconsistent("callbacks of " + site.methodId + " the replay did not make");
        String token = t[3 + n];
        Map<String, Object> result;
        if (method.getReturnType() == void.class) {
            if (!token.equals("v")) throw new Inconsistent("void library result");
            result = null;
        } else if (stream(token)) { // Part 10d: a stream or optional the call made: its replayed twin
            if (replayed == null || streamTwins.containsKey(token)) throw new Inconsistent("library stream result");
            streamTwins.put(token, replayed);
            String base = !site.instance ? site.owner.substring(site.owner.lastIndexOf('.') + 1)
                    : stream(t[1]) ? streamLabels.get(t[1]) : target instanceof String ? "String" : target.getClass().getSimpleName();
            streamLabels.put(token, base + "." + site.name + "(" + arguments.stream().map(a -> label((Map<?, ?>) a)).reduce((a, b) -> a + ", " + b).orElse("") + ")");
            result = streamValue(token);
        } else if (function(token)) { // Part 10d: a function the library made (Comparator.comparing, andThen): its replayed twin
            if (replayed == null || !token.startsWith("g") || funcMirrors.containsKey(token)) throw new Inconsistent("library function result");
            funcMirrors.put(token, replayed);
            funcLabels.put(token, site.owner.substring(site.owner.lastIndexOf('.') + 1) + "." + site.name + "("
                    + arguments.stream().map(a -> a instanceof Map<?, ?> m && "function".equals(m.get("kind")) ? (String) m.get("label") : "…").reduce((a, b) -> a + ", " + b).orElse("") + ")");
            result = functionValue(token);
        } else {
            result = libraryValueOf(token);
            // Part 10d: a collection a stream collected is the replayed twin, checked against the recorded contents.
            if ((replayed instanceof java.util.Collection || replayed instanceof Map) && !mirrorIds.containsKey(replayed)
                    && result.get("objectId") instanceof String collected && mirrors.containsKey(collected)) adopt(replayed, collected);
            boolean same = replayed instanceof String text ? text.equals(stringTexts.get((String) result.get("objectId")))
                    : replayed != null && replayed.getClass().isArray() ? sameArray(replayed, result)
                    : traceValue(replayed).equals(result);
            if (!same) throw new Inconsistent("library result of " + site.methodId + ": recorded " + token + ", replayed " + replayed);
        }
        Map<String, Object> after = before == null ? null : libraryValue(receiverId != null ? receiverId : changedArgument);
        if (after != null && after.equals(before)) after = null;
        if (after != null && "map".equals(after.get("kind"))) checkEntries((String) after.get("objectId")); // Part 10d
        for (var e : javaArrays.entrySet()) { // Part 10d: an array the call changed (Arrays.sort) takes its replayed contents
            List<Map<String, Object>> state = arrays.get(e.getKey()), replayedElements = arrayElements(e.getValue());
            if (state.equals(replayedElements)) continue;
            if (after != null) throw new Inconsistent("a library call changed two objects");
            for (int i = 0; i < state.size(); i++) state.set(i, replayedElements.get(i));
            after = ordered("objectId", e.getKey(), "kind", "array", "elementType", elementTypes.get(e.getKey()), "elements", new ArrayList<>(state));
        }
        addObservable(site, "LIBRARY", ordered("objectId", receiverId, "method", site.methodId, "arguments", arguments,
                "result", result, "after", after));
    }

    /** A Java array holding a copy of a traced array's state, for replay (Part 10d). Strings are {@link Library.Str}. */
    private Object javaArray(String id, Class<?> param, Map<String, Object> made) throws Inconsistent {
        String elementType = elementTypes.get(id);
        List<Map<String, Object>> state = arrays.get(id);
        Object out = switch (elementType) {
            case "int" -> new int[state.size()]; case "long" -> new long[state.size()]; case "short" -> new short[state.size()];
            case "byte" -> new byte[state.size()]; case "char" -> new char[state.size()]; case "boolean" -> new boolean[state.size()];
            case "float" -> new float[state.size()]; case "double" -> new double[state.size()];
            case "java.lang.String" -> new Object[state.size()];
            default -> throw new Inconsistent("library call on an array of " + elementType);
        };
        if (!param.isInstance(out)) throw new Inconsistent("library array parameter");
        for (int i = 0; i < state.size(); i++) {
            Map<String, Object> v = state.get(i);
            Object kind = v.get("kind"), value = v.get("value");
            if ("unknown".equals(kind)) throw new Inconsistent("library call on an array with unknown elements");
            switch (elementType) {
                case "int" -> ((int[]) out)[i] = Integer.parseInt((String) value);
                case "long" -> ((long[]) out)[i] = Long.parseLong((String) value);
                case "short" -> ((short[]) out)[i] = Short.parseShort((String) value);
                case "byte" -> ((byte[]) out)[i] = Byte.parseByte((String) value);
                case "char" -> ((char[]) out)[i] = (char) ((Number) value).intValue();
                case "boolean" -> ((boolean[]) out)[i] = (Boolean) value;
                case "float" -> ((float[]) out)[i] = Values.f((String) v.get("bits"));
                case "double" -> ((double[]) out)[i] = Values.d((String) v.get("bits"));
                default -> {
                    if ("null".equals(kind)) continue;
                    String sid = (String) v.get("objectId");
                    if (!stringTexts.containsKey(sid)) throw new Inconsistent("string array element");
                    ((Object[]) out)[i] = new Library.Str(stringTexts.get(sid), sid);
                }
            }
        }
        made.put(id, out);
        return out;
    }

    /** The trace values of a replayed Java array's elements. */
    private List<Map<String, Object>> arrayElements(Object array) throws Inconsistent {
        int n = java.lang.reflect.Array.getLength(array);
        List<Map<String, Object>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(traceValue(java.lang.reflect.Array.get(array, i)));
        return out;
    }

    /** Whether a recorded array result has the replayed array's type and contents (Part 10d). */
    private boolean sameArray(Object replayed, Map<String, Object> result) throws Inconsistent {
        String id = (String) result.get("objectId");
        if (id == null || !arrays.containsKey(id)) return false;
        List<Map<String, Object>> state = arrays.get(id);
        if (replayed instanceof String[] texts) {
            if (!"java.lang.String".equals(elementTypes.get(id)) || state.size() != texts.length) return false;
            for (int i = 0; i < texts.length; i++)
                if (!texts[i].equals(stringTexts.get((String) state.get(i).get("objectId")))) return false;
            return true;
        }
        return replayed.getClass().getComponentType().getName().equals(elementTypes.get(id)) && state.equals(arrayElements(replayed));
    }

    // ---- Part 10d: functions, callbacks and their replay -------------------------------------

    /** One function a library call ran: its frame's parameters and, once it returned, its result token. */
    private static final class Callback {
        final Site method;
        final List<Map<String, Object>> args;
        String result;
        boolean returned;
        Callback(Site method, List<Map<String, Object>> args) { this.method = method; this.args = args; }
    }

    /** The callbacks of one library call still running, in the frame that made it. */
    private record CallbackLog(Site via, String callerFrame, List<Callback> calls) { }

    /** A replay that does not make the recorded callbacks (another order, other arguments). */
    private static final class ReplayMismatch extends RuntimeException {
        ReplayMismatch(String message) { super(message, null, false, false); }
    }

    private final Deque<CallbackLog> callbackLogs = new ArrayDeque<>();
    private final Map<String, Callback> callbackFrames = new HashMap<>(); // callback frame id -> its log entry
    private final Deque<Deque<Callback>> replayQueues = new ArrayDeque<>(); // callbacks of the library calls being replayed
    private final Map<String, Object> funcMirrors = new HashMap<>(); // g<n> -> the replayed twin of a library-made function
    private final Map<String, String> funcLabels = new HashMap<>();

    /** Whether a record token is a function: f<LAMBDA site> or g<n>. */
    private static boolean function(String token) { return token.length() > 1 && (token.charAt(0) == 'f' || token.charAt(0) == 'g'); }

    private Map<String, Object> functionValue(String token) throws Inconsistent {
        if (token.startsWith("g")) {
            String label = funcLabels.get(token);
            if (label == null) throw new Inconsistent("a function made by code that was not recorded");
            return ordered("kind", "function", "label", label);
        }
        Site lambda = site(token.substring(1));
        if (lambda.kind != Plan.Kind.LAMBDA) throw new Inconsistent("function token");
        return ordered("kind", "function", "label", lambda.name);
    }

    /**
     * A stand-in for a function during replay: a lambda or Main::helper returns the next recorded
     * callback's result after checking it is the same function with the same arguments; a JDK method
     * reference runs that JDK method (System.out::println does nothing: its output was already written).
     */
    private Object functionMirror(String token, Class<?> iface) throws Inconsistent {
        if (token.startsWith("g")) {
            Object twin = funcMirrors.get(token);
            if (twin == null || !iface.isInstance(twin)) throw new Inconsistent("a function made by code that was not recorded");
            return twin;
        }
        Site lambda = site(token.substring(1));
        if (lambda.kind != Plan.Kind.LAMBDA || !iface.isInterface()) throw new Inconsistent("function token");
        java.lang.reflect.InvocationHandler handler = (proxy, m, a) -> {
            if (m.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, m, a);
            Object[] args = a == null ? new Object[0] : a;
            switch (m.getName()) {
                case "equals": if (args.length == 1 && m.getParameterTypes()[0] == Object.class) return proxy == args[0]; break;
                case "hashCode": if (args.length == 0) return System.identityHashCode(proxy); break;
                case "toString": if (args.length == 0) return lambda.name; break;
                default: break;
            }
            if (lambda.callee == null) return jdkReference(lambda, args);
            Deque<Callback> queue = replayQueues.peek();
            Callback callback = queue == null ? null : queue.poll();
            if (callback == null || callback.method != lambda.callee) throw new ReplayMismatch(lambda.name + " was not called here");
            if (!callback.returned) throw new ReplayMismatch(lambda.name + " did not return");
            List<Map<String, Object>> recorded = callback.args.subList(callback.args.size() - args.length, callback.args.size());
            for (int i = 0; i < args.length; i++) {
                try { if (!traceValue(args[i]).equals(recorded.get(i))) throw new ReplayMismatch(lambda.name + " argument " + i); }
                catch (Inconsistent e) { throw new ReplayMismatch(e.getMessage()); }
            }
            try { return callback.result == null ? null : resultMirror(callback.method.javaType, callback.result); }
            catch (Inconsistent e) { throw new ReplayMismatch(e.getMessage()); }
        };
        return java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader() == null ? TraceBuilder.class.getClassLoader() : iface.getClassLoader(),
                new Class<?>[]{iface}, handler);
    }

    /** A JDK method reference during replay: pure methods run on the mirror values; printing does nothing. */
    private static Object jdkReference(Site lambda, Object[] args) throws ReflectiveOperationException {
        if (lambda.operator.equals("print")) return null;
        java.lang.reflect.Method m = Library.method(lambda.methodId);
        Object[] plain = new Object[args.length];
        for (int i = 0; i < args.length; i++) plain[i] = args[i] instanceof Library.Str s ? s.text() : args[i];
        if (lambda.operator.equals("static")) return m.invoke(null, plain);
        return m.invoke(plain[0], java.util.Arrays.copyOfRange(plain, 1, plain.length));
    }

    /** A recorded callback result as the mirror value the library would have received. */
    private Object resultMirror(String javaType, String token) throws Inconsistent {
        try {
            return switch (javaType) {
                case "int" -> Integer.parseInt(token);
                case "short" -> Short.parseShort(token);
                case "byte" -> Byte.parseByte(token);
                case "char" -> (char) Integer.parseInt(token);
                case "boolean" -> token.equals("1");
                case "long" -> Long.parseLong(token);
                case "float" -> Values.f(token);
                case "double" -> Values.d(token);
                default -> function(token) ? functionMirror(token, Object.class) : mirror(token);
            };
        } catch (RuntimeException e) { throw new Inconsistent("callback result " + token); }
    }

    // ---- Part 10d: sequential streams ------------------------------------------------------------

    private final Map<String, Object> streamTwins = new HashMap<>(); // s<n> -> the replayed stream or optional
    private final Map<String, String> streamLabels = new HashMap<>();

    /** Whether a record token is a stream or optional: s<n>. */
    private static boolean stream(String token) { return token.length() > 1 && token.charAt(0) == 's'; }

    /** A stream is shown by the calls that made it; an optional by its contents (OptionalInt[9], Optional.empty). */
    private Map<String, Object> streamValue(String token) throws Inconsistent {
        Object twin = streamTwins.get(token);
        if (twin == null) throw new Inconsistent("a stream made by code that was not recorded");
        if (twin instanceof java.util.Optional<?> || twin instanceof java.util.OptionalInt || twin instanceof java.util.OptionalDouble)
            return ordered("kind", "optional", "label", twin.toString());
        return ordered("kind", "stream", "label", streamLabels.get(token));
    }

    /** Short text of an argument inside a stream's label. */
    private String label(Map<?, ?> value) {
        Object kind = value.get("kind");
        if ("function".equals(kind) || "stream".equals(kind) || "optional".equals(kind)) return (String) value.get("label");
        if ("ref".equals(kind)) {
            String text = stringTexts.get((String) value.get("objectId"));
            return text == null ? "…" : '"' + text + '"';
        }
        if ("char".equals(kind)) return "'" + (char) ((Number) value.get("value")).intValue() + "'";
        return value.containsKey("value") ? String.valueOf(value.get("value")) : "…";
    }

    /**
     * A collection a stream's terminal operation made: the recorded object (captured with its contents)
     * takes the replayed twin as its mirror, after checking the twin holds the same contents, nested
     * collections (groupingBy) included.
     */
    @SuppressWarnings("unchecked")
    private void adopt(Object twin, String id) throws Inconsistent {
        Object mirror = mirrors.get(id);
        Map<String, Object> recorded = libraryValue(id);
        if (twin instanceof Map<?, ?> t && mirror instanceof Map<?, ?> m) {
            if (t.size() != m.size()) throw new Inconsistent("collected map size");
            for (var e : ((Map<Object, Object>) m).entrySet()) {
                Object nested = t.get(e.getKey());
                if (mirrorIds.containsKey(e.getValue()) && (nested instanceof java.util.Collection || nested instanceof Map))
                    adopt(nested, mirrorIds.get(e.getValue()));
            }
        } else if (twin instanceof java.util.Collection<?> t && mirror instanceof java.util.Collection<?> m) {
            if (t.size() != m.size()) throw new Inconsistent("collected size");
            var ti = t.iterator();
            for (Object e : m) {
                Object nested = ti.next();
                if (mirrorIds.containsKey(e) && (nested instanceof java.util.Collection || nested instanceof Map)) adopt(nested, mirrorIds.get(e));
            }
        } else throw new Inconsistent("collected another kind of object");
        mirrorIds.remove(mirror);
        mirrors.put(id, twin);
        mirrorIds.put(twin, id);
        if (!libraryValue(id).get(twin instanceof Map ? "entries" : "elements").equals(recorded.get(twin instanceof Map ? "entries" : "elements")))
            throw new Inconsistent("collected contents differ from the replay");
    }

    // ---- Part 10d: for-each over collections and map views ------------------------------------

    private record Iteration(String collectionId, Iterator<?> iterator) { }

    private final Map<String, Iteration> iterations = new HashMap<>(); // frame id + "/" + ITERATE site id
    private final Map<String, List<String>> pendingEntries = new HashMap<>();
    private final Map<String, String> entryMaps = new HashMap<>(); // entry id -> its map's id
    private final Map<String, Map<String, Object>> entryStates = new HashMap<>(); // as last shown

    /** {@code <site> <collection>}: a for-each starts; the builder iterates its own mirror alongside. */
    @SuppressWarnings("unchecked")
    private void iterate(Site site, String[] t) throws Inconsistent {
        expect(t, 2);
        String id = (String) reference(t[1]).get("objectId");
        if (id == null) return; // a null collection: its failure record follows
        Object mirror = mirrors.get(id);
        Iterator<?> iterator;
        if (site.name.equals("elements")) {
            if (!(mirror instanceof java.util.Collection<?> c) || mirror instanceof java.util.PriorityQueue) throw new Inconsistent("iterated object");
            iterator = c.iterator();
        } else {
            if (!(mirror instanceof Map<?, ?> m)) throw new Inconsistent("iterated map");
            iterator = switch (site.name) {
                case "keys" -> m.keySet().iterator();
                case "values" -> m.values().iterator();
                default -> m.entrySet().iterator();
            };
        }
        iterations.put(frames.peek().id() + "/" + site.id, new Iteration(id, iterator));
    }

    /** {@code <site> <element>}: one next() of a for-each, checked against the mirror's own next element. */
    private void next(Site site, String[] t) throws Inconsistent {
        expect(t, 2);
        Iteration iteration = iterations.get(frames.peek().id() + "/" + site.callee.id);
        if (iteration == null) throw new Inconsistent("next() without its for-each");
        Object element;
        try { element = iteration.iterator().next(); } catch (RuntimeException e) { throw new Inconsistent("for-each replay"); }
        Map<String, Object> result;
        if (site.name.equals("entries")) result = entry(t[1], (Map.Entry<?, ?>) element, iteration.collectionId());
        else {
            result = libraryValueOf(t[1]);
            if (!traceValue(element).equals(result)) throw new Inconsistent("next element: recorded " + t[1] + ", replayed " + element);
        }
        Map<String, Object> data = ordered("objectId", iteration.collectionId(), "method", "java.util.Iterator.next()",
                "arguments", List.of(), "result", result, "after", null);
        if (!site.name.equals("elements")) data.put("view", site.name);
        addObservable(site, "LIBRARY", data);
    }

    /** A map entry returned by next() over entrySet(): captured when first seen, checked against the mirror's entry. */
    private Map<String, Object> entry(String token, Map.Entry<?, ?> mirrorEntry, String mapId) throws Inconsistent {
        String id = object(token);
        if (knownObjects.contains(id)) {
            if (mirrors.get(id) != mirrorEntry) throw new Inconsistent("map entry identity");
            return ref(id);
        }
        List<String> body = pendingEntries.remove(id);
        if (body == null || body.size() != 3) throw new Inconsistent("unrecorded map entry");
        Map<String, Object> key = libraryValueOf(body.get(1)), value = libraryValueOf(body.get(2));
        if (!traceValue(mirrorEntry.getKey()).equals(key) || !traceValue(mirrorEntry.getValue()).equals(value))
            throw new Inconsistent("map entry contents");
        knownObjects.add(id);
        mirrors.put(id, mirrorEntry);
        mirrorIds.put(mirrorEntry, id);
        entryMaps.put(id, mapId);
        Map<String, Object> shown = libraryValue(id);
        entryStates.put(id, shown);
        addBookkeeping("OBJECT_CAPTURE", Map.of("object", shown));
        return ref(id);
    }

    /** {@code Y<id> <class> <key> <value>}: a map entry first seen, or re-read after uncaptured code. */
    private void entryContents(String line) throws Inconsistent {
        String[] t = line.split(" ");
        if (t.length != 4) throw new Inconsistent("map entry record");
        String id = "o" + Integer.parseInt(t[0].substring(1));
        List<String> body = List.of(t).subList(1, 4);
        if (!knownObjects.contains(id)) {
            if (pendingEntries.putIfAbsent(id, body) != null) throw new Inconsistent("map entry seen twice");
            return;
        }
        // An entry is shown as it was when obtained; a change the builder cannot follow stops capture.
        if (!libraryValueOf(body.get(1)).equals(entryStates.get(id).get("key"))
                || !libraryValueOf(body.get(2)).equals(entryStates.get(id).get("value"))) throw new Inconsistent("a map entry changed without capture");
    }

    /** After a covered call changed a map: an entry it changed (put of an iterated key) cannot be shown faithfully. */
    private void checkEntries(String mapId) throws Inconsistent {
        for (var e : entryMaps.entrySet()) {
            if (e.getValue().equals(mapId) && !libraryValue(e.getKey()).equals(entryStates.get(e.getKey())))
                throw new Inconsistent("a map entry changed while shown");
        }
    }

    /** {@code e.getMessage()} (Part 8h): the result must be the message recorded with the exception object. */
    private void exceptionMessage(Site site, String[] t) throws Inconsistent {
        expect(t, 4);
        String id = exceptionReference(t[1]);
        String message = exceptions.get(id).message();
        Map<String, Object> result = libraryValueOf(t[3]);
        boolean same = message == null ? "null".equals(result.get("kind")) : message.equals(stringTexts.get((String) result.get("objectId")));
        if (!same) throw new Inconsistent("exception message");
        addObservable(site, "LIBRARY", ordered("objectId", id, "method", site.methodId, "arguments", List.of(), "result", result, "after", null));
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
                "schemaVersion", "1.10.0",
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
