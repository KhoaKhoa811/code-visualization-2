package codeviz.tracer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Test-only host execution of trusted programs as a separate JVM process. This is
 * not the isolated runner (Part 7c); it exists to compare behavior and produce traces.
 */
final class HostRun {
    record Result(int exit, String stdout, String stderr, List<String> raw) {
        String stderrFirstLine() { return stderr.lines().findFirst().orElse(""); }
    }

    static Result run(Map<String, byte[]> classes, boolean traced, Path dir) throws Exception {
        return run(classes, traced, dir, null);
    }

    /** {@code input}: standard input bytes followed by end of input (Part 8g); null reads nothing. */
    static Result run(Map<String, byte[]> classes, boolean traced, Path dir, byte[] input) throws Exception {
        Path out = Files.createTempDirectory(dir, "classes");
        for (var entry : classes.entrySet()) Files.write(out.resolve(entry.getKey() + ".class"), entry.getValue());
        Path raw = out.resolve("raw.trace");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m"));
        String classpath = out.toString();
        if (traced) {
            command.add("-Dcodeviz.trace=" + raw);
            classpath += File.pathSeparator + Path.of("target/classes").toAbsolutePath();
        }
        command.addAll(List.of("-cp", classpath, "Main"));
        Process process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(input == null ? nullFile() : stdin(out, input))).start();
        var stdout = process.getInputStream();
        var stderr = process.getErrorStream();
        byte[][] streams = new byte[2][];
        Thread errReader = Thread.ofVirtual().start(() -> {
            try { streams[1] = stderr.readAllBytes(); } catch (Exception e) { streams[1] = new byte[0]; }
        });
        streams[0] = stdout.readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("timeout"); }
        errReader.join();
        String err = new String(streams[1], StandardCharsets.UTF_8);
        List<String> lines = traced && Files.exists(raw) ? Files.readAllLines(raw) : List.of();
        return new Result(process.exitValue(), new String(streams[0], StandardCharsets.UTF_8).replace("\r", ""),
                err.replace("\r", ""), lines);
    }

    private static File stdin(Path dir, byte[] input) throws java.io.IOException {
        Path file = dir.resolve("stdin.txt");
        Files.write(file, input);
        return file.toFile();
    }

    private static File nullFile() {
        return new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }
}
