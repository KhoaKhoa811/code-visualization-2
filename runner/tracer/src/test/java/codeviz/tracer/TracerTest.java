package codeviz.tracer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 7b producer checks on trusted programs run as host JVM processes. Every
 * trace is validated by the shared trace-v1 checker (strict schema plus envelope,
 * source and cursor-boundary checks), and every traced program is compared with
 * the original: stdout, exit status and the uncaught-exception line must match.
 */
class TracerTest {
    @TempDir Path temp;

    record Run(Map<String, Object> trace, HostRun.Result result, Tracer.Prepared prepared, String json) { }

    private static String program(String body) {
        return "public class Main {\n    public static void main(String[] args) {\n" + body + "\n    }\n}\n";
    }

    private Run run(String name, String source) throws Exception { return run(name, source, null); }

    /** {@code input}: the program's standard input (Part 8g), given to the original, the traced run and the builder. */
    private Run run(String name, String source, byte[] input) throws Exception {
        Tracer.Prepared prepared = Tracer.prepare(source);
        HostRun.Result result = prepared.compiled() ? HostRun.run(prepared.classes(), prepared.traced(), temp, input)
                : new HostRun.Result(-1, "", "", List.of());
        // A run halted at the raw or call-depth limit legitimately differs from the original.
        if (prepared.traced() && !result.raw().contains("L") && !result.raw().contains("D") && !result.raw().contains("T")) {
            HostRun.Result original = HostRun.run(prepared.analysis().classes(), false, temp, input);
            assertEquals(original.stdout(), result.stdout(), "stdout must match the original program; traced stderr: " + result.stderrFirstLine());
            assertEquals(original.exit(), result.exit(), "exit status must match the original program");
            String expected = original.stderrFirstLine(), actual = result.stderrFirstLine();
            if (actual.contains("because the object is null")) { // Part 8e: the recorder names the field, not a helpful NPE
                expected = expected.substring(0, expected.indexOf(" because ") + 1);
                actual = actual.substring(0, actual.indexOf(" because ") + 1);
            }
            assertEquals(expected, actual, "uncaught exception must match");
            // Part 8i: the stack trace below it shows the program's own frames, never the recorder's.
            assertEquals(stackFrames(original.stderr()), stackFrames(result.stderr()), "stack trace must match the original program");
        }
        byte[] delivered = input == null ? new byte[0] : input;
        String json = Tracer.trace(prepared, "test-" + name, result.raw(), result.exit(), delivered);
        validate(name, json);
        // The backend builds the trace from the serialized plan; it must give the same trace.
        Tracer.Prepared decoded = Tracer.fromPlan(source, Tracer.plan(prepared));
        assertEquals(json, Tracer.trace(decoded, "test-" + name, result.raw(), result.exit(), delivered), "plan round trip");
        Map<String, Object> trace = JsonReader.object(json);
        assertReferencesResolve(trace);
        return new Run(trace, result, prepared, json);
    }

    /** The "at ..." lines of uncaught stack traces (the message lines are compared separately). */
    private static List<String> stackFrames(String stderr) {
        return stderr.lines().filter(l -> l.startsWith("\tat ") || l.startsWith("\t... ") || l.startsWith("Caused by")).toList();
    }

    /**
     * Every reference names an object captured by the end of its step (Part 8c: nested arrays;
     * Part 8e: objects captured in one step may refer to each other, cycles included).
     */
    @SuppressWarnings("unchecked")
    private static void assertReferencesResolve(Map<String, Object> trace) {
        if (trace.get("initialState") == null) return;
        java.util.Set<Object> known = new java.util.HashSet<>();
        for (Object o : (List<Object>) map(trace.get("initialState")).get("objects")) known.add(map(o).get("objectId"));
        List<Object> waiting = new ArrayList<>();
        for (Map<String, Object> e : events(trace)) {
            Map<String, Object> data = map(e.get("data"));
            Object created = data.get("object") instanceof Map<?, ?> object ? object.get("objectId") : null;
            if (created != null) assertTrue(known.add(created), "object captured twice: " + created);
            collectRefs(data, waiting);
            if (!e.containsKey("step")) continue;
            for (Object id : waiting) assertTrue(known.contains(id), "step " + e.get("step") + " references uncaptured " + id);
            waiting.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectRefs(Object node, List<Object> out) {
        if (node instanceof Map<?, ?> m) {
            if ("ref".equals(m.get("kind"))) out.add(m.get("objectId"));
            for (Object v : m.values()) collectRefs(v, out);
        } else if (node instanceof List<?> l) {
            for (Object v : l) collectRefs(v, out);
        }
    }

    private Run main(String name, String body) throws Exception { return run(name, program(body)); }

    /** Strict trace-v1 schema + shared metadata checks from contracts/. */
    private void validate(String name, String json) throws Exception {
        Path dir = Path.of("target", "traces");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        Process node = new ProcessBuilder("node", Path.of("../../contracts/trace/check.cjs").toString(), file.toString())
                .redirectErrorStream(true).start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(node.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, node.exitValue(), "trace-v1 check failed: " + output);
    }

    // ---- projections ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> trace) {
        return (List<Map<String, Object>>) trace.get("events");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    private static String value(Object typed) {
        Map<String, Object> v = map(typed);
        return switch ((String) v.get("kind")) {
            case "ref" -> "#" + v.get("objectId");
            case "null" -> "null";
            case "unknown" -> "?";
            case "unassigned" -> "_";
            case "function" -> (String) v.get("label"); // Part 10d
            case "float" -> String.valueOf(Float.intBitsToFloat(Integer.parseUnsignedInt((String) v.get("bits"), 16)));
            case "double" -> String.valueOf(Double.longBitsToDouble(Long.parseUnsignedLong((String) v.get("bits"), 16)));
            default -> String.valueOf(v.get("value"));
        };
    }

    /** One line per observable Step: kind, facts, and the highlighted source text. */
    @SuppressWarnings("unchecked")
    private static List<String> steps(Map<String, Object> trace) {
        String text = (String) map(trace.get("source")).get("text");
        Map<Object, String> names = new HashMap<>();
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : events(trace)) {
            if (!e.containsKey("step")) continue;
            Map<String, Object> d = map(e.get("data"));
            String fact = switch ((String) e.get("kind")) {
                case "DECLARE" -> {
                    Map<String, Object> b = map(d.get("binding"));
                    names.put(b.get("bindingId"), (String) b.get("name"));
                    yield "DECLARE " + b.get("name") + "=" + value(b.get("value"));
                }
                case "ASSIGN" -> "ASSIGN " + names.get(d.get("bindingId")) + " " + value(d.get("previous")) + "->" + value(d.get("value"));
                case "UPDATE" -> "UPDATE " + names.get(d.get("bindingId")) + " " + value(d.get("previous")) + "->"
                        + value(d.get("value")) + " =" + value(d.get("result"));
                case "ALLOCATE" -> "ALLOCATE " + describeObject(map(d.get("object")));
                case "FIELD_READ" -> "FREAD " + d.get("field") + "=" + value(d.get("value"));
                case "FIELD_WRITE" -> "FWRITE " + d.get("field") + " " + value(d.get("previous")) + "->" + value(d.get("value"));
                case "ARRAY_READ" -> "READ [" + d.get("index") + "]=" + value(d.get("value"));
                case "ARRAY_WRITE" -> "WRITE [" + d.get("index") + "] " + value(d.get("previous")) + "->" + value(d.get("value"));
                case "ARRAY_LENGTH" -> "LENGTH " + d.get("length");
                case "EXPRESSION" -> "EXPR " + d.get("operator") + " " + ((List<Object>) d.get("operands")).stream()
                        .map(o -> map(o).containsKey("skipped") ? "_" : value(map(o).get("value")))
                        .reduce((a, b) -> a + " " + b).orElse("") + " = " + value(d.get("result"));
                case "EXCEPTION" -> "EXCEPTION " + d.get("exceptionType");
                case "CATCH" -> { // Part 8h
                    Map<String, Object> b = map(d.get("binding"));
                    names.put(b.get("bindingId"), (String) b.get("name"));
                    yield "CATCH " + b.get("name") + "=" + value(b.get("value"));
                }
                case "SWITCH" -> "SWITCH " + value(d.get("value")) + " " + d.get("matched");
                case "LIBRARY" -> {
                    String method = (String) d.get("method");
                    String name = method.substring(method.lastIndexOf('.', method.indexOf('(')) + 1, method.indexOf('('));
                    yield "LIB " + name + ((List<Object>) d.get("arguments")).stream().map(TracerTest::value).toList().toString()
                            .replace(" ", "").replace("[", "(").replace("]", ")")
                            + (d.get("result") == null ? "" : " = " + value(d.get("result")))
                            + (d.get("after") == null ? "" : " -> " + describeObject(map(d.get("after"))));
                }
                case "CALL" -> {
                    List<Object> parameters = (List<Object>) d.get("parameters");
                    for (Object p : parameters) names.put(map(p).get("bindingId"), (String) map(p).get("name"));
                    yield "CALL " + map(d.get("frame")).get("methodId") + parameters.stream()
                            .map(p -> " " + map(p).get("name") + "=" + value(map(p).get("value"))).reduce("", String::concat);
                }
                case "RETURN" -> "RETURN " + (d.get("result") == null ? "void" : value(d.get("result")))
                        + (Boolean.TRUE.equals(d.get("implicit")) ? " implicit" : "");
                case "UNCAPTURED" -> "UNCAPTURED" + ((List<Object>) d.get("unknownBindings")).stream().map(id -> " " + names.get(id) + "=?")
                        .reduce("", String::concat) + ((List<Object>) d.get("arrays")).stream().map(a -> " #" + map(a).get("objectId") + "="
                        + ((List<Object>) map(a).get("elements")).stream().map(TracerTest::value).toList().toString().replace(" ", ""))
                        .reduce("", String::concat) + ((List<Object>) d.get("unknownArrays")).stream().map(id -> " #" + id + "=?").reduce("", String::concat)
                        + ((List<Object>) d.getOrDefault("objects", List.of())).stream().map(o -> " #" + map(o).get("objectId") + ((List<Object>) map(o).get("fields")).stream()
                                .map(f -> map(f).get("name") + "=" + value(map(f).get("value"))).toList().toString().replace(" ", "").replace("[", "{").replace("]", "}"))
                        .reduce("", String::concat);
                default -> e.get("kind").toString();
            };
            Map<String, Object> range = map(e.get("source"));
            out.add(fact + " @" + text.substring(((Long) range.get("start")).intValue(), ((Long) range.get("end")).intValue()));
        }
        return out;
    }

    /** An array as [elements], an object (Part 8e) as Class{field=value,...}. */
    @SuppressWarnings("unchecked")
    private static String describeObject(Map<String, Object> o) {
        if ("input".equals(o.get("kind"))) { String c = (String) o.get("className"); return c.substring(c.lastIndexOf('.') + 1); }
        if ("builder".equals(o.get("kind"))) return "\"" + ((List<Object>) o.get("utf16")).stream()
                .map(u -> String.valueOf((char) ((Long) u).intValue())).reduce("", String::concat) + "\"";
        if ("map".equals(o.get("kind"))) return ((List<Object>) o.get("entries")).stream()
                .map(e -> value(map(e).get("key")) + "=" + value(map(e).get("value"))).toList().toString().replace(" ", "").replace("[", "{").replace("]", "}");
        if ("object".equals(o.get("kind"))) return o.get("className") + ((List<Object>) o.get("fields")).stream()
                .map(f -> map(f).get("name") + "=" + value(map(f).get("value"))).toList().toString().replace(" ", "").replace("[", "{").replace("]", "}");
        return ((List<Object>) o.get("elements")).stream().map(TracerTest::value).toList().toString().replace(" ", "");
    }

    /** Steps with string references shown as their quoted text (Part 8f). */
    @SuppressWarnings("unchecked")
    private static List<String> textSteps(Map<String, Object> trace) {
        Map<String, String> texts = new HashMap<>();
        for (Map<String, Object> e : events(trace)) {
            if (!(map(e.get("data")).get("object") instanceof Map<?, ?> o) || !"string".equals(o.get("kind"))) continue;
            texts.put((String) o.get("objectId"), ((List<Object>) o.get("utf16")).stream()
                    .map(u -> String.valueOf((char) ((Long) u).intValue())).reduce("", String::concat));
        }
        List<String> out = new ArrayList<>();
        for (String step : steps(trace)) {
            var m = java.util.regex.Pattern.compile("#(o\\d+)").matcher(step);
            StringBuilder b = new StringBuilder();
            while (m.find()) m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(
                    texts.containsKey(m.group(1)) ? "\"" + texts.get(m.group(1)) + "\"" : m.group()));
            m.appendTail(b);
            out.add(b.toString());
        }
        return out;
    }

    private static List<String> kinds(Map<String, Object> trace) {
        return events(trace).stream().map(e -> (String) e.get("kind")).toList();
    }

    private static String status(Map<String, Object> trace, String part) { return (String) map(trace.get(part)).get("status"); }

    private static void assertInOrder(List<String> actual, String... expected) {
        int at = 0;
        for (String want : expected) {
            while (at < actual.size() && !actual.get(at).equals(want)) at++;
            assertTrue(at < actual.size(), "missing in order: " + want + "\nsteps:\n" + String.join("\n", actual));
            at++;
        }
    }

    private static void assertComplete(Run run, String execution) {
        assertTrue(run.prepared().traced(), "program should be traceable: " + run.prepared().analysis().unsupported());
        assertEquals(execution, status(run.trace(), "execution"));
        assertEquals("complete", status(run.trace(), "capture"), () -> "capture: " + run.trace().get("capture") + "\nsteps:\n" + String.join("\n", steps(run.trace())));
    }

    // ---- acceptance cases -----------------------------------------------------

    @Test void ac01ScalarDeclarationThenAssignment() throws Exception {
        Run r = main("ac01", "int x = 5;\nx = 8;");
        assertComplete(r, "completed");
        assertEquals(List.of("DECLARE x=5 @int x = 5", "ASSIGN x 5->8 @x = 8"), steps(r.trace()));
        assertEquals("FRAME_END", kinds(r.trace()).get(kinds(r.trace()).size() - 1));
    }

    @Test void ac02OnlyOneElementChanges() throws Exception {
        Run r = main("ac02", "int[] a = {3, 1, 2};\na[1] = 9;");
        assertComplete(r, "completed");
        assertEquals(List.of("ALLOCATE [3,1,2] @{3, 1, 2}", "DECLARE a=#o2 @int[] a = {3, 1, 2}",
                "WRITE [1] 1->9 @a[1] = 9"), steps(r.trace()));
    }

