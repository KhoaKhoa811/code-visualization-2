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
        // The backend builds the trace from the serialized plan; it must give the same trace.
        Tracer.Prepared decoded = Tracer.fromPlan(source, Tracer.plan(prepared));
        assertEquals(json, Tracer.trace(decoded, "test-" + name, result.raw(), result.exit()), "plan round trip");
        Map<String, Object> trace = JsonReader.object(json);
        assertReferencesResolve(trace);
        return new Run(trace, result, prepared, json);
    }

    /** Every reference in an event names an object captured by that point (Part 8c: nested arrays). */
    @SuppressWarnings("unchecked")
    private static void assertReferencesResolve(Map<String, Object> trace) {
        if (trace.get("initialState") == null) return;
        java.util.Set<Object> known = new java.util.HashSet<>();
        for (Object o : (List<Object>) map(trace.get("initialState")).get("objects")) known.add(map(o).get("objectId"));
        for (Map<String, Object> e : events(trace)) {
            Map<String, Object> data = map(e.get("data"));
            Object created = data.get("object") instanceof Map<?, ?> object ? object.get("objectId") : null;
            List<Object> refs = new ArrayList<>();
            collectRefs(data, refs);
            for (Object id : refs) assertTrue(known.contains(id), "event " + e.get("seq") + " references uncaptured " + id);
            if (created != null) assertTrue(known.add(created), "object captured twice: " + created);
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
                case "ALLOCATE" -> "ALLOCATE " + ((List<Object>) map(d.get("object")).get("elements")).stream()
                        .map(TracerTest::value).toList().toString().replace(" ", "");
                case "ARRAY_READ" -> "READ [" + d.get("index") + "]=" + value(d.get("value"));
                case "ARRAY_WRITE" -> "WRITE [" + d.get("index") + "] " + value(d.get("previous")) + "->" + value(d.get("value"));
                case "ARRAY_LENGTH" -> "LENGTH " + d.get("length");
                case "EXPRESSION" -> "EXPR " + d.get("operator") + " " + ((List<Object>) d.get("operands")).stream()
                        .map(o -> map(o).containsKey("skipped") ? "_" : value(map(o).get("value")))
                        .reduce((a, b) -> a + " " + b).orElse("") + " = " + value(d.get("result"));
                case "EXCEPTION" -> "EXCEPTION " + d.get("exceptionType");
                case "SWITCH" -> "SWITCH " + value(d.get("value")) + " " + d.get("matched");
                case "UNCAPTURED" -> "UNCAPTURED" + ((List<Object>) d.get("unknownBindings")).stream().map(id -> " " + names.get(id) + "=?")
                        .reduce("", String::concat) + ((List<Object>) d.get("arrays")).stream().map(a -> " #" + map(a).get("objectId") + "="
                        + ((List<Object>) map(a).get("elements")).stream().map(TracerTest::value).toList().toString().replace(" ", ""))
                        .reduce("", String::concat) + ((List<Object>) d.get("unknownArrays")).stream().map(id -> " #" + id + "=?").reduce("", String::concat);
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
                s[0] = String.valueOf(42);
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
                        case 1: n = Math.max(n, 9); break;
                        default: n++;
                    }
                }
                int m = switch (n) { case 10 -> { int q = Math.abs(-3); yield q; } default -> 0; };
                int p = 1 + switch (m) { case 3 -> 4; default -> 5; };
                System.out.println(found);
                System.out.println(n);
                System.out.println(m);
                System.out.println(p);""");
        assertComplete(r, "completed");
        assertEquals("6\n10\n3\n5\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertInOrder(steps, "READ [1]=6 @a", "DECLARE v=6 @int v", "EXPR == 6 6 = true @v == 6", "ASSIGN found -1->6 @found = v",
                "SWITCH 0 case @case 0", "SWITCH 1 case @case 1", "UNCAPTURED n=? @n = Math.max(n, 9);",
                "SWITCH 2 default @default", "UPDATE n ?->10 =9 @n++",
                "SWITCH 10 case @case 10", "UNCAPTURED @int q = Math.abs(-3);", "UNCAPTURED @yield q;",
                "DECLARE m=3 @int m = switch (n) { case 10 -> { int q = Math.abs(-3); yield q; } default -> 0; }",
                "UNCAPTURED @int p = 1 + switch (m) { case 3 -> 4; default -> 5; };");
        assertFalse(steps.stream().anyMatch(s -> s.startsWith("READ [2]")), "break leaves the for-each before its last element");
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

    // ---- outside coverage -------------------------------------------------------

    // ---- Part 8a: uncaptured statements inside traced programs -----------------------

    @Test void unsupportedStatementsRunUncapturedWhileTheRestIsTraced() throws Exception {
        String helper = "public class Main {\n    static int twice(int v) { return v * 2; }\n"
                + "    public static void main(String[] args) {\n        int n = 4;\n        System.out.println(twice(n));\n    }\n}\n";
        Run h = run("uncaptured-helper", helper);
        assertComplete(h, "completed");
        assertEquals("8\n", h.result().stdout());
        assertEquals(List.of("DECLARE n=4 @int n = 4", "UNCAPTURED @System.out.println(twice(n));"), steps(h.trace()));
        @SuppressWarnings("unchecked")
        var diagnostics = (List<Map<String, Object>>) h.trace().get("diagnostics");
        assertEquals("UNSUPPORTED_CONSTRUCT", diagnostics.get(0).get("code"));
        assertTrue(diagnostics.get(0).containsKey("source"), "diagnostic names the construct's range");
        assertTrue(((String) map(h.trace().get("capture")).get("reason")).contains("without capture"));

        Run c = main("uncaptured-concat", "int n = 1;\nSystem.out.println(\"a\" + n);");
        assertEquals(List.of("DECLARE n=1 @int n = 1", "UNCAPTURED @System.out.println(\"a\" + n);"), steps(c.trace()));
        assertFalse(c.result().raw().stream().anyMatch(l -> l.startsWith("A")), "printing never re-reads arrays");

        // A declaration without code is silent; uses of its variable are uncaptured.
        Run u = main("uncaptured-unsupported-type", "Integer[] q;\nq = new Integer[2];\nSystem.out.println(q.length);");
        assertEquals(List.of("UNCAPTURED @q = new Integer[2];", "UNCAPTURED @System.out.println(q.length);"), steps(u.trace()));
        assertEquals("2\n", u.result().stdout());
    }

    @Test void assignedBindingsBecomeUnknownUntilTheNextCoveredStore() throws Exception {
        Run r = main("uncaptured-unknown", "int x = 1;\nx = Math.max(x, 5);\nx = x + 1;\nint i = 0;\ni = Math.abs(i - 2);\ni++;");
        assertComplete(r, "completed");
        assertEquals(List.of("DECLARE x=1 @int x = 1", "UNCAPTURED x=? @x = Math.max(x, 5);", "EXPR + 5 1 = 6 @x + 1", "ASSIGN x ?->6 @x = x + 1",
                "DECLARE i=0 @int i = 0", "UNCAPTURED i=? @i = Math.abs(i - 2);", "UPDATE i ?->3 =2 @i++"), steps(r.trace()));
    }

    @Test void arraysChangedByUncapturedCodeAreReRead() throws Exception {
        Run lib = main("uncaptured-library", "int[] a = {2, 1};\njava.util.Arrays.sort(a);\nSystem.out.println(a[0]);");
        assertComplete(lib, "completed");
        assertEquals("1\n", lib.result().stdout());
        assertInOrder(steps(lib.trace()), "ALLOCATE [2,1] @{2, 1}", "UNCAPTURED #o2=[1,2] @java.util.Arrays.sort(a);", "READ [0]=1 @a[0]");

        String helper = "public class Main {\n    static void bump(int[] a) { a[0]++; }\n"
                + "    public static void main(String[] args) {\n        int[] a = {1, 2};\n        bump(a);\n        bump(a);\n"
                + "        int[] b = {7};\n        bump(b);\n        int x = a[0] + b[0];\n    }\n}\n";
        Run h = run("uncaptured-mutating-helper", helper);
        assertComplete(h, "completed");
        assertInOrder(steps(h.trace()), "UNCAPTURED #o2=[2,2] @bump(a);", "UNCAPTURED #o2=[3,2] @bump(a);",
                "UNCAPTURED #o3=[8] @bump(b);", "READ [0]=3 @a[0]", "READ [0]=8 @b[0]", "DECLARE x=11 @int x = a[0] + b[0]");
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
        Run r = main("uncaptured-loop", "int s = 0;\nfor (int i = 0; i < 5; i++) {\n    if (i == 3) break;\n    s = Math.max(s, s + i);\n}\n"
                + "for (int j = 0; j < 4; j = Math.addExact(j, 2)) System.out.println(j);\nSystem.out.println(s);");
        assertComplete(r, "completed");
        assertEquals("0\n2\n3\n", r.result().stdout());
        List<String> steps = steps(r.trace());
        assertEquals(3, steps.stream().filter(s -> s.equals("UNCAPTURED s=? @s = Math.max(s, s + i);")).count());
        // An unsupported for-update degrades the whole loop into one uncaptured step.
        assertEquals(1, steps.stream().filter(s -> s.startsWith("UNCAPTURED @for (int j")).count());
    }

    @Test void tabIndentedSourceMapsStatementRanges() throws Exception {
        Run r = run("uncaptured-tabs", "public class Main {\n\tpublic static void main(String[] args) {\n\t\tint x = 1;\n"
                + "\t\tx = Math.max(x, 3);\n\t\tSystem.out.println(x);\n\t}\n}\n");
        assertComplete(r, "completed");
        assertEquals(List.of("DECLARE x=1 @int x = 1", "UNCAPTURED x=? @x = Math.max(x, 3);"), steps(r.trace())); // println(x) is covered
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
}
