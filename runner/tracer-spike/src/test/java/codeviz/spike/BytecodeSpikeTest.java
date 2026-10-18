package codeviz.spike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential and event checks for the Part 7a bytecode spike. Trusted repository
 * programs are compiled and run as separate host JVM processes, original and
 * instrumented, then compared. Not the isolated runner; container runs are 7b work.
 */
class BytecodeSpikeTest {
    record Run(int exit, String stdout, String stderrFirstLine, List<String> events) { }

    @TempDir Path temp;

    private Run run(String program, boolean instrumented) throws Exception {
        Path source = Path.of("src/test/resources/programs", program, "Main.java");
        Path classes = temp.resolve(program + "-classes");
        Files.createDirectories(classes);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "--release", "21", "-d", classes.toString(), source.toString());
        assertEquals(0, compiled, "fixture must compile");
        Path classpath = classes;
        Path trace = temp.resolve(program + ".jsonl");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        if (instrumented) {
            classpath = temp.resolve(program + "-instrumented");
            Instrumenter.instrumentDirectory(classes, classpath);
            command.add("-Dcodeviz.trace=" + trace);
            command.addAll(List.of("-cp", classpath + java.io.File.pathSeparator + Path.of("target/classes").toAbsolutePath()));
        } else {
            command.addAll(List.of("-cp", classpath.toString()));
        }
        command.add("Main");
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        List<String> events = instrumented ? Files.readAllLines(trace).stream().map(BytecodeSpikeTest::project).toList() : List.of();
        return new Run(process.exitValue(), stdout, stderr.lines().findFirst().orElse(""), events);
    }

    /** Behavior must be identical: output, exit status and the uncaught exception line. */
    @ParameterizedTest
    @ValueSource(strings = {"ac01", "ac02", "ac03", "ac04", "ac05", "ac07", "ac10", "recursion", "broad"})
    void instrumentedProgramBehavesLikeOriginal(String program) throws Exception {
        Run original = run(program, false), traced = run(program, true);
        assertEquals(original.stdout(), traced.stdout());
        assertEquals(original.exit(), traced.exit());
        assertEquals(original.stderrFirstLine(), traced.stderrFirstLine());
        assertFalse(traced.events().isEmpty());
    }

    @Test void ac01ScalarAssignments() throws Exception {
        assertContainsInOrder(run("ac01", true).events(), "ENTER Main.main", "LOCAL x=int:5", "LOCAL x=int:8", "EXIT");
    }

    @Test void ac02OnlyIndexOneChanges() throws Exception {
        var events = run("ac02", true).events();
        assertContainsInOrder(events, "ARRAY_NEW #2 len=3", "ARRAY_WRITE #2[0]=int:3", "ARRAY_WRITE #2[1]=int:1",
                "ARRAY_WRITE #2[2]=int:2", "LOCAL a=#2", "ARRAY_WRITE #2[1]=int:9");
    }

    @Test void ac03ReadsAndWritesInOrder() throws Exception {
        assertContainsInOrder(run("ac03", true).events(),
                "ARRAY_READ #2[0]=int:1", "ARRAY_WRITE #2[0]=int:2",
                "ARRAY_READ #2[1]=int:2", "ARRAY_WRITE #2[1]=int:4",
                "ARRAY_READ #2[2]=int:3", "ARRAY_WRITE #2[2]=int:6");
    }

    @Test void ac04BubbleSortComparisonsAndWrites() throws Exception {
        var events = run("ac04", true).events();
        // IDs: #1 is main's args array, #2 the int array. First inner comparison a[0]=3 > a[1]=1: javac emits IF_ICMPLE (skip swap), not taken.
        assertContainsInOrder(events, "COMPARE IF_ICMPLE 3 1 jump=false",
                "LOCAL t=int:3", "ARRAY_WRITE #2[0]=int:1", "ARRAY_WRITE #2[1]=int:3");
        List<String> writes = events.stream().filter(e -> e.startsWith("ARRAY_WRITE #2[")).toList();
        assertEquals("ARRAY_WRITE #2[2]=int:3", writes.get(writes.size() - 1));
    }

    @Test void ac05AliasSharesOneIdentity() throws Exception {
        assertContainsInOrder(run("ac05", true).events(), "LOCAL a=#2", "LOCAL b=#2", "ARRAY_WRITE #2[0]=int:9");
    }

    @Test void ac07SideEffectsHappenOnce() throws Exception {
        var events = run("ac07", true).events();
        assertEquals(2, events.stream().filter(e -> e.equals("ENTER Main.calculate")).count(),
                "calculate() runs exactly twice; the short-circuited call never runs");
        assertContainsInOrder(events, "LOCAL i=int:1", "ARRAY_WRITE #2[0]=int:41",
                "LOCAL i=int:2", "ARRAY_READ #2[1]=int:0", "ARRAY_WRITE #2[1]=int:42");
    }

    @Test void ac10FailedWriteIsNotRecordedAsSuccess() throws Exception {
        var events = run("ac10", true).events();
        assertTrue(events.contains("ARRAY_WRITE #2[0]=int:1"));
        assertFalse(events.stream().anyMatch(e -> e.startsWith("ARRAY_WRITE #2[3]")));
        assertEquals("THROW_EXIT java.lang.ArrayIndexOutOfBoundsException", events.get(events.size() - 1));
    }

    @Test void recursionHasDistinctFramesAndReturns() throws Exception {
        var raw = run("recursion", true);
        assertContainsInOrder(raw.events(), "ENTER Main.fact", "LOCAL n=int:3", "ENTER Main.fact", "LOCAL n=int:2",
                "ENTER Main.fact", "LOCAL n=int:1", "EXIT int:1", "LOCAL rest=int:1", "EXIT int:2",
                "LOCAL rest=int:2", "EXIT int:6");
        assertEquals("6\n", raw.stdout().replace("\r", ""));
    }

    @Test void recursiveFramesHaveDistinctIds() throws Exception {
        Path source = Path.of("src/test/resources/programs/recursion/Main.java");
        Path classes = temp.resolve("frames"), out = temp.resolve("frames-out");
        Files.createDirectories(classes);
        ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), source.toString());
        Instrumenter.instrumentDirectory(classes, out);
        Path trace = temp.resolve("frames.jsonl");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dcodeviz.trace=" + trace, "-cp", out + java.io.File.pathSeparator + Path.of("target/classes").toAbsolutePath(),
                "Main").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        List<Integer> frames = new ArrayList<>();
        for (String line : Files.readAllLines(trace)) {
            if (line.contains("\"kind\":\"ENTER\"")) frames.add(Integer.parseInt(field(line, "frame")));
        }
        assertEquals(List.of(1, 2, 3, 4), frames, "main plus three distinct fact frames");
    }

    // ---- helpers ---------------------------------------------------------------

    private static void assertContainsInOrder(List<String> events, String... expected) {
        int at = 0;
        for (String want : expected) {
            while (at < events.size() && !events.get(at).equals(want)) at++;
            assertTrue(at < events.size(), "missing in order: " + want + "\nevents: " + String.join("\n", events));
            at++;
        }
    }

    private static final Pattern VALUE = Pattern.compile("\\{\"t\":\"([^\"]+)\"(?:,\"id\":(\\d+))?(?:,\"v\":(\"[^\"]*\"|[^}]*))?\\}");

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\"[^\"]*\"|-?\\d+|true|false)").matcher(json);
        return m.find() ? m.group(1).replace("\"", "") : "";
    }

    private static String value(String json, String name) {
        int at = json.indexOf("\"" + name + "\":{");
        if (at < 0) return "";
        Matcher m = VALUE.matcher(json);
        if (!m.find(at + name.length() + 3)) return "";
        if (m.group(2) != null && !m.group(1).equals("String")) return "#" + m.group(2);
        if (m.group(1).equals("null")) return "null";
        return m.group(1) + ":" + (m.group(3) == null ? "" : m.group(3).replace("\"", ""));
    }

    /** Compact, presentation-free projection used only by these assertions. */
    static String project(String json) {
        String kind = field(json, "kind");
        return switch (kind) {
            case "ENTER" -> "ENTER " + field(json, "method");
            case "EXIT" -> json.contains("\"value\"") ? "EXIT " + value(json, "value") : "EXIT";
            case "THROW_EXIT" -> "THROW_EXIT " + field(json, "exception");
            case "LOCAL" -> "LOCAL " + field(json, "name") + "=" + value(json, "value");
            case "ARRAY_NEW" -> "ARRAY_NEW " + value(json, "array") + " len=" + field(json, "length");
            case "ARRAY_WRITE", "ARRAY_READ" -> kind + " " + value(json, "array") + "[" + field(json, "index") + "]="
                    + value(json, "value");
            case "COMPARE" -> "COMPARE " + field(json, "op") + " " + value(json, "left").replace("int:", "") + " "
                    + value(json, "right").replace("int:", "") + " jump=" + field(json, "jump");
            default -> json;
        };
    }

    @Test void projectionReadsRecorderJson() throws IOException {
        assertEquals("ARRAY_WRITE #1[2]=int:7", project(
                "{\"seq\":3,\"kind\":\"ARRAY_WRITE\",\"frame\":1,\"line\":4,\"array\":{\"t\":\"int[]\",\"id\":1},\"index\":2,\"value\":{\"t\":\"int\",\"v\":7}}"));
    }
}
