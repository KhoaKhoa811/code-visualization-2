package codeviz.tracer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 7c tracer-side checks: the compile-phase CLI runs in its own JVM (as it will
 * in the container), its plan file rebuilds the same trace in a separate reader,
 * and orchestrator stop causes map to the execution-isolation statuses.
 */
class PrepareTest {
    @TempDir Path temp;

    private static String program(String body) {
        return "public class Main {\n    public static void main(String[] args) {\n" + body + "\n    }\n}\n";
    }

    /** Runs Prepare in a separate JVM; returns the out directory. */
    private Path prepareInProcess(String source) throws Exception {
        Path src = temp.resolve("src-" + System.nanoTime());
        Files.createDirectories(src);
        Files.writeString(src.resolve("Main.java"), source, StandardCharsets.UTF_8);
        Path out = src.resolve("out");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "codeviz.tracer.Prepare",
                src.resolve("Main.java").toString(), out.toString()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), output);
        return out;
    }

    private static Map<String, byte[]> classes(Path out) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.list(out)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".class")) classes.put(name.substring(0, name.length() - 6), Files.readAllBytes(f));
            }
        }
        return classes;
    }

    private String check(String name, String json) throws Exception {
        Path file = temp.resolve(name + ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        Process node = new ProcessBuilder("node", Path.of("../../contracts/trace/check.cjs").toString(), file.toString())
                .redirectErrorStream(true).start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(node.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, node.exitValue(), output);
        return json;
    }

    @SuppressWarnings("unchecked")
    private static String field(String json, String part, String key) {
        return (String) ((Map<String, Object>) JsonReader.object(json).get(part)).get(key);
    }

    @Test void planFromSeparateJvmRebuildsTheSameTrace() throws Exception {
        for (String body : List.of(
                "int[] a = {3, 1, 2};\nfor (int i = 0; i < a.length - 1; i++) {\n  for (int j = 0; j < a.length - 1 - i; j++) {\n"
                        + "    if (a[j] > a[j + 1]) { int t = a[j]; a[j] = a[j + 1]; a[j + 1] = t; }\n  }\n}",
                "String s = \"hi\";\nboolean ok = s != null && !(s == null);\nint x = ok ? 1 : 2;",
                "int[] a = {0};\nint x = 0;\na[2] = ++x;",
                "int x = ;")) {
            String source = program(body);
            Path out = prepareInProcess(source);
            Tracer.Prepared fromPlan = Tracer.fromPlan(source, Files.readString(out.resolve("plan.json")));
            Tracer.Prepared direct = Tracer.prepare(source);
            assertEquals(direct.traced(), fromPlan.traced());
            assertEquals(direct.compiled(), fromPlan.compiled());
            HostRun.Result run = direct.compiled() ? HostRun.run(classes(out), fromPlan.traced(), temp)
                    : new HostRun.Result(-1, "", "", List.of());
            String raw = run.raw().isEmpty() ? "" : String.join("\n", run.raw()) + "\n";
            var outcome = Tracer.Outcome.exited(run.exit());
            String rebuilt = Tracer.trace(fromPlan, "plan", raw, outcome);
            assertEquals(Tracer.trace(direct, "plan", raw, outcome), rebuilt, "plan file must carry every builder fact");
            check("plan-" + Math.abs(body.hashCode()), rebuilt);
        }
    }

    @Test void planForDifferentSourceIsRejected() throws Exception {
        String source = program("int x = 5;");
        String plan = Tracer.plan(Tracer.prepare(source));
        assertThrows(IllegalArgumentException.class, () -> Tracer.fromPlan(program("int x = 6;"), plan));
    }

    @Test void stopCausesWinOverRecordedTermination() throws Exception {
        String source = program("int x = 1;\nx = 2;\nx = 3;");
        Tracer.Prepared prepared = Tracer.prepare(source);
        HostRun.Result run = HostRun.run(prepared.classes(), true, temp);
        String raw = String.join("\n", run.raw()) + "\n";
        String timeout = check("timeout", Tracer.trace(prepared, "t", raw, new Tracer.Outcome(Tracer.Cause.EXECUTION_TIMEOUT, 137)));
        assertEquals("limited", field(timeout, "execution", "status"));
        assertEquals("Execution exceeded 5000 ms", field(timeout, "execution", "reason"));
        assertEquals("partial", field(timeout, "capture", "status"));
        assertTrue(timeout.contains("\"code\":\"EXECUTION_TIMEOUT\""));
        assertFalse(timeout.contains("FRAME_END"), "no terminal bookkeeping after a stop cause");
        String cancelled = check("cancelled", Tracer.trace(prepared, "c", raw, new Tracer.Outcome(Tracer.Cause.CANCELLED, 137)));
        assertEquals("cancelled", field(cancelled, "execution", "status"));
        assertTrue(cancelled.contains("\"code\":\"RUN_CANCELLED\""));
    }

    @Test void tornLastRecordIsDropped() throws Exception {
        String source = program("int x = 1;\nx = 2;");
        Tracer.Prepared prepared = Tracer.prepare(source);
        HostRun.Result run = HostRun.run(prepared.classes(), true, temp);
        List<String> raw = run.raw();
        // Kill mid-write: the ASSIGN record is present but not newline-terminated.
        String torn = String.join("\n", raw.subList(0, raw.size() - 1));
        String json = check("torn", Tracer.trace(prepared, "torn", torn, new Tracer.Outcome(Tracer.Cause.EXECUTION_TIMEOUT, 137)));
        assertTrue(json.contains("\"kind\":\"DECLARE\""));
        assertFalse(json.contains("\"kind\":\"ASSIGN\""));
    }

    @Test void stopsBeforeAPlanExistGiveUnavailableCapture() throws Exception {
        String source = program("int x = 1;");
        String compileTimeout = check("compile-timeout", Tracer.notRun(source, "ct", new Tracer.Outcome(Tracer.Cause.COMPILATION_TIMEOUT, 137)));
        assertEquals("limited", field(compileTimeout, "execution", "status"));
        assertEquals("unavailable", field(compileTimeout, "capture", "status"));
        assertTrue(compileTimeout.contains("\"phase\":\"compile\",\"code\":\"COMPILATION_TIMEOUT\""));
        String output = check("compile-output", Tracer.notRun(source, "co", new Tracer.Outcome(Tracer.Cause.OUTPUT_LIMIT, 1)));
        assertTrue(output.contains("\"phase\":\"compile\",\"code\":\"OUTPUT_LIMIT\""));
    }

    @Test void outputOnlyRunReportsItsStopCause() throws Exception {
        // Threads keep a program output-only (Part 8a integrity guard).
        String source = "public class Main {\n    public static void main(String[] args) throws Exception {\n"
                + "        Thread.sleep(100000);\n    }\n}\n";
        Tracer.Prepared prepared = Tracer.prepare(source);
        assertFalse(prepared.traced());
        String json = check("output-only-timeout", Tracer.trace(prepared, "o", "", new Tracer.Outcome(Tracer.Cause.EXECUTION_TIMEOUT, 137)));
        assertEquals("limited", field(json, "execution", "status"));
        assertEquals("unavailable", field(json, "capture", "status"));
        assertTrue(json.contains("TRACE_INTEGRITY") && json.contains("EXECUTION_TIMEOUT"));
    }
}