    @Test void ac03LoopReadsAndWritesInOrder() throws Exception {
        Run r = main("ac03", """
                int[] a = {1, 2, 3};
                for (int i = 0; i < a.length; i++) {
                    a[i] = a[i] * 2;
                }
                for (int i = 0; i < a.length; i++) System.out.println(a[i]);""");
        assertComplete(r, "completed");
        assertEquals("2\n4\n6\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "DECLARE i=0 @int i = 0", "LENGTH 3 @a.length", "EXPR < 0 3 = true @i < a.length",
                "READ [0]=1 @a[i]", "EXPR * 1 2 = 2 @a[i] * 2", "WRITE [0] 1->2 @a[i] = a[i] * 2", "UPDATE i 0->1 =0 @i++",
                "READ [2]=3 @a[i]", "WRITE [2] 3->6 @a[i] = a[i] * 2", "EXPR < 3 3 = false @i < a.length");
    }

    @Test void ac04UserWrittenBubbleSort() throws Exception {
        Run r = main("ac04", """
                int[] a = {3, 1, 2};
                for (int i = 0; i < a.length - 1; i++) {
                    for (int j = 0; j < a.length - 1 - i; j++) {
                        if (a[j] > a[j + 1]) {
                            int t = a[j];
                            a[j] = a[j + 1];
                            a[j + 1] = t;
                        }
                    }
                }
                for (int k = 0; k < a.length; k++) System.out.println(a[k]);""");
        assertComplete(r, "completed");
        assertEquals("1\n2\n3\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertEquals(List.of("EXPR > 3 1 = true @a[j] > a[j + 1]", "EXPR > 3 2 = true @a[j] > a[j + 1]",
                "EXPR > 1 2 = false @a[j] > a[j + 1]"), steps.stream().filter(s -> s.startsWith("EXPR >")).toList());
        assertEquals(List.of("WRITE [0] 3->1 @a[j] = a[j + 1]", "WRITE [1] 1->3 @a[j + 1] = t",
                "WRITE [1] 3->2 @a[j] = a[j + 1]", "WRITE [2] 2->3 @a[j + 1] = t"),
                steps.stream().filter(s -> s.startsWith("WRITE")).toList());
        // Each swap declares its own t: two lifetimes, separated by a scope exit.
        List<String> bindings = events(r.trace()).stream().filter(e -> "DECLARE".equals(e.get("kind")))
                .map(e -> map(map(e.get("data")).get("binding"))).filter(b -> "t".equals(b.get("name")))
                .map(b -> (String) b.get("bindingId")).toList();
        assertEquals(2, bindings.size());
        assertNotEquals(bindings.get(0), bindings.get(1));
    }

    @Test void ac05AliasesShareOneArray() throws Exception {
        Run r = main("ac05", "int[] a = {1, 2};\nint[] b = a;\nb[0] = 9;\nSystem.out.println(a[0]);");
        assertComplete(r, "completed");
        assertEquals("9\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "DECLARE a=#o2 @int[] a = {1, 2}", "DECLARE b=#o2 @int[] b = a",
                "WRITE [0] 1->9 @b[0] = 9", "READ [0]=9 @a[0]");
    }

    @Test void ac17SeveralStepsOnOneLine() throws Exception {
        Run r = main("ac17", "int x = 1; x = x + 2 * x;");
        assertComplete(r, "completed");
        assertEquals(List.of("DECLARE x=1 @int x = 1", "EXPR * 2 1 = 2 @2 * x", "EXPR + 1 2 = 3 @x + 2 * x",
                "ASSIGN x 1->3 @x = x + 2 * x"), steps(r.trace()));
    }

    @Test void j01PostfixUpdatePrecedesDeclaration() throws Exception {
        Run r = main("j01", "int x = 2;\nint y = x++;");
        assertComplete(r, "completed");
        assertEquals(List.of("DECLARE x=2 @int x = 2", "UPDATE x 2->3 =2 @x++", "DECLARE y=2 @int y = x++"), steps(r.trace()));
    }

    @Test void j02IndexSideEffectHappensOnce() throws Exception {
        Run r = main("j02", "int[] a = {4, 1};\nint i = 0;\na[i++] = a[1] + 2;\nSystem.out.println(a[0] + i);");
        assertComplete(r, "completed");
        assertEquals("4\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "UPDATE i 0->1 =0 @i++", "READ [1]=1 @a[1]", "EXPR + 1 2 = 3 @a[1] + 2",
                "WRITE [0] 4->3 @a[i++] = a[1] + 2");
        assertEquals(1, steps(r.trace()).stream().filter(s -> s.startsWith("UPDATE")).count());
    }

    @Test void j03SameNameInSeparateBlocks() throws Exception {
        Run r = main("j03", "{ int x = 1; }\n{ int x = 2; }");
        assertComplete(r, "completed");
        assertEquals(List.of("SCOPE_ENTER", "DECLARE", "SCOPE_EXIT", "SCOPE_ENTER", "DECLARE", "FRAME_END"), kinds(r.trace()));
    }

    @Test void j04RebindingDoesNotMutateArray() throws Exception {
        Run r = main("j04", "int[] a = {2};\nint[] b = a;\nb[0] = 8;\nb = null;\nSystem.out.println(a[0]);");
        assertComplete(r, "completed");
        assertEquals("8\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "WRITE [0] 2->8 @b[0] = 8", "ASSIGN b #o2->null @b = null", "READ [0]=8 @a[0]");
    }

    @Test void j05ShortCircuitInventsNoRead() throws Exception {
        Run r = main("j05", "int[] a = {4};\nint i = -1;\nboolean ok = i >= 0 && a[i] == 4;");
        assertComplete(r, "completed");
        assertEquals(List.of("ALLOCATE [4] @{4}", "DECLARE a=#o2 @int[] a = {4}", "DECLARE i=-1 @int i = -1",
                "EXPR >= -1 0 = false @i >= 0", "EXPR && false _ = false @i >= 0 && a[i] == 4",
                "DECLARE ok=false @boolean ok = i >= 0 && a[i] == 4"), steps(r.trace()));
    }

    @Test void j06FailedWriteAfterUpdate() throws Exception {
        Run r = main("j06", "int[] a = {0};\nint x = 0;\na[2] = ++x;");
        assertComplete(r, "failed");
        assertInOrder(steps(r.trace()), "UPDATE x 0->1 =1 @++x", "EXCEPTION java.lang.ArrayIndexOutOfBoundsException @a[2] = ++x");
        assertFalse(steps(r.trace()).stream().anyMatch(s -> s.startsWith("WRITE")));
        assertEquals("unwind", map(events(r.trace()).get(events(r.trace()).size() - 1).get("data")).get("reason"));
    }

    @Test void j07DivisionByZeroCommitsNothing() throws Exception {
        Run r = main("j07", "int x = 1;\nint y = 0;\nx = x / y;");
        assertComplete(r, "failed");
        assertEquals(List.of("DECLARE x=1 @int x = 1", "DECLARE y=0 @int y = 0",
                "EXCEPTION java.lang.ArithmeticException @x / y"), steps(r.trace()));
    }

    @Test void j08WhileAndForFollowTheirOwnOperations() throws Exception {
        Run w = main("j08-while", """
                int[] a = {3, 1, 2};
                int sum = 0;
                int i = 0;
                while (i < a.length) {
                    sum = sum + a[i];
                    i++;
                }
                System.out.println(sum);""");
        Run f = main("j08-for", """
                int[] a = {3, 1, 2};
                int total = 0;
                for (int k = 0; k < a.length; k++) total = total + a[k];
                System.out.println(total);""");
        assertComplete(w, "completed");
        assertComplete(f, "completed");
        assertEquals("6\n", w.result().stdout());
        assertEquals("6\n", f.result().stdout());
        assertInOrder(steps(w.trace()), "ASSIGN sum 0->3 @sum = sum + a[i]", "ASSIGN sum 3->4 @sum = sum + a[i]",
                "ASSIGN sum 4->6 @sum = sum + a[i]");
        assertInOrder(steps(f.trace()), "ASSIGN total 0->3 @total = total + a[k]", "ASSIGN total 4->6 @total = total + a[k]");
    }

    @Test void stringIdentityAndReferenceComparison() throws Exception {
        Run r = main("strings", "String s = \"hi\";\nString t = s;\nString u = null;\nif (s == t && u == null) System.out.println(s);");
        assertComplete(r, "completed");
        assertEquals("hi\n", r.result().stdout());
        assertEquals(List.of("OBJECT_CAPTURE", "DECLARE"), kinds(r.trace()).subList(0, 2));
        assertInOrder(steps(r.trace()), "DECLARE s=#o2 @String s = \"hi\"", "DECLARE t=#o2 @String t = s",
                "DECLARE u=null @String u = null", "EXPR == #o2 #o2 = true @s == t", "EXPR == null null = true @u == null",
                "EXPR && true true = true @s == t && u == null");
    }

    @Test void booleanConditionsAndNegation() throws Exception {
        Run r = main("conditions", """
                boolean ready = true;
                if (ready) System.out.println(1);
                boolean done = false;
                int n = 0;
                while (!done) {
                    n++;
                    if (n == 2) done = true;
                }
                System.out.println(n);""");
        assertComplete(r, "completed");
        assertEquals("1\n2\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "EXPR condition true = true @ready", "EXPR ! false = true @!done",
                "EXPR == 1 2 = false @n == 2", "EXPR == 2 2 = true @n == 2", "ASSIGN done false->true @done = true",
                "EXPR ! true = false @!done");
    }

    @Test void loopReentryCreatesFreshBindings() throws Exception {
        Run r = main("reentry", """
                int count = 0;
                while (true) {
                    int t = count;
                    count++;
                    if (count == 2) break;
                }
                System.out.println(count);""");
        assertComplete(r, "completed");
        // No recorded operation lies outside the loop body between iterations; the second
        // declaration of t itself proves re-entry, so the old scope must close first.
        List<Integer> declaresOfT = new ArrayList<>();
        List<Map<String, Object>> events = events(r.trace());
        for (int i = 0; i < events.size(); i++) {
            if ("DECLARE".equals(events.get(i).get("kind"))
                    && "t".equals(map(map(events.get(i).get("data")).get("binding")).get("name"))) declaresOfT.add(i);
        }
        assertEquals(2, declaresOfT.size());
        assertTrue(kinds(r.trace()).subList(declaresOfT.get(0), declaresOfT.get(1)).contains("SCOPE_EXIT"));
        assertInOrder(steps(r.trace()), "DECLARE t=0 @int t = count", "DECLARE t=1 @int t = count");
    }

    @Test void j09DifferentlyWrittenSortsNeedNoTemplate() throws Exception {
        Run selection = main("j09-selection", """
                int[] values={2,2,1,3};int n=values.length;
                for(int p=0;p<n-1;p++){int min=p;
                  for(int q=p+1;q<n;q++){if(values[q]<values[min]){min=q;}}
                  if(min!=p){int keep=values[p];values[p]=values[min];values[min]=keep;}}
                for(int p=0;p<n;p++)System.out.println(values[p]);""");
        assertComplete(selection, "completed");
        assertEquals("1\n2\n2\n3\n", selection.result().stdout());
        Run empty = main("j09-empty", """
                int[] none = new int[0];
                for (int i = 0; i < none.length; i++) none[i] = 1;
                System.out.println(none.length);""");
        assertComplete(empty, "completed");
        assertInOrder(steps(empty.trace()), "ALLOCATE [] @new int[0]", "EXPR < 0 0 = false @i < none.length");
    }

    @Test void sourceRangesAreUtf16Offsets() throws Exception {
        String source = "// café 😀 comment\n" + program("int x = 5;");
        Run r = run("utf16", source);
        assertComplete(r, "completed");
        assertEquals(List.of("DECLARE x=5 @int x = 5"), steps(r.trace()));
    }

    @Test void overflowKeepsJavaIntSemantics() throws Exception {
        Run r = main("overflow", "int big = 2147483647;\nint wrapped = big + 1;\nSystem.out.println(wrapped);");
        assertComplete(r, "completed");
        assertEquals("-2147483648\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "EXPR + 2147483647 1 = -2147483648 @big + 1");
    }

    // ---- Part 8c: statements and arrays ------------------------------------------------

    @Test void forEachDoWhileAndLabeledJumps() throws Exception {
        Run r = main("statements-loops", """
                int[] a = {3, 1, 2};
                int sum = 0;
                for (int v : a) sum += v;
                long total = 0;
                for (long w : a) { total += w; }
                int i = 0;
                do { i++; } while (i < 3);
                do i--; while (false);
                int hits = 0;
                outer:
                for (int x = 0; x < 3; x++) {
                    for (int y = 0; y < 3; y++) {
                        if (y == x) continue outer;
                        if (x == 2) break outer;
                        hits++;
                    }
                }
                System.out.println(sum);
                System.out.println(total);
                System.out.println(i);
                System.out.println(hits);""");
        assertComplete(r, "completed");
        assertEquals("6\n6\n2\n1\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertInOrder(steps, "LENGTH 3 @a", "READ [0]=3 @a", "DECLARE v=3 @int v", "EXPR + 0 3 = 3 @sum += v", "ASSIGN sum 0->3 @sum += v",
                "READ [1]=1 @a", "DECLARE v=1 @int v", "READ [2]=2 @a", "DECLARE v=2 @int v", "ASSIGN sum 4->6 @sum += v",
                "READ [0]=3 @a", "DECLARE w=3 @long w", "ASSIGN total 0->3 @total += w",
                "UPDATE i 0->1 =0 @i++", "EXPR < 1 3 = true @i < 3", "UPDATE i 2->3 =2 @i++", "EXPR < 3 3 = false @i < 3",
                "UPDATE i 3->2 =3 @i--",
                "EXPR == 0 0 = true @y == x", "EXPR == 0 1 = false @y == x", "EXPR == 1 2 = false @x == 2", "UPDATE hits 0->1 =0 @hits++",
                "EXPR == 1 1 = true @y == x", "EXPR == 0 2 = false @y == x", "EXPR == 2 2 = true @x == 2");
        assertEquals(2, steps.stream().filter(s -> s.startsWith("LENGTH")).count(), "each for-each reads the length once");
        // Each iteration declares its loop variable afresh in a new scope instance.
        assertTrue(kinds(r.trace()).stream().filter("SCOPE_ENTER"::equals).count() >= 6);
    }

    @Test void switchStatementsAndExpressions() throws Exception {
        Run r = main("statements-switch", """
                int s = 0;
                for (int k = 0; k < 4; k++) {
                    switch (k) {
                        case 0: s += 10; break;
                        case 1:
                        case 2: s += 1;
                        default: s += 100;
                    }
                }
                char c = 'b';
                switch (c) { case 'a', 'b' -> s = s * 2; case 'z' -> s = 0; }
                switch (s) { case 1 -> s = 5; }
                int label = switch ((s + 1) % 3) { case 0 -> 7; case 1 -> { int t = s * 2; yield t; } default -> -1; };
                long big = switch (c) { case 'b' -> 1L << 40; default -> 0; };
                byte small = 3;
                switch (small) { case 3: label++; }
                System.out.println(s);
                System.out.println(label);
                System.out.println(big);""");
        assertComplete(r, "completed");
        assertEquals("624\n1249\n1099511627776\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "SWITCH 0 case @case 0", "ASSIGN s 0->10 @s += 10",
                "SWITCH 1 case @case 1", "ASSIGN s 10->11 @s += 1", "ASSIGN s 11->111 @s += 100",
                "SWITCH 2 case @case 2", "SWITCH 3 default @default", "ASSIGN s 212->312 @s += 100",
                "SWITCH 98 case @case 'a', 'b'", "ASSIGN s 312->624 @s = s * 2",
                "SWITCH 624 none @s",
                "EXPR % 625 3 = 1 @(s + 1) % 3", "SWITCH 1 case @case 1", "EXPR * 624 2 = 1248 @s * 2", "DECLARE t=1248 @int t = s * 2",
                "SWITCH 98 case @case 'b'", "DECLARE big=1099511627776 @long big = switch (c) { case 'b' -> 1L << 40; default -> 0; }",
                "SWITCH 3 case @case 3", "UPDATE label 1248->1249 =1248 @label++");
        assertTrue(steps(r.trace()).stream().anyMatch(s -> s.startsWith("DECLARE label=1248 @int label = switch")));
    }

    @Test void arraysOfEveryElementType() throws Exception {
        Run r = main("arrays-types", """
                double[] d = new double[3];
                d[1] = 2.5;
                d[2] += d[1] * 2;
                char[] word = {'h', 'i'};
                word[0]++;
                boolean[] seen = new boolean[2];
                seen[1] = true;
                if (seen[1]) d[0] = -0.0;
                seen[0] = !seen[1];
                byte[] b = {127};
                b[0]++;
                long[] l = new long[1];
                l[0] = 1L << 40;
                String[] names = {"x", null, "z"};
                names[1] = names[0];
                float[] f = {1.5f};
                f[0] /= 0;
                short[] sh = new short[1];
                sh[0] -= 1;
                System.out.println(d[2]);
                System.out.println(word[0]);
                System.out.println(b[0]);
                System.out.println(names[1] == names[0]);
                System.out.println(f[0]);
                System.out.println(sh[0]);
                System.out.println(l[0] + d[0]);""");
        assertComplete(r, "completed");
        assertEquals("5.0\ni\n-128\ntrue\nInfinity\n-1\n1.099511627776E12\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "ALLOCATE [0.0,0.0,0.0] @new double[3]", "WRITE [1] 0.0->2.5 @d[1] = 2.5",
                "READ [2]=0.0 @d[2]", "READ [1]=2.5 @d[1]", "EXPR * 2.5 2.0 = 5.0 @d[1] * 2", "EXPR + 0.0 5.0 = 5.0 @d[2] += d[1] * 2",
                "WRITE [2] 0.0->5.0 @d[2] += d[1] * 2",
                "ALLOCATE [104,105] @{'h', 'i'}", "READ [0]=104 @word[0]", "EXPR + 104 1 = 105 @word[0]++", "WRITE [0] 104->105 @word[0]++",
                "ALLOCATE [false,false] @new boolean[2]", "WRITE [1] false->true @seen[1] = true",
                "READ [1]=true @seen[1]", "EXPR condition true = true @seen[1]", "WRITE [0] 0.0->-0.0 @d[0] = -0.0",
                "WRITE [0] false->false @seen[0] = !seen[1]",
                "ALLOCATE [127] @{127}", "EXPR + 127 1 = 128 @b[0]++", "WRITE [0] 127->-128 @b[0]++",
                "ALLOCATE [0] @new long[1]", "WRITE [0] 0->1099511627776 @l[0] = 1L << 40",
                "READ [0]=1.5 @f[0]", "EXPR / 1.5 0.0 = Infinity @f[0] /= 0", "WRITE [0] 1.5->Infinity @f[0] /= 0",
                "EXPR - 0 1 = -1 @sh[0] -= 1", "WRITE [0] 0->-1 @sh[0] -= 1");
        assertTrue(steps(r.trace()).stream().anyMatch(s -> s.matches("ALLOCATE \\[#o\\d+,null,#o\\d+] @\\{\"x\", null, \"z\"}")));
        assertTrue(steps(r.trace()).stream().anyMatch(s -> s.matches("WRITE \\[1] null->#o\\d+ @names\\[1] = names\\[0]")));
    }

    @Test void multiDimensionalArraysShareRows() throws Exception {
        Run r = main("arrays-nested", """
                int[][] grid = new int[2][3];
                grid[1][2] = 7;
                int[][] tri = {{1}, {2, 3}};
                tri[0] = tri[1];
                tri[0][0] = 9;
                int[][][] cube = new int[2][2][2];
                cube[1][1][1] = tri[1][0];
                int[][] jag = new int[2][];
                jag[0] = new int[]{4, 5};
                int total = 0;
                for (int[] row : grid) for (int v : row) total += v;
                char[][] board = {{'x', 'o'}, {'o', 'x'}};
                System.out.println(total);
                System.out.println(tri[1][0]);
                System.out.println(cube[1][1][1]);
                System.out.println(jag[1] == null);
                System.out.println(board[1][0]);""");
        assertComplete(r, "completed");
        assertEquals("7\n9\n9\ntrue\no\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertTrue(steps.get(0).matches("ALLOCATE \\[#o\\d+,#o\\d+] @new int\\[2]\\[3]"), steps.get(0));
        assertInOrder(steps, "WRITE [2] 0->7 @grid[1][2] = 7", "ALLOCATE [1] @{1}", "ALLOCATE [2,3] @{2, 3}",
                "WRITE [0] 2->9 @tri[0][0] = 9", "READ [0]=9 @tri[1][0]", "WRITE [1] 0->9 @cube[1][1][1] = tri[1][0]",
                "ALLOCATE [null,null] @new int[2][]", "ALLOCATE [4,5] @new int[]{4, 5}", "ASSIGN total 0->7 @total += v");
        // Inner arrays of a sized creation are captured as bookkeeping inside its step: 2 rows, then 2 + 4 for the cube.
        assertEquals(8, kinds(r.trace()).stream().filter("OBJECT_CAPTURE"::equals).count());
    }

    @Test void uncapturedStatementsRevealNewRowsAndStrings() throws Exception {
        Run r = main("arrays-uncaptured", """
                int[][] g = {{1, 2}, {3, 4}};
                String[] s = {"a"};
                g[0] = java.util.Arrays.copyOf(g[1], 3);
                s[0] = Integer.toString(42);
                int x = g[0][2];
                System.out.println(x);
                System.out.println(s[0]);""");
        assertComplete(r, "completed");
        assertEquals("0\n42\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertTrue(steps.stream().anyMatch(s -> s.matches("UNCAPTURED #o\\d+=\\[#o\\d+,#o\\d+] @g\\[0] = .*")), String.join("\n", steps));
        assertTrue(steps.stream().anyMatch(s -> s.matches("UNCAPTURED #o\\d+=\\[#o\\d+] @s\\[0] = .*")), String.join("\n", steps));
        assertInOrder(steps, "READ [2]=0 @g[0][2]", "DECLARE x=0 @int x = g[0][2]");
    }

    @Test void jumpsAndUncapturedStatementsInsideSwitchesAndLoops() throws Exception {
        Run r = main("statements-corners", """
                int[] a = {5, 6, 7};
                int found = -1;
                for (int v : a) { if (v == 6) { found = v; break; } }
                int n = 0;
                for (int k = 0; k < 3; k++) {
                    switch (k) {
                        case 0: continue;
                        case 1: n = Integer.max(n, 9); break;
                        default: n++;
                    }
                }
                int m = switch (n) { case 10 -> { int q = StrictMath.abs(-3); yield q; } default -> 0; };
                int p = 1 + switch (m) { case 3 -> 4; default -> 5; };
                System.out.println(found);
                System.out.println(n);
                System.out.println(m);
                System.out.println(p);""");
        assertComplete(r, "completed");
        assertEquals("6\n10\n3\n5\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertInOrder(steps, "READ [1]=6 @a", "DECLARE v=6 @int v", "EXPR == 6 6 = true @v == 6", "ASSIGN found -1->6 @found = v",
                "SWITCH 0 case @case 0", "SWITCH 1 case @case 1", "UNCAPTURED n=? @n = Integer.max(n, 9);",
                "SWITCH 2 default @default", "UPDATE n ?->10 =9 @n++",
                "SWITCH 10 case @case 10", "UNCAPTURED @int q = StrictMath.abs(-3);", "UNCAPTURED @yield q;",
                "DECLARE m=3 @int m = switch (n) { case 10 -> { int q = StrictMath.abs(-3); yield q; } default -> 0; }",
                "UNCAPTURED @int p = 1 + switch (m) { case 3 -> 4; default -> 5; };");
        assertFalse(steps.stream().anyMatch(s -> s.startsWith("READ [2]")), "break leaves the for-each before its last element");
    }

    @Test void declarationWithoutInitializerBeforeASwitchIsDeclaredBeforeItsStep() throws Exception {
        // Found by the 8c browser check: the case assignments were planned before the switch
        // statement finished, so the waiting declaration attached to the first case only.
        Run r = main("switch-assigns-declared", """
                int total = 18;
                int kind;
                switch (total) {
                    case 0: kind = 0; break;
                    default: kind = 2;
                }
                System.out.println(kind);""");
        assertComplete(r, "completed");
        assertEquals("2\n", r.result().stdout());
        assertEquals(List.of("DECLARE total=18 @int total = 18", "DECLARE kind=_ @int kind", "SWITCH 18 default @default",
                "ASSIGN kind _->2 @kind = 2"), steps(r.trace()));
    }

    @Test void arrayFailuresMatchTheOriginal() throws Exception {
        Run bounds = main("arrays-bounds", "double[] d = new double[2];\nd[2] = 1.0;");
        assertComplete(bounds, "failed");
        assertInOrder(steps(bounds.trace()), "EXCEPTION java.lang.ArrayIndexOutOfBoundsException @d[2] = 1.0");
        Run negative = main("arrays-negative", "int k = -1;\nString[] s = new String[k];");
        assertComplete(negative, "failed");
        assertInOrder(steps(negative.trace()), "EXCEPTION java.lang.NegativeArraySizeException @new String[k]");
        Run multi = main("arrays-negative-multi", "int k = -2;\nint[][] g = new int[3][k];");
        assertComplete(multi, "failed");
        assertInOrder(steps(multi.trace()), "EXCEPTION java.lang.NegativeArraySizeException @new int[3][k]");
        Run chars = main("arrays-char-bounds", "char[] c = new char[1];\nchar x = c[1];");
        assertComplete(chars, "failed");
        assertInOrder(steps(chars.trace()), "EXCEPTION java.lang.ArrayIndexOutOfBoundsException @c[1]");
    }

    @Test void switchAndForEachOutsideCoverageRunUncaptured() throws Exception {
        Run r = main("statements-uncaptured", """
                String key = "b";
                int n = 0;
                switch (key) { case "a" -> n = 1; case "b" -> n = 2; }
                for (Integer v : java.util.List.of(1, 2)) n += v;
                switch (n) { }
                int[] a = {1};
                for (int v : a) n += v;
                System.out.println(n);""");
        assertComplete(r, "completed");
        assertEquals("6\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertInOrder(steps, "UNCAPTURED n=? @switch (key) { case \"a\" -> n = 1; case \"b\" -> n = 2; }",
                "UNCAPTURED n=? @for (Integer v : java.util.List.of(1, 2)) n += v;", "UNCAPTURED @switch (n) { }",
                "READ [0]=1 @a", "ASSIGN n ?->6 @n += v");
    }

    // ---- Part 8d: static methods and recursion --------------------------------------------

    private static final String METHODS = """
            public class Main {
                static int factorial(int n) {
                    if (n <= 1) return 1;
                    return n * factorial(n - 1);
                }
                static void swap(int[] a, int i, int j) {
                    int t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                }
                static boolean even(int n) { return n % 2 == 0; }
                static double half(long x) { return x / 2.0; }
                static int len(int[] a) { return a.length; }
                static int len(String[] s) { return s.length; }
                static void noop() { }
                public static void main(String[] args) {
                    int f = factorial(3);
                    int[] a = {3, 1, 2};
                    swap(a, 0, 2);
                    if (even(f)) f++;
                    factorial(1);
                    double h = half(5L);
                    noop();
                    int n = len(a) + len(new String[]{"x"});
                    System.out.println(f);
                    System.out.println(a[0]);
                    System.out.println(h);
                    System.out.println(n);
                }
            }
            """;

    @Test void callsAndRecursionHaveTheirOwnFrames() throws Exception {
        Run r = run("methods-recursion", METHODS);
        assertComplete(r, "completed");
        assertEquals("7\n2\n2.5\n4\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertInOrder(steps, "CALL Main.factorial(int):int n=3 @factorial(3)", "EXPR <= 3 1 = false @n <= 1", "EXPR - 3 1 = 2 @n - 1",
                "CALL Main.factorial(int):int n=2 @factorial(n - 1)", "CALL Main.factorial(int):int n=1 @factorial(n - 1)",
                "EXPR <= 1 1 = true @n <= 1", "RETURN 1 @return 1;", "EXPR * 2 1 = 2 @n * factorial(n - 1)",
                "RETURN 2 @return n * factorial(n - 1);", "EXPR * 3 2 = 6 @n * factorial(n - 1)", "RETURN 6 @return n * factorial(n - 1);",
                "DECLARE f=6 @int f = factorial(3)",
                "CALL Main.swap(int[],int,int):void a=#o2 i=0 j=2 @swap(a, 0, 2)", "READ [0]=3 @a[i]", "DECLARE t=3 @int t = a[i]",
                "WRITE [2] 2->3 @a[j] = t", "RETURN void implicit @}",
                "CALL Main.even(int):boolean n=6 @even(f)", "EXPR % 6 2 = 0 @n % 2", "EXPR == 0 0 = true @n % 2 == 0", "RETURN true @return n % 2 == 0;",
                "EXPR condition true = true @even(f)", "UPDATE f 6->7 =6 @f++",
                "CALL Main.factorial(int):int n=1 @factorial(1)", "RETURN 1 @return 1;",
                "CALL Main.half(long):double x=5 @half(5L)", "EXPR / 5.0 2.0 = 2.5 @x / 2.0", "RETURN 2.5 @return x / 2.0;",
                "DECLARE h=2.5 @double h = half(5L)", "CALL Main.noop():void @noop()", "RETURN void implicit @}",
                "CALL Main.len(int[]):int a=#o2 @len(a)");
        assertTrue(steps.stream().anyMatch(s -> s.matches("CALL Main\\.len\\(java\\.lang\\.String\\[]\\):int s=#o\\d+ @len\\(new String\\[]\\{\"x\"}\\)")),
                "overloads are distinct methods");
        // Each invocation is a distinct frame whose caller is the frame that called it.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = events(r.trace()).stream().filter(e -> "CALL".equals(e.get("kind")))
                .map(e -> map(map(e.get("data")).get("frame"))).toList();
        assertEquals(calls.size(), calls.stream().map(f -> f.get("frameId")).distinct().count());
        assertEquals("f1", calls.get(1).get("callerFrameId"));
        assertEquals("f2", calls.get(2).get("callerFrameId"));
    }

    @Test void helpersCalledFromUncapturedCodeRunSilently() throws Exception {
        Run r = run("methods-silent", """
                public class Main {
                    static int square(int v) { return v * v; }
                    static int counter;
                    static void tick() { counter++; }
                    public static void main(String[] args) {
                        int[] a = {1, 2, 3};
                        int total = 0;
                        for (int i = 0; i < a.length; i++) total += square(a[i]);
                        System.out.println(String.format(java.util.Locale.ROOT, "total = %d", square(total))); // Part 12b: no Locale overload
                        int big = Integer.max(square(2), 1);
                        tick();
                        System.out.println(total + big);
                    }
                }
                """);
        assertComplete(r, "completed");
        assertEquals("total = 196\n18\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertEquals(3, steps.stream().filter(s -> s.startsWith("CALL Main.square")).count(), String.join("\n", steps));
        assertInOrder(steps, "ASSIGN total 5->14 @total += square(a[i])", "UNCAPTURED @System.out.println(String.format(java.util.Locale.ROOT, \"total = %d\", square(total)));",
                "UNCAPTURED @int big = Integer.max(square(2), 1);", "CALL Main.tick():void @tick()", "UNCAPTURED @counter++;",
                "RETURN void implicit @}");
    }

    @Test void returnsInsideUncapturedStatementsAndFailuresInCallees() throws Exception {
        Run r = run("methods-returns", """
                public class Main {
                    static int pick(int[] a, int k) {
                        for (int v : a) if (v > k) return v;
                        return Integer.max(k, 0);
                    }
                    static int boom(int x) { return 10 / x; }
                    public static void main(String[] args) {
                        int[] a = {1, 5};
                        int p = pick(a, 2);
                        int q = pick(a, 9);
                        System.out.println(p + q);
                        int z = boom(0);
                    }
                }
                """);
        assertComplete(r, "failed");
        assertEquals("14\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "EXPR > 5 2 = true @v > k", "RETURN 5 @return v;", "DECLARE p=5 @int p = pick(a, 2)",
                "UNCAPTURED @return Integer.max(k, 0);", "RETURN 9 @return Integer.max(k, 0);", "DECLARE q=9 @int q = pick(a, 9)",
                "CALL Main.boom(int):int x=0 @boom(0)", "EXCEPTION java.lang.ArithmeticException @10 / x");
        List<Map<String, Object>> events = events(r.trace());
        List<Object> ends = events.stream().filter(e -> "FRAME_END".equals(e.get("kind"))).map(e -> map(e.get("data")).get("frameId")).toList();
        assertEquals(2, ends.size(), "the failing helper and main both unwind");
        assertEquals("f0", ends.get(1));
    }

    @Test void callDepthLimitStopsAtSixtyFourFrames() throws Exception {
        String program = "public class Main {\n    static int down(int n) { return n == 0 ? 0 : down(n - 1); }\n"
                + "    public static void main(String[] args) {\n        int d = down(%d);\n        System.out.println(d);\n    }\n}\n";
        Run fits = run("methods-depth-fits", program.formatted(62)); // main plus 63 helper frames
        assertComplete(fits, "completed");
        Run over = run("methods-depth-over", program.formatted(63));
        assertEquals(86, over.result().exit());
        assertEquals("limited", status(over.trace(), "execution"));
        assertEquals("partial", status(over.trace(), "capture"));
        assertTrue(over.json().contains("CALL_DEPTH_LIMIT"));
        assertEquals(63, steps(over.trace()).stream().filter(s -> s.startsWith("CALL")).count());
    }

    // ---- Part 8e: objects and fields ------------------------------------------------------

    @Test void linkedListAndTreeObjects() throws Exception {
        Run r = run("objects-list-tree", """
                public class Main {
                    static class Node {
                        int val;
                        Node next;
                        Node(int v) { val = v; }
                    }
                    static class Tree {
                        int key;
                        Tree left, right;
                        Tree(int k) { key = k; }
                        void insert(int k) {
                            if (k < key) { if (left == null) left = new Tree(k); else left.insert(k); }
                            else { if (right == null) right = new Tree(k); else right.insert(k); }
                        }
                        int size() { return 1 + (left == null ? 0 : left.size()) + (right == null ? 0 : right.size()); }
                    }
                    public static void main(String[] args) {
                        Node head = new Node(1);
                        head.next = new Node(2);
                        head.next.next = new Node(3);
                        int sum = 0;
                        for (Node n = head; n != null; n = n.next) sum += n.val;
                        Tree root = new Tree(5);
                        root.insert(3);
                        root.insert(8);
                        root.insert(4);
                        System.out.println(sum);
                        System.out.println(root.size());
                        System.out.println(root.left.right.key);
                    }
                }
                """);
        assertComplete(r, "completed");
        assertEquals("6\n4\n4\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertInOrder(steps, "ALLOCATE Main.Node{val=0,next=null} @new Node(1)", "CALL Main.Node.<init>(int):void this=#o2 v=1 @new Node(1)",
                "FWRITE val 0->1 @val = v", "RETURN void implicit @}", "DECLARE head=#o2 @Node head = new Node(1)",
                "ALLOCATE Main.Node{val=0,next=null} @new Node(2)", "FWRITE next null->#o3 @head.next = new Node(2)",
                "FREAD next=#o3 @head.next", "FWRITE next null->#o4 @head.next.next = new Node(3)",
                "FREAD val=1 @n.val", "FREAD next=#o3 @n.next", "ASSIGN n #o2->#o3 @n = n.next", "ASSIGN sum 3->6 @sum += n.val",
                "CALL Main.Tree.insert(int):void this=#o5 k=3 @root.insert(3)", "FREAD key=5 @key", "EXPR < 3 5 = true @k < key",
                "FREAD left=null @left", "ALLOCATE Main.Tree{key=0,left=null,right=null} @new Tree(k)", "FWRITE left null->#o6 @left = new Tree(k)",
                "CALL Main.Tree.insert(int):void this=#o6 k=4 @left.insert(k)", "CALL Main.Tree.size():int this=#o5 @root.size()");
    }

    @Test void fieldsOfEveryTypeWithInitializers() throws Exception {
        Run r = run("objects-fields", """
                public class Main {
                    static class Box {
                        int count;
                        long total;
                        double avg;
                        boolean open;
                        char tag;
                        String name;
                        int[] data = new int[3];
                        Box self;
                    }
                    public static void main(String[] args) {
                        Box b = new Box();
                        b.count++;
                        b.count += 5;
                        b.total = 1L << 33;
                        b.avg = b.total / 2.0;
                        b.open = true;
                        if (b.open) b.tag = 'x';
                        b.name = "box";
                        b.data[1] = 4;
                        b.self = b;
                        System.out.println(b.count);
                        System.out.println(b.self.avg);
                        System.out.println(b.tag);
                        System.out.println(b.name == b.self.name);
                    }
                }
                """);
        assertComplete(r, "completed");
        assertEquals("6\n4.294967296E9\nx\ntrue\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "ALLOCATE Main.Box{count=0,total=0,avg=0.0,open=false,tag=0,name=null,data=null,self=null} @new Box()",
                "CALL Main.Box.<init>():void this=#o2 @new Box()", "ALLOCATE [0,0,0] @new int[3]", "FWRITE data null->#o3 @int[] data = new int[3]",
                "RETURN void implicit @Box", "DECLARE b=#o2 @Box b = new Box()",
                "FREAD count=0 @b.count", "EXPR + 0 1 = 1 @b.count++", "FWRITE count 0->1 @b.count++",
                "FREAD count=1 @b.count", "EXPR + 1 5 = 6 @b.count += 5", "FWRITE count 1->6 @b.count += 5",
                "FWRITE total 0->8589934592 @b.total = 1L << 33", "FWRITE avg 0.0->4.294967296E9 @b.avg = b.total / 2.0",
                "FREAD open=true @b.open", "EXPR condition true = true @b.open", "FWRITE tag 0->120 @b.tag = 'x'",
                "FREAD data=#o3 @b.data", "WRITE [1] 0->4 @b.data[1] = 4", "FWRITE self null->#o2 @b.self = b",
                "FREAD name=#o4 @b.self.name", "EXPR == #o4 #o4 = true @b.name == b.self.name");
    }

    @Test void silentConstructorsAndObjectsFromUncapturedCodeWithCycles() throws Exception {
        Run r = run("objects-uncaptured", """
                class Pair {
                    int id;
                    Pair other;
                    Pair(int id) { this(id, null); }
                    Pair(int id, Pair o) { this.id = id; other = o; }
                }
                class Util<T> {
                    static Pair make() { Pair x = new Pair(7, null); Pair y = new Pair(8, x); x.other = y; return x; }
                    static void bump(Pair p) { p.id += 10; }
                }
                public class Main {
                    public static void main(String[] args) {
                        Pair a = new Pair(1);
                        Pair b = new Pair(2, a);
                        a.other = b;
                        Pair c = null;
                        c = Util.make();
                        int k = c.other.other.id;
                        Util.bump(b);
                        int j = a.other.id;
                        System.out.println(k);
                        System.out.println(j);
                    }
                }
                """);
        assertComplete(r, "completed");
        assertEquals("7\n12\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertInOrder(steps, "DECLARE a=#o2 @Pair a = new Pair(1)", "CALL Pair.<init>(int,Pair):void this=#o3 id=2 o=#o2 @new Pair(2, a)",
                "FWRITE other null->#o3 @a.other = b", "UNCAPTURED c=? @c = Util.make();", "FREAD other=#o5 @c.other",
                "FREAD id=7 @c.other.other.id", "UNCAPTURED #o3{id=12} @Util.bump(b);", "FREAD id=12 @a.other.id");
        assertFalse(steps.stream().anyMatch(s -> s.contains("@new Pair(1)") && s.startsWith("ALLOCATE")), "the this(...) constructor runs silently");
        List<String> kinds = kinds(r.trace());
        assertTrue(kinds.stream().filter("OBJECT_CAPTURE"::equals).count() >= 3, "a, then c and its partner (a cycle)");
    }

    @Test void nullReceiversFailAndTheObjectLimitStops() throws Exception {
        Run npe = run("objects-null", """
                public class Main {
                    static class Node { int val; Node next; }
                    public static void main(String[] args) {
                        Node n = new Node();
                        int x = n.next.val;
                    }
                }
                """);
        assertComplete(npe, "failed");
        assertInOrder(steps(npe.trace()), "FREAD next=null @n.next", "EXCEPTION java.lang.NullPointerException @n.next.val");
        Run many = run("objects-limit", """
                public class Main {
                    static class Node { int val; Node(int v) { val = v; } }
                    public static void main(String[] args) {
                        Node last = null;
                        for (int i = 0; i < 2000; i++) last = new Node(i);
                        System.out.println(last.val);
                    }
                }
                """);
        assertEquals(86, many.result().exit());
        assertEquals("limited", status(many.trace(), "execution"));
        assertTrue(many.json().contains("TRAVERSAL_LIMIT"));
    }

    // ---- Part 8f: strings and library collections ------------------------------------------

    @Test void stringsConcatenationAndPureLibraryCalls() throws Exception {
        Run r = main("library-strings", """
                String name = "Ada";
                int age = 36;
                String line = "Hi " + name + ", " + age + '!';
                String s = "";
                for (int i = 0; i < 3; i++) s += i;
                int len = line.length();
                char first = line.charAt(0);
                String part = line.substring(3, 6);
                boolean same = part.equals(name);
                int at = line.indexOf(",");
                String up = name.toUpperCase();
                int big = Math.max(age, 40);
                int parsed = Integer.parseInt("12") + 1;
                boolean digit = Character.isDigit(line.charAt(8));
                double root = Math.sqrt(16.0);
                Integer boxed = age;
                int back = boxed + 1;
                Integer none = null;
                if (none == null) none = 3;
                String desc = "v=" + boxed + " " + null + " " + 1.5 + " " + true + none;
                System.out.println(line);
                System.out.println(s);
                System.out.println(len + " " + first + " " + part + " " + same + " " + at + " " + up);
                System.out.println(big + parsed + back);
                System.out.println(digit + " " + root);
                System.out.println(desc);""");
        assertComplete(r, "completed");
        assertEquals("Hi Ada, 36!\n012\n11 H Ada true 6 ADA\n90\ntrue 4.0\nv=36 null 1.5 true3\n", r.result().stdout());
        assertInOrder(textSteps(r.trace()), "EXPR + \"Hi \" \"Ada\" \", \" 36 33 = \"Hi Ada, 36!\" @\"Hi \" + name + \", \" + age + '!'",
                "EXPR + \"\" 0 = \"0\" @s += i", "ASSIGN s \"\"->\"0\" @s += i", "EXPR + \"01\" 2 = \"012\" @s += i",
                "LIB length() = 11 @line.length()", "LIB charAt(0) = 72 @line.charAt(0)", "LIB substring(3,6) = \"Ada\" @line.substring(3, 6)",
                "LIB equals(\"Ada\") = true @part.equals(name)", "LIB indexOf(\",\") = 6 @line.indexOf(\",\")",
                "LIB toUpperCase() = \"ADA\" @name.toUpperCase()", "LIB max(36,40) = 40 @Math.max(age, 40)",
                "LIB parseInt(\"12\") = 12 @Integer.parseInt(\"12\")", "LIB isDigit(51) = true @Character.isDigit(line.charAt(8))",
                "LIB sqrt(16.0) = 4.0 @Math.sqrt(16.0)", "DECLARE boxed=36 @Integer boxed = age", "EXPR + 36 1 = 37 @boxed + 1",
                "DECLARE none=null @Integer none = null", "EXPR == null null = true @none == null", "ASSIGN none null->3 @none = 3",
                "EXPR + \"v=\" 36 \" \" null \" \" 1.5 \" \" true 3 = \"v=36 null 1.5 true3\" @\"v=\" + boxed + \" \" + null + \" \" + 1.5 + \" \" + true + none");
    }

    @Test void stringBuilderAndCollectionsAreReplayedAndVerified() throws Exception {
        Run r = main("library-collections", """
                StringBuilder sb = new StringBuilder();
                sb.append("ab").append(3).append('c');
                sb.reverse();
                sb.insert(0, 'x');
                sb.setCharAt(1, 'Z');
                String built = sb.toString();
                java.util.List<Integer> xs = new java.util.ArrayList<>();
                xs.add(5);
                xs.add(7);
                xs.add(0, 1);
                int second = xs.get(1);
                xs.set(2, 9);
                int removed = xs.remove(0);
                boolean has = xs.contains(9);
                java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
                stack.push(1);
                stack.push(2);
                int top = stack.pop();
                java.util.Queue<String> q = new java.util.LinkedList<>();
                q.offer("a");
                q.offer("b");
                String head = q.poll();
                java.util.Map<String, Integer> counts = new java.util.HashMap<>();
                String word = "abca";
                for (int i = 0; i < word.length(); i++) {
                    String k = "" + word.charAt(i);
                    counts.put(k, counts.getOrDefault(k, 0) + 1);
                }
                java.util.Set<Integer> seen = new java.util.TreeSet<>();
                seen.add(3);
                seen.add(1);
                seen.add(3);
                java.util.PriorityQueue<Integer> pq = new java.util.PriorityQueue<>();
                pq.add(5);
                pq.add(1);
                pq.add(3);
                int min = pq.poll();
                java.util.Stack<Integer> st = new java.util.Stack<>();
                st.push(4);
                int peek = st.peek();
                System.out.println(built);
                System.out.println(xs.size() + " " + second + " " + removed + " " + has);
                System.out.println(top + " " + head + " " + counts.get("a") + " " + seen.size() + " " + min + " " + peek);""");
        assertComplete(r, "completed");
        assertEquals("xZ3ba\n2 5 1 true\n2 a 2 2 1 4\n", r.result().stdout());
        assertInOrder(textSteps(r.trace()), "ALLOCATE \"\" @new StringBuilder()",
                "LIB append(\"ab\") = #o2 -> \"ab\" @sb.append(\"ab\")", "LIB append(3) = #o2 -> \"ab3\" @sb.append(\"ab\").append(3)",
                "LIB reverse() = #o2 -> \"c3ba\" @sb.reverse()", "LIB insert(0,120) = #o2 -> \"xc3ba\" @sb.insert(0, 'x')",
                "LIB setCharAt(1,90) -> \"xZ3ba\" @sb.setCharAt(1, 'Z')", "LIB toString() = \"xZ3ba\" @sb.toString()",
                "ALLOCATE [] @new java.util.ArrayList<>()", "LIB add(5) = true -> [5] @xs.add(5)", "LIB add(0,1) -> [1,5,7] @xs.add(0, 1)",
                "LIB get(1) = 5 @xs.get(1)", "DECLARE second=5 @int second = xs.get(1)", "LIB set(2,9) = 7 -> [1,5,9] @xs.set(2, 9)",
                "LIB remove(0) = 1 -> [5,9] @xs.remove(0)", "LIB contains(9) = true @xs.contains(9)",
                "LIB push(2) -> [2,1] @stack.push(2)", "LIB pop() = 2 -> [1] @stack.pop()",
                "LIB poll() = \"a\" -> [\"b\"] @q.poll()",
                "LIB put(\"a\",2) = 1 -> {\"a\"=2,\"b\"=1,\"c\"=1} @counts.put(k, counts.getOrDefault(k, 0) + 1)",
                "LIB add(3) = false @seen.add(3)", "LIB add(1) = true -> [1,5] @pq.add(1)", "LIB add(3) = true -> [1,3,5] @pq.add(3)", "LIB poll() = 1 -> [3,5] @pq.poll()",
                "LIB peek() = 4 @st.peek()");
    }

    @Test void nestedCollectionsUserObjectsAndCollectionsFromUncapturedCode() throws Exception {
        Run r = run("library-nested", """
                public class Main {
                    static class Node { int val; Node(int v) { val = v; } }
                    public static void main(String[] args) {
                        java.util.List<Integer> xs = null;
                        xs = new java.util.ArrayList<>(java.util.List.of(4, 5));
                        xs.add(6);
                        java.util.Map<String, java.util.List<Integer>> groups = new java.util.TreeMap<>();
                        groups.put("a", xs);
                        int size = groups.get("a").size();
                        java.util.List<Node> nodes = new java.util.LinkedList<>();
                        nodes.add(new Node(7));
                        int v = nodes.get(0).val;
                        System.out.println(size + v);
                    }
                }
                """);
        assertComplete(r, "completed");
        assertEquals("10\n", r.result().stdout());
        List<String> steps = textSteps(r.trace());
        assertInOrder(steps, "UNCAPTURED xs=? @xs = new java.util.ArrayList<>(java.util.List.of(4, 5));", "LIB add(6) = true -> [4,5,6] @xs.add(6)",
                "LIB size() = 3 @groups.get(\"a\").size()", "FREAD val=7 @nodes.get(0).val");
        assertTrue(steps.stream().anyMatch(s -> s.matches("LIB put\\(\"a\",#o\\d+\\) = null -> \\{\"a\"=#o\\d+} @groups.put\\(\"a\", xs\\)")), String.join("\n", steps));
        assertTrue(kinds(r.trace()).contains("OBJECT_CAPTURE"), "the list made by uncaptured code is captured on first use");
    }

    @Test void libraryFailuresAndUncapturedChangesToCollections() throws Exception {
        Run failure = main("library-failure", "java.util.List<Integer> e = new java.util.ArrayList<>();\nint x = e.get(0);");
        assertComplete(failure, "failed");
        assertInOrder(steps(failure.trace()), "EXCEPTION java.lang.IndexOutOfBoundsException @e.get(0)");
        Run empty = main("library-empty-pop", "java.util.Deque<Integer> d = new java.util.ArrayDeque<>();\nd.push(1);\nd.pop();\nd.pop();");
        assertComplete(empty, "failed");
        assertInOrder(steps(empty.trace()), "LIB pop() = 1 -> [] @d.pop()", "EXCEPTION java.util.NoSuchElementException @d.pop()");
        Run region = main("library-region", """
                java.util.List<Integer> xs = new java.util.ArrayList<>();
                xs.add(1);
                java.util.Collections.addAll(xs, 3, 2);
                java.util.Collections.reverse(xs);
                int n = xs.size();
                int last = xs.get(2);
                System.out.println(n + last);""");
        assertComplete(region, "completed");
        assertEquals("4\n", region.result().stdout());
        assertInOrder(textSteps(region.trace()), "LIB add(1) = true -> [1] @xs.add(1)", "UNCAPTURED @java.util.Collections.addAll(xs, 3, 2);",
                "UNCAPTURED @java.util.Collections.reverse(xs);", "LIB size() = 3 @xs.size()", "LIB get(2) = 1 @xs.get(2)");
        @SuppressWarnings("unchecked")
        List<Object> reread = events(region.trace()).stream().filter(e -> "UNCAPTURED".equals(e.get("kind")))
                .flatMap(e -> ((List<Object>) map(e.get("data")).get("collections")).stream()).toList();
        assertEquals(2, reread.size(), "both uncaptured statements changed the list: " + reread);
    }

    // ---- Part 10d: broader problems --------------------------------------------------------------

    private static List<String> uncaptured(Run run) { return steps(run.trace()).stream().filter(s -> s.startsWith("UNCAPTURED")).toList(); }

    @Test void arrayUtilitiesStringArraysAndLibrarySortsAreOneStepEach() throws Exception {
        Run r = main("array-utilities", """
                int[] a = {3, 1, 2};
                java.util.Arrays.sort(a);
                String s = java.util.Arrays.toString(a);
                char[] c = "cba".toCharArray();
                java.util.Arrays.sort(c, 1, 3);
                boolean same = java.util.Arrays.equals(c, "cab".toCharArray());
                String[] w = "y x y".split(" ");
                java.util.Arrays.sort(w);
                java.util.List<String> xs = new java.util.ArrayList<>();
                xs.add("b"); xs.add("a");
                java.util.Collections.sort(xs);
                System.out.println(s + same + w[0]);
                System.out.println(xs);""");
        assertComplete(r, "completed");
        assertEquals("[1, 2, 3]truex\n[a, b]\n", r.result().stdout());
        assertEquals(List.of(), uncaptured(r));
        assertInOrder(textSteps(r.trace()), "ALLOCATE [3,1,2] @{3, 1, 2}", "LIB sort(#o2) -> [1,2,3] @java.util.Arrays.sort(a)",
                "LIB toString(#o2) = \"[1, 2, 3]\" @java.util.Arrays.toString(a)",
                "LIB toCharArray() = #o5 @\"cba\".toCharArray()", "LIB sort(#o5,1,3) -> [99,97,98] @java.util.Arrays.sort(c, 1, 3)",
                "LIB equals(#o5,#o7) = true @java.util.Arrays.equals(c, \"cab\".toCharArray())",
                "LIB split(\" \") = #o10 @\"y x y\".split(\" \")", "LIB sort(#o10) -> [\"x\",\"y\",\"y\"] @java.util.Arrays.sort(w)",
                "LIB sort(#o14) -> [\"a\",\"b\"] @java.util.Collections.sort(xs)");
    }

    @Test void arraysFillAndMathPowAreOneStepEach() throws Exception { // Part 12a
        Run r = main("fill-and-pow", """
                int[] a = new int[4];
                java.util.Arrays.fill(a, 7);
                java.util.Arrays.fill(a, 1, 3, 2);
                char[] c = new char[2];
                java.util.Arrays.fill(c, 'x');
                String[] w = new String[2];
                java.util.Arrays.fill(w, "hi");
                double[][] g = new double[2][2];
                java.util.Arrays.fill(g[1], 1.5);
                double p = Math.pow(2, 10);
                double q = Math.pow(a[1], 0.5);
                System.out.println(a[1] + " " + c[0] + w[1] + g[1][0] + " " + p + " " + q);""");
        assertComplete(r, "completed");
        assertEquals("2 xhi1.5 1024.0 1.4142135623730951\n", r.result().stdout());
        assertEquals(List.of(), uncaptured(r));
        assertInOrder(textSteps(r.trace()), "LIB fill(#o2,7) -> [7,7,7,7] @java.util.Arrays.fill(a, 7)",
                "LIB fill(#o2,1,3,2) -> [7,2,2,7] @java.util.Arrays.fill(a, 1, 3, 2)",
                "LIB fill(#o3,120) -> [120,120] @java.util.Arrays.fill(c, 'x')",
                "LIB pow(2.0,10.0) = 1024.0 @Math.pow(2, 10)", "LIB pow(2.0,0.5) = 1.4142135623730951 @Math.pow(a[1], 0.5)");
        assertTrue(textSteps(r.trace()).stream().anyMatch(s -> s.startsWith("LIB fill(#o4,") && s.endsWith("-> [\"hi\",\"hi\"] @java.util.Arrays.fill(w, \"hi\")")), "string array fill");
        assertTrue(textSteps(r.trace()).stream().anyMatch(s -> s.endsWith("-> [1.5,1.5] @java.util.Arrays.fill(g[1], 1.5)")), "fill of a grid row");
    }

    @Test void stringFormatAndStreamOfRecordTheirArgumentsOneByOne() throws Exception { // Part 12b
        Run r = main("format-and-stream-of", """
                int n = 7;
                String name = "Ann";
                String s = String.format("%s has %d (%.2f)%n", name, n, n / 3.0);
                String t = String.format("100%%");
                java.util.List<Integer> xs = java.util.stream.Stream.of(3, 1, 2).sorted().toList();
                long c = java.util.stream.Stream.of("a").count();
                System.out.print(s + t + xs + c);""");
        assertComplete(r, "completed");
        assertEquals("Ann has 7 (2.33)\n100%[1, 2, 3]1", r.result().stdout());
        assertEquals(List.of(), uncaptured(r));
        List<String> steps = textSteps(r.trace());
        assertTrue(steps.stream().anyMatch(x -> x.startsWith("LIB format(\"%s has %d (%.2f)%n\",\"Ann\",7,2.3333333333333335) = \"Ann has 7 (2.33)\n\"")), String.join("\n", steps));
        assertTrue(steps.stream().anyMatch(x -> x.startsWith("LIB format(\"100%%\") = \"100%\"")), String.join("\n", steps));
        assertTrue(steps.stream().anyMatch(x -> x.startsWith("LIB of(3,1,2) = ")), String.join("\n", steps));
        assertTrue(steps.stream().anyMatch(x -> x.startsWith("LIB toList() = ")), String.join("\n", steps));
        assertFalse(steps.stream().anyMatch(x -> x.startsWith("ALLOCATE")), "javac's varargs array is not shown: " + steps);

        // An array passed whole, a user object to format, and the Locale overload stay uncaptured.
        Run u = main("format-uncovered", """
                Object[] values = {1};
                String a = String.format("%s", values);
                String b = String.format(java.util.Locale.ROOT, "%d", 2);""");
        assertComplete(u, "completed");
        assertEquals(3, uncaptured(u).size(), String.join("\n", steps(u.trace()))); // Object[] itself is not traced
    }

    @Test void printingLibraryValuesAndReverseOrderCollections() throws Exception {
        Run r = main("print-and-reverse", """
                java.util.PriorityQueue<Integer> pq = new java.util.PriorityQueue<>(java.util.Collections.reverseOrder());
                pq.add(1); pq.add(5); pq.add(3);
                System.out.println(pq.poll());
                java.util.TreeSet<String> t = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
                t.add("a"); t.add("c"); t.add("b");
                StringBuilder sb = new StringBuilder("x");
                System.out.println(sb);
                System.out.println(t);
                System.out.print(pq.peek());""");
        assertComplete(r, "completed");
        assertEquals("5\nx\n[c, b, a]\n3", r.result().stdout());
        assertEquals(List.of(), uncaptured(r));
        assertInOrder(textSteps(r.trace()), "LIB add(5) = true -> [5,1] @pq.add(5)", "LIB add(3) = true -> [5,3,1] @pq.add(3)",
                "LIB poll() = 5 -> [3,1] @pq.poll()", "LIB add(\"b\") = true -> [\"c\",\"b\",\"a\"] @t.add(\"b\")");
        // A collection ordered by user code cannot be mirrored: a lambda comparator stays uncaptured.
        Run user = main("user-comparator", """
                java.util.PriorityQueue<Integer> q = new java.util.PriorityQueue<>((x, y) -> y - x);
                q.add(2);
                System.out.println(q.peek());""");
        assertEquals("2\n", user.result().stdout());
        assertFalse(uncaptured(user).isEmpty());
    }

    @Test void forEachOverCollectionsAndMapViewsStepsThroughEachElement() throws Exception {
        Run r = main("for-each-collections", """
                java.util.List<Integer> xs = new java.util.ArrayList<>();
                xs.add(3); xs.add(4);
                int sum = 0;
                for (int x : xs) sum += x;
                java.util.Set<String> seen = new java.util.TreeSet<>();
                seen.add("b"); seen.add("a");
                String all = "";
                for (String s : seen) all += s;
                java.util.Map<Character, Integer> m = new java.util.TreeMap<>();
                m.put('x', 1); m.put('y', 2);
                int total = 0;
                for (char k : m.keySet()) total += m.get(k);
                for (int v : m.values()) total += v;
                for (java.util.Map.Entry<Character, Integer> e : m.entrySet()) System.out.println(e.getKey() + "=" + e.getValue());
                java.util.Deque<Integer> d = new java.util.ArrayDeque<>();
                d.push(1); d.push(2);
                for (Integer y : d) System.out.print(y);
                System.out.println(sum + all + total);""");
        assertComplete(r, "completed");
        assertEquals("x=1\ny=2\n217ab6\n", r.result().stdout());
        assertEquals(List.of(), uncaptured(r));
        assertInOrder(textSteps(r.trace()), "LIB next() = 3 @xs", "DECLARE x=3 @int x", "LIB next() = 4 @xs",
                "LIB next() = \"a\" @seen", "DECLARE s=\"a\" @String s", "LIB next() = \"b\" @seen",
                "LIB next() = 120 @m.keySet()", "LIB get(120) = 1 @m.get(k)", "LIB next() = 2 @m.values()");
        List<Map<String, Object>> entries = events(r.trace()).stream().filter(e -> "OBJECT_CAPTURE".equals(e.get("kind")))
                .map(e -> map(map(e.get("data")).get("object"))).filter(o -> "entry".equals(o.get("kind"))).toList();
        assertEquals(2, entries.size(), "each entry is captured once, with its key and value");
        assertEquals("1", map(entries.get(0).get("value")).get("value"));
        // A change to the collection inside the loop fails next() exactly as in Java.
        Run cme = main("for-each-modified", """
                java.util.List<Integer> xs = new java.util.ArrayList<>();
                xs.add(1); xs.add(2);
                for (int x : xs) if (x == 1) xs.add(3);""");
        assertComplete(cme, "failed");
        assertInOrder(steps(cme.trace()), "LIB add(3) = true -> [1,2,3] @xs.add(3)", "EXCEPTION java.util.ConcurrentModificationException @xs");
        // An entry changed through put while shown cannot be followed: capture stops before it would be wrong.
        Run stale = main("for-each-entry-put", """
                java.util.Map<String, Integer> m = new java.util.HashMap<>();
                m.put("a", 1);
                for (java.util.Map.Entry<String, Integer> e : m.entrySet()) m.put(e.getKey(), e.getValue() + 1);
                System.out.println(m.get("a"));""");
        assertEquals("2\n", stale.result().stdout());
        assertEquals("partial", status(stale.trace(), "capture"));
    }

    @Test void lambdasRunByLibraryCallsAreFramesAndTheCallIsReplayedWithTheirResults() throws Exception {
        Run r = main("lambdas", """
                java.util.List<Integer> xs = new java.util.ArrayList<>();
                xs.add(3); xs.add(-1); xs.add(2);
                int[] total = {0};
                xs.forEach(x -> total[0] += x);
                xs.removeIf(x -> x < 0);
                xs.sort((p, q) -> q - p);
                java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
                String[] words = {"b", "a", "b"};
                for (String w : words) counts.merge(w, 1, Integer::sum);
                java.util.Function<Integer, Integer> twice = v -> { int d = v * 2; return d; };
                int four = twice.apply(2);
                System.out.println(total[0] + " " + four);
                System.out.println(xs);
                System.out.println(counts);""".replace("java.util.Function", "java.util.function.Function"));
        assertComplete(r, "completed");
        assertEquals("4 4\n[3, 2]\n{a=1, b=2}\n", r.result().stdout());
        assertEquals(List.of(), uncaptured(r));
        assertInOrder(steps(r.trace()), "CALL Main.lambda@6:12(java.lang.Integer):void total=#o3 x=3 @x -> total[0] += x",
                "WRITE [0] 0->3 @total[0] += x", "RETURN void implicit @total[0] += x", "WRITE [0] 2->4 @total[0] += x",
                "LIB forEach(lambdaat6:12) @xs.forEach(x -> total[0] += x)", "RETURN true @x < 0",
                "LIB removeIf(lambdaat7:13) = true -> [3,2] @xs.removeIf(x -> x < 0)",
                "CALL Main.lambda@8:9(java.lang.Integer,java.lang.Integer):int p=2 q=3 @(p, q) -> q - p", "RETURN 1 @q - p",
                "LIB sort(lambdaat8:9) @xs.sort((p, q) -> q - p)",
                "LIB merge(#o6,1,Integer::sum) = 2 -> {#o7=1,#o6=2} @counts.merge(w, 1, Integer::sum)",
                "DECLARE twice=lambda at 12:55 @java.util.function.Function<Integer, Integer> twice = v -> { int d = v * 2; return d; }",
                "CALL Main.lambda@12:55(java.lang.Integer):java.lang.Integer v=2 @v -> { int d = v * 2; return d; }",
                "DECLARE d=4 @int d = v * 2", "RETURN 4 @return d;", "LIB apply(2) = 4 @twice.apply(2)");
        @SuppressWarnings("unchecked")
        List<Object> vias = events(r.trace()).stream().filter(e -> "CALL".equals(e.get("kind")))
                .map(e -> map(map(e.get("data")).get("frame")).get("via")).toList();
        assertTrue(vias.contains("java.util.List.forEach(java.util.function.Consumer)"), vias.toString());
    }

    @Test void streamsRunTheirLambdasElementByElementInTheTerminalOperation() throws Exception {
        Run r = main("streams", """
                java.util.List<Integer> xs = new java.util.ArrayList<>();
                xs.add(1); xs.add(2); xs.add(4);
                int s = xs.stream().filter(x -> x % 2 == 0).mapToInt(x -> x * x).sum();
                String[] words = {"bb", "a", "cc"};
                java.util.Map<Integer, java.util.List<String>> byLength = java.util.Arrays.stream(words)
                        .collect(java.util.stream.Collectors.groupingBy(String::length));
                java.util.OptionalInt top = java.util.stream.IntStream.rangeClosed(1, 3).map(v -> v * 10).max();
                java.util.stream.Stream<Integer> once = xs.stream();
                long n = once.count();
                System.out.println(s + " " + top.getAsInt() + " " + n);
                System.out.println(byLength);
                once.count();""");
        assertComplete(r, "failed");
        assertEquals("20 30 3\n{1=[a], 2=[bb, cc]}\n", r.result().stdout());
        assertEquals(List.of(), uncaptured(r));
        // Lazy order: each element goes through filter, then (if kept) mapToInt, before the next one.
        List<String> calls = steps(r.trace()).stream().filter(x -> x.startsWith("CALL Main.lambda@5:")).toList();
        assertEquals(List.of("CALL Main.lambda@5:28(java.lang.Integer):boolean x=1 @x -> x % 2 == 0",
                "CALL Main.lambda@5:28(java.lang.Integer):boolean x=2 @x -> x % 2 == 0",
                "CALL Main.lambda@5:54(java.lang.Integer):int x=2 @x -> x * x",
                "CALL Main.lambda@5:28(java.lang.Integer):boolean x=4 @x -> x % 2 == 0",
                "CALL Main.lambda@5:54(java.lang.Integer):int x=4 @x -> x * x"), calls);
        assertInOrder(steps(r.trace()), "LIB sum() = 20 @xs.stream().filter(x -> x % 2 == 0).mapToInt(x -> x * x).sum()",
                "LIB getAsInt() = 30 @top.getAsInt()", "EXCEPTION java.lang.IllegalStateException @once.count()");
        @SuppressWarnings("unchecked")
        List<Object> vias = events(r.trace()).stream().filter(e -> "CALL".equals(e.get("kind")))
                .map(e -> map(map(e.get("data")).get("frame")).get("via")).distinct().toList();
        assertTrue(vias.contains("java.util.stream.IntStream.sum()"), vias.toString());
    }

    @Test void helpersAsMethodReferencesComparatorsAndAThrowingLambda() throws Exception {
        Run r = run("method-references", """
                public class Main {
                    static boolean even(int n) {
                        return n % 2 == 0;
                    }
                    public static void main(String[] args) {
                java.util.List<String> ws = new java.util.ArrayList<>();
                ws.add("ccc"); ws.add("a"); ws.add("bb");
                ws.sort(java.util.Comparator.comparing(String::length));
                java.util.List<Integer> ns = new java.util.ArrayList<>();
                ns.add(4); ns.add(7); ns.add(9);
                ns.removeIf(Main::even);
                java.util.Map<Integer, java.util.List<Integer>> g = new java.util.HashMap<>();
                g.computeIfAbsent(1, k -> new java.util.ArrayList<>()).add(2);
                ns.forEach(System.out::println);
                System.out.println(ws);
                System.out.println(g);
                ns.forEach(n -> { if (n > 8) throw new IllegalStateException("big"); });
                    }
                }
                """);
        // A lambda whose expression body is not covered leaves only its statement uncaptured.
        Run fallback = main("lambda-uncovered", "java.util.List<Integer> xs = new java.util.ArrayList<>();\nxs.add(1);\n"
                + "xs.forEach(x -> System.out.printf(\"%d%n\", x));\nint y = xs.get(0);");
        assertComplete(fallback, "completed");
        assertEquals(List.of("UNCAPTURED @xs.forEach(x -> System.out.printf(\"%d%n\", x));"), uncaptured(fallback));
        assertComplete(r, "failed");
        assertEquals("7\n9\n[a, bb, ccc]\n{1=[2]}\n", r.result().stdout());
        assertEquals(List.of(), uncaptured(r));
        assertInOrder(textSteps(r.trace()), "LIB sort(Comparator.comparing(String::length)) -> [\"a\",\"bb\",\"ccc\"] @ws.sort(java.util.Comparator.comparing(String::length))",
                "CALL Main.even(int):boolean n=4 @ns.removeIf(Main::even)", "RETURN true @return n % 2 == 0;",
                "LIB removeIf(Main::even) = true -> [7,9] @ns.removeIf(Main::even)",
                "EXCEPTION java.lang.IllegalStateException @throw new IllegalStateException(\"big\");",
                "EXCEPTION java.lang.IllegalStateException @ns.forEach(n -> { if (n > 8) throw new IllegalStateException(\"big\"); })");
    }

    // ---- Part 8g: Scanner input ---------------------------------------------------------------

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test void scannerReadsAreStepsReplayedOverTheDeliveredInput() throws Exception {
        String source = """
                import java.util.Scanner;

                public class Main {
                    public static void main(String[] args) {
                        Scanner in = new Scanner(System.in);
                        System.out.print("Enter n: ");
                        int n = in.nextInt();
                        in.nextLine();
                        String name = in.nextLine();
                        double d = in.nextDouble();
                        System.out.println(n * 2);
                        System.out.println("Hi " + name);
                        boolean more = in.hasNextInt();
                        System.out.println(more);
                        System.out.println(d);
                    }
                }
                """;
        Run r = run("scanner-reads", source, utf8("21\nAda Lovelace\n2.5\nx\n"));
        assertComplete(r, "completed");
        assertTrue(r.prepared().analysis().interactive(), "System.in makes the run interactive");
        assertEquals("Enter n: 42\nHi Ada Lovelace\nfalse\n2.5\n", r.result().stdout());
        assertInOrder(textSteps(r.trace()), "LIB nextInt() = 21 @in.nextInt()", "DECLARE n=21 @int n = in.nextInt()",
                "LIB nextLine() = \"\" @in.nextLine()", "LIB nextLine() = \"Ada Lovelace\" @in.nextLine()",
                "LIB nextDouble() = 2.5 @in.nextDouble()", "LIB hasNextInt() = false @in.hasNextInt()");
        assertTrue(r.json().contains("\"kind\":\"input\""), "the Scanner is an input object");
        assertFalse(Tracer.prepare(program("int x = 1;")).analysis().interactive(), "no System.in: not interactive");
    }

    /** Part 8i: BufferedReader over System.in is an input object too; readLine and read are replayed and verified. */
    @Test void bufferedReaderReadsAreStepsReplayedOverTheDeliveredInput() throws Exception {
        Run r = run("reader-reads", """
                import java.io.*;

                public class Main {
                    public static void main(String[] args) throws IOException {
                        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                        String first = in.readLine();
                        int n = Integer.parseInt(first.trim());
                        int c = in.read();
                        String rest = in.readLine();
                        String end = in.readLine();
                        System.out.println(n * 2 + " " + c + " " + rest + " " + end);
                        in.close();
                    }
                }
                """, " 21 \nab\n".getBytes(StandardCharsets.UTF_8));
        assertComplete(r, "completed");
        assertTrue(r.prepared().analysis().interactive());
        assertEquals("42 97 b null\n", r.result().stdout());
        assertInOrder(textSteps(r.trace()), "ALLOCATE BufferedReader @new BufferedReader(new InputStreamReader(System.in))",
                "LIB readLine() = \" 21 \" @in.readLine()", "LIB read() = 97 @in.read()", "LIB readLine() = \"b\" @in.readLine()",
                "LIB readLine() = null @in.readLine()", "LIB close() @in.close()");
        assertTrue(r.json().contains("\"className\":\"java.io.BufferedReader\""));
        // A second reader of standard input is not replayed.
        Run two = run("reader-and-scanner", """
                import java.io.*;
                import java.util.Scanner;

                public class Main {
                    public static void main(String[] args) throws IOException {
                        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                        Scanner sc = new Scanner(System.in);
                        System.out.println(in.readLine());
                    }
                }
                """, "x\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("partial", status(two.trace(), "capture"));
    }

    @Test void invalidTokensAndEndOfInputFollowJava() throws Exception {
        String source = """
                import java.util.Scanner;

                public class Main {
                    public static void main(String[] args) {
                        Scanner in = new Scanner(System.in);
                        int n = in.nextInt();
                    }
                }
                """;
        Run invalid = run("scanner-invalid", source, utf8("abc\n"));
        assertComplete(invalid, "failed");
        assertInOrder(steps(invalid.trace()), "EXCEPTION java.util.InputMismatchException @in.nextInt()");
        Run eof = run("scanner-eof", source, new byte[0]);
        assertComplete(eof, "failed");
        assertInOrder(steps(eof.trace()), "EXCEPTION java.util.NoSuchElementException @in.nextInt()");
        // A transcript that disagrees with what the program read stops capture instead of guessing.
        Tracer.Prepared prepared = Tracer.prepare(source);
        HostRun.Result result = HostRun.run(prepared.classes(), true, temp, utf8("7\n"));
        String json = Tracer.trace(prepared, "test-scanner-forged", result.raw(), result.exit(), utf8("8\n"));
        assertEquals("partial", status(JsonReader.object(json), "capture"));
    }

    // ---- outside coverage -------------------------------------------------------

    // ---- Part 8a: uncaptured statements inside traced programs -----------------------

    // ---- Part 8h: exceptions -------------------------------------------------------------

    /** Ended frames in order, as frameId:reason. */
    private static List<String> frameEnds(Map<String, Object> trace) {
        return events(trace).stream().filter(e -> "FRAME_END".equals(e.get("kind")))
                .map(e -> map(e.get("data")).get("frameId") + ":" + map(e.get("data")).get("reason")).toList();
    }

    @Test void caughtFailureRunsCatchThenFinally() throws Exception {
        Run r = main("exceptions-main", """
                int[] a = {1, 2};
                int total = 0;
                try {
                    total += a[5];
                } catch (ArrayIndexOutOfBoundsException e) {
                    total = -1;
                } finally {
                    total += 10;
                }
                System.out.println(total);""");
        assertComplete(r, "completed");
        assertEquals("9\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "EXCEPTION java.lang.ArrayIndexOutOfBoundsException @a[5]",
                "CATCH e=#o3 @catch (ArrayIndexOutOfBoundsException e)", "ASSIGN total 0->-1 @total = -1", "ASSIGN total -1->9 @total += 10");
        @SuppressWarnings("unchecked")
        Map<String, Object> captured = events(r.trace()).stream().map(e -> map(e.get("data")).get("object")).filter(Map.class::isInstance)
                .map(o -> (Map<String, Object>) o).filter(o -> "exception".equals(o.get("kind"))).findFirst().orElseThrow();
        assertEquals("Index 5 out of bounds for length 2", captured.get("message"));
    }

    @Test void helpersThrowUnwindAndFinallyRunsOnEveryPath() throws Exception {
        Run r = run("exceptions-helpers", """
                public class Main {
                    static class InsufficientFunds extends RuntimeException {
                        InsufficientFunds(String message) { super(message); }
                    }
                    static int withdraw(int balance, int amount) {
                        if (amount > balance) throw new InsufficientFunds("need " + (amount - balance) + " more");
                        return balance - amount;
                    }
                    static int safeWithdraw(int balance, int amount) {
                        try {
                            return withdraw(balance, amount);
                        } finally {
                            System.out.println("checked " + amount);
                        }
                    }
                    public static void main(String[] args) {
                        int balance = 100;
                        balance = safeWithdraw(balance, 30);
                        String status;
                        try {
                            balance = safeWithdraw(balance, 500);
                            status = "ok";
                        } catch (InsufficientFunds e) {
                            status = e.getMessage();
                        }
                        System.out.println(balance + " " + status);
                    }
                }
                """);
        assertComplete(r, "completed");
        assertEquals("checked 30\nchecked 500\n70 need 430 more\n", r.result().stdout());
        List<String> steps = textSteps(r.trace());
        assertInOrder(steps, "ASSIGN balance 100->70 @balance = safeWithdraw(balance, 30)", "CALL Main.withdraw(int,int):int balance=70 amount=500 @withdraw(balance, amount)",
                "EXCEPTION Main$InsufficientFunds @throw new InsufficientFunds(\"need \" + (amount - balance) + \" more\");",
                "EXPR + \"checked \" 500 = \"checked 500\" @\"checked \" + amount",
                "CATCH e=#o7 @catch (InsufficientFunds e)", "LIB getMessage() = \"need 430 more\" @e.getMessage()",
                "ASSIGN status _->\"need 430 more\" @status = e.getMessage()");
        // withdraw and safeWithdraw unwind where the exception leaves them; main returns normally.
        assertEquals(List.of("f4:unwind", "f3:unwind", "f0:main_return"), frameEnds(r.trace()));
    }

    @Test void uncaughtThrowRunsFinallyAndUnwindsEveryFrame() throws Exception {
        Run r = run("exceptions-uncaught", """
                public class Main {
                    static void check(int n) {
                        try {
                            if (n < 0) throw new IllegalArgumentException("negative: " + n);
                        } finally {
                            System.out.println("checked");
                        }
                    }
                    public static void main(String[] args) {
                        check(1);
                        check(-2);
                    }
                }
                """);
        assertComplete(r, "failed");
        assertEquals("checked\nchecked\n", r.result().stdout());
        assertEquals("Uncaught java.lang.IllegalArgumentException.", map(r.trace().get("execution")).get("reason"));
        assertInOrder(steps(r.trace()), "EXCEPTION java.lang.IllegalArgumentException @throw new IllegalArgumentException(\"negative: \" + n);");
        assertEquals(List.of("f2:unwind", "f0:unwind"), frameEnds(r.trace()));
    }

    @Test void jumpsOutOfTryRunFinallyCopiesAndLibraryFailuresAreCaught() throws Exception {
        Run r = main("exceptions-jumps", """
                String[] inputs = {"12", "x", "7"};
                int sum = 0;
                int bad = 0;
                for (int i = 0; i < inputs.length; i++) {
                    try {
                        if (i == 2) break;
                        sum += Integer.parseInt(inputs[i]);
                    } catch (NumberFormatException | ArithmeticException e) {
                        bad++;
                    } finally {
                        sum += 100;
                    }
                }
                int count = 0;
                int fin = 0;
                outer:
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        try {
                            if (j == 1) continue outer;
                            count++;
                        } finally {
                            fin++;
                        }
                    }
                }
                System.out.println(sum + " " + bad + " " + count + " " + fin);
                try {
                    return;
                } finally {
                    System.out.println("bye");
                }""");
        assertComplete(r, "completed");
        assertEquals("312 1 3 6\nbye\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertInOrder(steps, "EXCEPTION java.lang.NumberFormatException @Integer.parseInt(inputs[i])",
                "CATCH e=#o6 @catch (NumberFormatException | ArithmeticException e)", "UPDATE bad 0->1 =0 @bad++",
                "ASSIGN sum 112->212 @sum += 100", "EXPR == 2 2 = true @i == 2", "ASSIGN sum 212->312 @sum += 100");
        assertEquals(6, steps.stream().filter(s -> s.startsWith("UPDATE fin")).count());
        String multi = (String) events(r.trace()).stream().filter(e -> "CATCH".equals(e.get("kind")))
                .map(e -> map(map(e.get("data")).get("binding")).get("javaType")).findFirst().orElseThrow();
        assertEquals("java.lang.NumberFormatException | java.lang.ArithmeticException", multi);
    }

    @Test void rethrownExceptionKeepsItsIdentityThroughFinally() throws Exception {
        Run r = main("exceptions-rethrow", """
                int stage = 0;
                try {
                    try {
                        stage = 1;
                        int z = stage / 0;
                    } catch (ArithmeticException e) {
                        stage = 2;
                        throw e;
                    } finally {
                        stage += 10;
                    }
                } catch (RuntimeException outer) {
                    stage += 100;
                }
                System.out.println(stage);""");
        assertComplete(r, "completed");
        assertEquals("112\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "EXCEPTION java.lang.ArithmeticException @stage / 0", "CATCH e=#o2 @catch (ArithmeticException e)",
                "ASSIGN stage 1->2 @stage = 2", "EXCEPTION java.lang.ArithmeticException @throw e;", "ASSIGN stage 2->12 @stage += 10",
                "CATCH outer=#o2 @catch (RuntimeException outer)", "ASSIGN stage 12->112 @stage += 100");
    }

    @Test void exceptionsFromUncapturedStatementsAreCaught() throws Exception {
        Run r = main("exceptions-uncaptured", """
                int[] a = {3};
                int r = 0;
                try {
                    r = Math.floorDiv(a[0], r);
                } catch (ArithmeticException e) {
                    r = -1;
                }
                System.out.println(r);""");
        assertComplete(r, "completed");
        assertEquals("-1\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "UNCAPTURED r=? @r = Math.floorDiv(a[0], r);", "CATCH e=#o3 @catch (ArithmeticException e)",
                "ASSIGN r ?->-1 @r = -1");
    }

    @Test void unsupportedExceptionConstructsRunUncaptured() throws Exception {
        Run r = run("exceptions-unsupported", """
                public class Main {
                    static class Fancy extends Exception {
                        int code = 7;
                        Fancy() { super("fancy"); }
                    }
                    static int risky(int n) throws Fancy {
                        if (n > 1) throw new Fancy();
                        return n;
                    }
                    public static void main(String[] args) {
                        int x = 0;
                        try (java.io.StringReader reader = new java.io.StringReader("a")) {
                            x = 1;
                        }
                        try {
                            x = risky(2);
                        } catch (Fancy e) {
                            x = 2;
                        } finally {
                            if (x > 1) { x = 3; }
                        }
                        try {
                            x += 1;
                        } finally {
                            x = Math.floorMod(x, 10) + 5;
                        }
                        System.out.println(x);
                    }
                }
                """);
        assertTrue(r.prepared().traced());
        assertEquals("completed", status(r.trace(), "execution"));
        assertEquals("9\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("UNCAPTURED") && s.contains("try (java.io.StringReader")), String.join("\n", steps));
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("UNCAPTURED") && s.contains("throw new Fancy()")), String.join("\n", steps));
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("CATCH e=")), "a class with fields is still caught: " + steps);
        // A finally block that would run uncaptured keeps its whole try statement uncaptured.
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("UNCAPTURED") && s.contains("x += 1;") && s.contains("Math.floorMod")),
                String.join("\n", steps));
    }

    @Test void unsupportedStatementsRunUncapturedWhileTheRestIsTraced() throws Exception {
        // Methods of other classes stay uncaptured (Part 8d traces only static methods of Main).
        String helper = "class Util<T> { // generic: stays untraced (Part 8e)\n    static int twice(int v) { return v * 2; }\n}\npublic class Main {\n"
                + "    public static void main(String[] args) {\n        int n = 4;\n        System.out.println(Util.twice(n));\n    }\n}\n";
        Run h = run("uncaptured-helper", helper);
        assertComplete(h, "completed");
        assertEquals("8\n", h.result().stdout());
        assertEquals(List.of("DECLARE n=4 @int n = 4", "UNCAPTURED @System.out.println(Util.twice(n));"), steps(h.trace()));
        @SuppressWarnings("unchecked")
        var diagnostics = (List<Map<String, Object>>) h.trace().get("diagnostics");
        assertEquals("UNSUPPORTED_CONSTRUCT", diagnostics.get(0).get("code"));
        assertTrue(diagnostics.get(0).containsKey("source"), "diagnostic names the construct's range");
        assertTrue(((String) map(h.trace().get("capture")).get("reason")).contains("without capture"));

        Run c = main("uncaptured-concat", "int n = 1;\nSystem.out.println(Integer.toBinaryString(n));");
        assertEquals(List.of("DECLARE n=1 @int n = 1", "UNCAPTURED @System.out.println(Integer.toBinaryString(n));"), steps(c.trace()));
        assertFalse(c.result().raw().stream().anyMatch(l -> l.startsWith("A")), "printing never re-reads arrays");

        // A declaration without code is silent; uses of its variable are uncaptured.
        Run u = main("uncaptured-unsupported-type", "Object[] q;\nq = new Object[2];\nSystem.out.println(q.length);");
        assertEquals(List.of("UNCAPTURED @q = new Object[2];", "UNCAPTURED @System.out.println(q.length);"), steps(u.trace()));
        assertEquals("2\n", u.result().stdout());
    }

    @Test void assignedBindingsBecomeUnknownUntilTheNextCoveredStore() throws Exception {
        Run r = main("uncaptured-unknown", "int x = 1;\nx = Integer.max(x, 5);\nx = x + 1;\nint i = 0;\ni = StrictMath.abs(i - 2);\ni++;");
        assertComplete(r, "completed");
        assertEquals(List.of("DECLARE x=1 @int x = 1", "UNCAPTURED x=? @x = Integer.max(x, 5);", "EXPR + 5 1 = 6 @x + 1", "ASSIGN x ?->6 @x = x + 1",
                "DECLARE i=0 @int i = 0", "UNCAPTURED i=? @i = StrictMath.abs(i - 2);", "UPDATE i ?->3 =2 @i++"), steps(r.trace()));
    }

    @Test void arraysChangedByUncapturedCodeAreReRead() throws Exception {
        // Part 12a: Arrays.fill is covered now; System.arraycopy is not.
        Run lib = main("uncaptured-library", "int[] a = {2, 1};\nSystem.arraycopy(a, 1, a, 0, 1);\nSystem.out.println(a[0]);");
        assertComplete(lib, "completed");
        assertEquals("1\n", lib.result().stdout());
        assertInOrder(steps(lib.trace()), "ALLOCATE [2,1] @{2, 1}", "UNCAPTURED #o2=[1,1] @System.arraycopy(a, 1, a, 0, 1);", "READ [0]=1 @a[0]");

        String helper = "class Util<T> { // generic: stays untraced (Part 8e)\n    static void bump(int[] a) { a[0]++; }\n}\npublic class Main {\n"
                + "    public static void main(String[] args) {\n        int[] a = {1, 2};\n        Util.bump(a);\n        Util.bump(a);\n"
                + "        int[] b = {7};\n        Util.bump(b);\n        int x = a[0] + b[0];\n    }\n}\n";
        Run h = run("uncaptured-mutating-helper", helper);
        assertComplete(h, "completed");
        assertInOrder(steps(h.trace()), "UNCAPTURED #o2=[2,2] @Util.bump(a);", "UNCAPTURED #o2=[3,2] @Util.bump(a);",
                "UNCAPTURED #o3=[8] @Util.bump(b);", "READ [0]=3 @a[0]", "READ [0]=8 @b[0]", "DECLARE x=11 @int x = a[0] + b[0]");
    }

    @Test void arraysCreatedByUncapturedCodeAreCapturedOnFirstUse() throws Exception {
        Run r = main("uncaptured-new-array", "int[] a = {1};\na = java.util.Arrays.copyOf(a, 3);\na[2] = 9;\nint s = a[2] + a[0];");
        assertComplete(r, "completed");
        List<String> kinds = kinds(r.trace());
        assertTrue(kinds.indexOf("OBJECT_CAPTURE") > kinds.indexOf("UNCAPTURED"));
        assertInOrder(steps(r.trace()), "UNCAPTURED a=? @a = java.util.Arrays.copyOf(a, 3);", "WRITE [2] 0->9 @a[2] = 9",
                "READ [2]=9 @a[2]", "READ [0]=1 @a[0]", "DECLARE s=10 @int s = a[2] + a[0]");
    }

    @Test void uncapturedStatementsInsideLoopsAndBranches() throws Exception {
        Run r = main("uncaptured-loop", "int s = 0;\nfor (int i = 0; i < 5; i++) {\n    if (i == 3) break;\n    s = Integer.max(s, s + i);\n}\n"
                + "for (int j = 0; j < 4; j = Math.addExact(j, 2)) System.out.println(j);\nSystem.out.println(s);");
        assertComplete(r, "completed");
        assertEquals("0\n2\n3\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertEquals(3, steps.stream().filter(s -> s.equals("UNCAPTURED s=? @s = Integer.max(s, s + i);")).count());
        // An unsupported for-update degrades the whole loop into one uncaptured step.
        assertEquals(1, steps.stream().filter(s -> s.startsWith("UNCAPTURED @for (int j")).count());
    }

    @Test void tabIndentedSourceMapsStatementRanges() throws Exception {
        Run r = run("uncaptured-tabs", "public class Main {\n\tpublic static void main(String[] args) {\n\t\tint x = 1;\n"
                + "\t\tx = Integer.max(x, 3);\n\t\tSystem.out.println(x);\n\t}\n}\n");
        assertComplete(r, "completed");
        assertEquals(List.of("DECLARE x=1 @int x = 1", "UNCAPTURED x=? @x = Integer.max(x, 3);"), steps(r.trace())); // println(x) is covered
    }

    @Test void failureInsideAnUncapturedStatementEndsAtThatStep() throws Exception {
        Run r = main("uncaptured-failure", "int[] a = {1};\nint x = 0;\nx = Math.floorDiv(a[0], x);");
        assertTrue(r.prepared().traced());
        assertEquals("failed", status(r.trace(), "execution"));
        assertEquals("partial", status(r.trace(), "capture"));
        List<String> steps = steps(r.trace());
        assertEquals("UNCAPTURED x=? @x = Math.floorDiv(a[0], x);", steps.get(steps.size() - 1));
    }

    // ---- Part 8b: primitive types and operators --------------------------------------

    @Test void allPrimitiveTypesAreTracedWithExactValues() throws Exception {
        Run r = main("primitive-types", """
                long big = 3000000000L;
                big = big * 2 + 1;
                byte b = 127;
                b++;
                short s = -2;
                s--;
                char c = 'a';
                c += 2;
                float f = 1.5f;
                double d = f / 4;
                d -= 0.125;
                int i = (int) (d * 100);
                long fromInt = i;
                char letter = (char) (c + 1);
                System.out.println(big + " " + b + " " + s + " " + c + " " + f + " " + d + " " + i + " " + fromInt + " " + letter);""");
        assertComplete(r, "completed");
        assertEquals("6000000001 -128 -3 c 1.5 0.25 25 25 d\n", r.result().stdout());
        assertInOrder(steps(r.trace()),
                "DECLARE big=3000000000 @long big = 3000000000L",
                "EXPR * 3000000000 2 = 6000000000 @big * 2",
                "EXPR + 6000000000 1 = 6000000001 @big * 2 + 1",
                "ASSIGN big 3000000000->6000000001 @big = big * 2 + 1",
                "DECLARE b=127 @byte b = 127",
                "UPDATE b 127->-128 =127 @b++",
                "UPDATE s -2->-3 =-2 @s--",
                "DECLARE c=97 @char c = 'a'",
                "EXPR + 97 2 = 99 @c += 2",
                "ASSIGN c 97->99 @c += 2",
                "EXPR * 0.25 100.0 = 25.0 @d * 100",
                "EXPR (int) 25.0 = 25 @(int) (d * 100)",
                "DECLARE fromInt=25 @long fromInt = i",
                "EXPR (char) 100 = 100 @(char) (c + 1)");
        String json = r.json();
        assertTrue(json.contains("{\"kind\":\"double\",\"bits\":\"3fd0000000000000\"}"), "0.25 as exact bits");
        assertTrue(json.contains("{\"kind\":\"char\",\"value\":100}"));
    }

    @Test void bitwiseShiftsCastsAndTernary() throws Exception {
        Run r = main("bitwise", """
                int x = 6;
                int y = x & 3 | 8 ^ 1;
                y = ~y;
                y <<= 2;
                long shifted = 1L << x;
                int count = 40;
                long viaLong = 5L >>> count;
                int narrow = x << 34L;
                boolean p = x > 3;
                boolean q = p & !p | p ^ true;
                int pick = p ? x : -x;
                byte packed = (byte) 300;
                packed = (byte) (packed + x * 50);
                System.out.println(y + " " + shifted + " " + viaLong + " " + narrow + " " + q + " " + pick + " " + packed);""");
        assertComplete(r, "completed");
        assertEquals("-48 64 0 24 false 6 88\n", r.result().stdout());
        assertInOrder(steps(r.trace()), "EXPR & 6 3 = 2 @x & 3", "EXPR | 2 9 = 11 @x & 3 | 8 ^ 1", "EXPR ~ 11 = -12 @~y",
                "EXPR << -12 2 = -48 @y <<= 2", "EXPR << 1 6 = 64 @1L << x", "EXPR >>> 5 40 = 0 @5L >>> count",
                "EXPR << 6 34 = 24 @x << 34L", "EXPR condition true = true @p", "EXPR + 44 300 = 344 @packed + x * 50",
                "EXPR (byte) 344 = 88 @(byte) (packed + x * 50)");
    }

    @Test void longFloatAndDoubleComparisonsKeepJavaSemantics() throws Exception {
        Run r = main("float-compare", """
                double zero = 0;
                double nan = zero / zero;
                boolean any = nan < 1 || nan >= 1 || nan == nan;
                float neg = -0.0f;
                boolean same = neg == 0.0f;
                long l = 5;
                boolean greater = l > 4 && 2.5 <= l;
                char ch = 'q';
                boolean letter = ch >= 'a' && ch <= 'z';
                System.out.println(any + " " + same + " " + greater + " " + letter);""");
        assertComplete(r, "completed");
        assertEquals("false true true true\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertInOrder(steps, "EXPR < NaN 1.0 = false @nan < 1", "EXPR >= NaN 1.0 = false @nan >= 1", "EXPR == NaN NaN = false @nan == nan",
                "EXPR == -0.0 0.0 = true @neg == 0.0f", "EXPR > 5 4 = true @l > 4", "EXPR <= 2.5 5.0 = true @2.5 <= l",
                "EXPR >= 113 97 = true @ch >= 'a'");
    }

    @Test void compoundAssignmentAndUpdatesOnArrayElements() throws Exception {
        Run r = main("array-compound", "int[] a = {5, 7};\na[0] += 10;\na[1]++;\nint y = a[0]--;\na[1] *= a[0];");
        assertComplete(r, "completed");
        assertInOrder(steps(r.trace()),
                "READ [0]=5 @a[0]", "EXPR + 5 10 = 15 @a[0] += 10", "WRITE [0] 5->15 @a[0] += 10",
                "READ [1]=7 @a[1]", "EXPR + 7 1 = 8 @a[1]++", "WRITE [1] 7->8 @a[1]++",
                "READ [0]=15 @a[0]", "EXPR - 15 1 = 14 @a[0]--", "WRITE [0] 15->14 @a[0]--", "DECLARE y=15 @int y = a[0]--",
                "READ [1]=8 @a[1]", "READ [0]=14 @a[0]", "EXPR * 8 14 = 112 @a[1] *= a[0]", "WRITE [1] 8->112 @a[1] *= a[0]");
    }

    @Test void overflowWideIncrementsAndLongDivisionByZero() throws Exception {
        Run r = main("primitive-edges", "char c = 65535;\nc++;\nint i = 5;\ni -= 40000;\ni += 7;\nlong zero = 0;\nlong boom = 10L / zero;");
        assertEquals("failed", status(r.trace(), "execution"));
        assertEquals("complete", status(r.trace(), "capture"));
        assertInOrder(steps(r.trace()), "UPDATE c 65535->0 =65535 @c++",
                "EXPR - 5 40000 = -39995 @i -= 40000", "ASSIGN i 5->-39995 @i -= 40000",   // too large for iinc
                "EXPR + -39995 7 = -39988 @i += 7", "ASSIGN i -39995->-39988 @i += 7",     // iinc 7
                "EXCEPTION java.lang.ArithmeticException @10L / zero");
    }

    @Test void declarationsWithoutInitializerAppearBeforeTheirNextOperation() throws Exception {
        Run r = main("uninitialized", """
                int total;
                total = 0;
                for (int i = 0; i < 2; i++) {
                    int square;
                    square = i * i;
                    total += square;
                }
                long unused;
                System.out.println(total);""");
        assertComplete(r, "completed");
        List<String> steps = steps(r.trace());
        assertEquals("DECLARE total=_ @int total", steps.get(0));
        assertEquals(2, steps.stream().filter(s -> s.equals("DECLARE square=_ @int square")).count(), "a fresh binding per iteration");
        assertInOrder(steps, "ASSIGN total _->0 @total = 0", "DECLARE square=_ @int square", "EXPR * 0 0 = 0 @i * i",
                "ASSIGN square _->0 @square = i * i", "EXPR + 0 0 = 0 @total += square", "ASSIGN total 0->0 @total += square");
        assertEquals("1\n", r.result().stdout());
        assertTrue(steps.stream().noneMatch(s -> s.contains("unused")), "never followed by an operation: not observable");
    }

    @Test void programWideLimitsStillRunOutputOnly() throws Exception {
        for (var c : List.of(
                List.of("thread", program("Thread t = new Thread(() -> { });\nt.start();"), "TRACE_INTEGRITY"),
                List.of("reflection", program("System.out.println(String.class.getMethods().length > 0);"), "TRACE_INTEGRITY"),
                List.of("no-main", "public class Main {\n    static void run() { }\n}\n", "UNSUPPORTED_CONSTRUCT"))) {
            Tracer.Prepared prepared = Tracer.prepare(c.get(1));
            assertFalse(prepared.traced(), c.get(0));
            assertEquals(c.get(2), prepared.analysis().unsupported().get(0).code());
        }
        Run r = main("integrity-output-only", "System.out.println(Thread.currentThread() != null);");
        assertEquals("unavailable", status(r.trace(), "capture"));
        assertEquals("true\n", r.result().stdout());
    }

    @Test void constantConcatenationIsFoldedNotTraced() throws Exception {
        Run r = main("constant-concat", "System.out.println(\"a\" + 1);");
        assertComplete(r, "completed");
        assertEquals("a1\n", r.result().stdout());
        assertEquals(List.of(), steps(r.trace()), "javac folds the constant; nothing executes");
    }

    @Test void compileErrorsNeverRun() throws Exception {
        Run r = main("compile-error", "int x = ;");
        assertEquals("compile_error", status(r.trace(), "execution"));
        assertEquals("unavailable", status(r.trace(), "capture"));
        assertNull(r.trace().get("initialState"));
    }

    @Test void recorderIsNotVisibleToSubmittedCode() {
        var prepared = Tracer.prepare(program("codeviz.rt.Recorder.exit();"));
        assertFalse(prepared.compiled(), "submitted code compiles against the JDK only");
    }

    // ---- limits and integrity -------------------------------------------------

    @Test void oversizedRecordEndsCaptureAsLimited() throws Exception {
        Run r = main("record-limit", "int x = 1;\nint[] big = new int[100000];\nSystem.out.println(big.length);");
        assertEquals("limited", status(r.trace(), "execution"));
        assertEquals("partial", status(r.trace(), "capture"));
        assertEquals(List.of("DECLARE x=1 @int x = 1"), steps(r.trace()));
    }

    @Test void rawLimitHaltsAndReportsLimited() throws Exception {
        Run r = main("raw-limit", "int s = 0;\nfor (int i = 0; i < 5000000; i++) s = s + i;\nSystem.out.println(s);");
        assertEquals(86, r.result().exit());
        assertEquals("limited", status(r.trace(), "execution"));
        assertEquals("partial", status(r.trace(), "capture"));
    }

    @Test void tamperedRecordStopsAtLastSafeOperation() throws Exception {
        String source = program("int[] a = {4, 1};\nint x = a[1];\nint y = x;");
        Tracer.Prepared prepared = Tracer.prepare(source);
        HostRun.Result result = HostRun.run(prepared.classes(), true, temp);
        List<String> raw = new ArrayList<>(result.raw());
        int read = -1;
        for (int i = 0; i < raw.size(); i++) if (raw.get(i).matches("\\d+ r2 1 1")) read = i;
        assertTrue(read > 0, "read record present: " + raw);
        raw.set(read, raw.get(read).replace(" r2 1 1", " r2 1 7")); // forged value disagrees with the array
        String json = Tracer.trace(prepared, "test-tampered", raw, result.exit());
        validate("tampered", json);
        Map<String, Object> trace = JsonReader.object(json);
        assertEquals("partial", status(trace, "capture"));
        assertEquals(List.of("ALLOCATE [4,1] @{4, 1}", "DECLARE a=#o2 @int[] a = {4, 1}"), steps(trace));
    }

    /** Part 8h: a catch needs an exception in flight, and the exception it receives must be one its clause catches. */
    @Test void forgedCatchRecordsStopCapture() throws Exception {
        String source = program("int x = 0;\ntry {\n    x = 10 / x;\n} catch (ArithmeticException e) {\n    x = -1;\n}");
        Tracer.Prepared prepared = Tracer.prepare(source);
        HostRun.Result result = HostRun.run(prepared.classes(), true, temp);
        List<String> raw = new ArrayList<>(result.raw());
        int failure = -1, exception = -1;
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i).startsWith("x")) failure = i;
            if (raw.get(i).startsWith("E")) exception = i;
        }
        assertTrue(failure > 0 && exception > 0, "failure records present: " + raw);
        List<String> noFailure = new ArrayList<>(raw);
        noFailure.remove(failure);
        Map<String, Object> unthrown = JsonReader.object(Tracer.trace(prepared, "test-unthrown", noFailure, result.exit()));
        assertEquals("partial", status(unthrown, "capture"));
        assertTrue(steps(unthrown).stream().noneMatch(s -> s.startsWith("CATCH")));
        List<String> otherClass = new ArrayList<>(raw);
        otherClass.set(exception, otherClass.get(exception).replace("java.lang.ArithmeticException", "java.lang.IllegalStateException"));
        otherClass.set(failure, otherClass.get(failure).replace("java.lang.ArithmeticException", "java.lang.IllegalStateException"));
        Map<String, Object> wrong = JsonReader.object(Tracer.trace(prepared, "test-wrong-catch", otherClass, result.exit()));
        assertEquals("partial", status(wrong, "capture"));
        assertTrue(steps(wrong).stream().noneMatch(s -> s.startsWith("CATCH")));
    }
}
