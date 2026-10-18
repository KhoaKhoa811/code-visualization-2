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

    private Run run(String name, String source) throws Exception {
        Tracer.Prepared prepared = Tracer.prepare(source);
        HostRun.Result result = prepared.compiled() ? HostRun.run(prepared.classes(), prepared.traced(), temp)
                : new HostRun.Result(-1, "", "", List.of());
        // A run halted at the raw limit legitimately differs from the original.
        if (prepared.traced() && !result.raw().contains("L")) {
            HostRun.Result original = HostRun.run(prepared.analysis().classes(), false, temp);
            assertEquals(original.stdout(), result.stdout(), "stdout must match the original program");
            assertEquals(original.exit(), result.exit(), "exit status must match the original program");
            assertEquals(original.stderrFirstLine(), result.stderrFirstLine(), "uncaught exception must match");
        }
        String json = Tracer.trace(prepared, "test-" + name, result.raw(), result.exit());
        validate(name, json);
        return new Run(JsonReader.object(json), result, prepared, json);
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
                case "ALLOCATE" -> "ALLOCATE " + ((List<Object>) map(d.get("object")).get("elements")).stream()
                        .map(TracerTest::value).toList().toString().replace(" ", "");
                case "ARRAY_READ" -> "READ [" + d.get("index") + "]=" + value(d.get("value"));
                case "ARRAY_WRITE" -> "WRITE [" + d.get("index") + "] " + value(d.get("previous")) + "->" + value(d.get("value"));
                case "ARRAY_LENGTH" -> "LENGTH " + d.get("length");
                case "EXPRESSION" -> "EXPR " + d.get("operator") + " " + ((List<Object>) d.get("operands")).stream()
                        .map(o -> map(o).containsKey("skipped") ? "_" : value(map(o).get("value")))
                        .reduce((a, b) -> a + " " + b).orElse("") + " = " + value(d.get("result"));
                case "EXCEPTION" -> "EXCEPTION " + d.get("exceptionType");
                default -> e.get("kind").toString();
            };
            Map<String, Object> range = map(e.get("source"));
            out.add(fact + " @" + text.substring(((Long) range.get("start")).intValue(), ((Long) range.get("end")).intValue()));
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
        assertEquals("complete", status(run.trace(), "capture"));
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

    // ---- outside coverage -------------------------------------------------------

    @Test void unsupportedProgramsRunOutputOnly() throws Exception {
        String helper = "public class Main {\n    static int twice(int v) { return v * 2; }\n"
                + "    public static void main(String[] args) {\n        System.out.println(twice(4));\n    }\n}\n";
        for (var c : List.of(
                List.of("helper", helper, "8\n"),
                List.of("concat", program("int n = 1;\nSystem.out.println(\"a\" + n);"), "a1\n"),
                List.of("uninitialized", program("int x;\nx = 1;\nSystem.out.println(x);"), "1\n"),
                List.of("library", program("int[] a = {2, 1};\njava.util.Arrays.sort(a);\nSystem.out.println(a[0]);"), "1\n"))) {
            Run r = run("unsupported-" + c.get(0), c.get(1));
            assertFalse(r.prepared().traced());
            assertEquals(c.get(2), r.result().stdout());
            assertEquals("completed", status(r.trace(), "execution"));
            assertEquals("unavailable", status(r.trace(), "capture"));
            @SuppressWarnings("unchecked")
            var diagnostics = (List<Map<String, Object>>) r.trace().get("diagnostics");
            assertEquals("UNSUPPORTED_CONSTRUCT", diagnostics.get(0).get("code"));
            assertTrue(diagnostics.get(0).containsKey("source"), "diagnostic names the construct's range");
        }
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
}
