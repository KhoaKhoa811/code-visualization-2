package com.codevisualization.backend.runner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 7c live checks: real traced runs inside the owned restricted container.
 * Every trace must pass the shared trace-v1 checker, and every run must confirm
 * whole-container termination and removal.
 */
@EnabledIfSystemProperty(named = "codeviz.docker.live", matches = "true")
class TracedRunLiveTest {
    private static Path docker() { return Path.of(System.getProperty("codeviz.docker.executable")); }

    private static String program(String body) {
        return "public class Main {\n    public static void main(String[] args) {\n" + body + "\n    }\n}\n";
    }

    private static TracedRunLifecycle.Result run(String name, String source) throws Exception {
        var result = new TracedRunLifecycle(docker()).run(source);
        System.out.println("Traced " + name + ": " + result.runId());
        assertTrue(result.terminationConfirmed(), result.infrastructureFailures().toString());
        assertTrue(result.cleanupVerified(), result.infrastructureFailures().toString());
        assertTrue(result.infrastructureFailures().isEmpty(), result.infrastructureFailures().toString());
        assertNotNull(result.trace());
        check(name, result.trace());
        return result;
    }

    static void check(String name, String trace) throws Exception {
        Path dir = Path.of("target", "live-traces");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".json");
        Files.writeString(file, trace, StandardCharsets.UTF_8);
        Process node = new ProcessBuilder("node", Path.of("../contracts/trace/check.cjs").toString(), file.toString())
                .redirectErrorStream(true).start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(node.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, node.exitValue(), "trace-v1 check failed: " + output);
    }

    private static String stdout(TracedRunLifecycle.Result r) { return new String(r.stdout(), StandardCharsets.UTF_8); }

    @Test void bubbleSortIsTracedInsideTheContainer() throws Exception {
        var r = run("bubble-sort", program("""
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
                for (int k = 0; k < a.length; k++) System.out.println(a[k]);"""));
        assertEquals("1\n2\n3\n", stdout(r));
        assertTrue(r.trace().contains("\"execution\":{\"status\":\"completed\""));
        assertTrue(r.trace().contains("\"capture\":{\"status\":\"complete\""));
        assertTrue(r.trace().contains("\"operator\":\">\""));
    }

    @Test void uncaughtFailureIsCompleteAndFailed() throws Exception {
        var r = run("failed-write", program("int[] a = {0};\nint x = 0;\na[2] = ++x;"));
        assertTrue(r.trace().contains("\"status\":\"failed\"") && r.trace().contains("\"status\":\"complete\""));
        assertTrue(r.trace().contains("ArrayIndexOutOfBoundsException"));
        assertTrue(new String(r.stderr(), StandardCharsets.UTF_8).contains("ArrayIndexOutOfBoundsException"));
    }

    @Test void outputOnlyAndCompileErrorPaths() throws Exception {
        // Threads keep a program output-only (Part 8a integrity guard).
        var threads = run("output-only", program("System.out.println(Thread.currentThread() != null);"));
        assertEquals("true\n", stdout(threads));
        assertTrue(threads.trace().contains("\"status\":\"unavailable\"") && threads.trace().contains("TRACE_INTEGRITY"));
        var broken = run("compile-error", program("int x = ;"));
        assertTrue(broken.trace().contains("\"status\":\"compile_error\""));
    }

    @Test void helperClassesAndUncapturedStatementsRunInTheContainer() throws Exception {
        var r = run("partial-coverage", """
                public class Main {
                    static class Box { int[] data; Box(int[] d) { data = d; } }
                    static void bump(int[] a) { a[0] += 10; }
                    public static void main(String[] args) {
                        int[] a = {3, 1, 2};
                        java.util.Arrays.sort(a);
                        bump(a);
                        Box box = new Box(a);
                        int total = 0;
                        for (int i = 0; i < a.length; i++) total += a[i];
                        System.out.println(a[0] + " " + total + " " + box.data.length);
                    }
                }
                """);
        assertEquals("11 16 3\n", stdout(r));
        assertTrue(r.trace().contains("\"status\":\"completed\"") && r.trace().contains("\"status\":\"complete\""));
        assertTrue(r.trace().contains("\"kind\":\"UNCAPTURED\""));
        assertTrue(r.trace().contains("{\"objectId\":\"o2\",\"elements\":[{\"kind\":\"int\",\"value\":\"1\"}"), "sorted contents re-read");
        // Part 8d: bump is a traced static helper of Main; its call and implicit return are steps.
        assertTrue(r.trace().contains("\"methodId\":\"Main.bump(int[]):void\""), "traced helper call");
        assertTrue(r.trace().contains("\"kind\":\"RETURN\""), "traced helper return");
    }

    @Test void runtimeTimeoutKeepsTheSafePrefix() throws Exception {
        var r = run("timeout", program("int n = 1;\nwhile (true) { }"));
        assertTrue(r.trace().contains("\"status\":\"limited\"") && r.trace().contains("EXECUTION_TIMEOUT"));
        assertTrue(r.trace().contains("\"status\":\"partial\"") && r.trace().contains("\"kind\":\"DECLARE\""));
    }

    @Test void rawRecordLimitHaltsTheProgram() throws Exception {
        var r = run("raw-limit", program("int i = 0;\nwhile (true) { i++; }"));
        assertTrue(r.trace().contains("\"status\":\"limited\"") && r.trace().contains("TRACE_LIMIT"), r.trace().substring(0, 600));
        assertTrue(r.trace().contains("\"status\":\"partial\""));
    }

    @Test void cancellationDuringRuntimeStopsAndCleansUp() throws Exception {
        var lifecycle = new TracedRunLifecycle(docker());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TracedRunLifecycle.Result> future = executor.submit(() -> lifecycle.run(program("int n = 1;\nwhile (true) { }")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!lifecycle.progress().phase().equals("executing") && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals("executing", lifecycle.progress().phase());
            Thread.sleep(300); // let the JVM start and record main entry
            assertTrue(lifecycle.cancel());
            var r = future.get(20, TimeUnit.SECONDS);
            assertTrue(r.terminationConfirmed() && r.cleanupVerified(), r.infrastructureFailures().toString());
            check("cancelled", r.trace());
            assertTrue(r.trace().contains("\"status\":\"cancelled\"") && r.trace().contains("RUN_CANCELLED"));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    static final String DOUBLER = """
            import java.util.Scanner;

            public class Main {
                public static void main(String[] args) {
                    Scanner in = new Scanner(System.in);
                    System.out.print("Enter n: ");
                    int n = in.nextInt();
                    System.out.println(n * 2);
                }
            }
            """;

    /** An interactive run that receives one live line, then end of input; its trace passes the checker. */
    private static TracedRunLifecycle.Result interactive(String name, String source, String line) throws Exception {
        var lifecycle = new TracedRunLifecycle(docker());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TracedRunLifecycle.Result> future = executor.submit(() -> lifecycle.run(source, true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((lifecycle.input() == null || !lifecycle.input().accepting()) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(lifecycle.interactive());
            assertEquals(RunInput.Outcome.ACCEPTED, lifecycle.input().offer(1, line));
            lifecycle.input().eof();
            var r = future.get(60, TimeUnit.SECONDS);
            assertTrue(r.terminationConfirmed() && r.cleanupVerified(), r.infrastructureFailures().toString());
            check(name, r.trace());
            return r;
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    /** AC-19 inside the container: a prompt, one line of live input, the doubled value; Scanner reads replayed. */
    @Test void liveInputReachesAnInteractiveRun() throws Exception {
        var r = interactive("interactive", DOUBLER, "21");
        assertEquals("Enter n: 42\n", stdout(r));
        assertTrue(r.trace().contains("\"status\":\"completed\"") && r.trace().contains("java.util.Scanner.nextInt()"), r.trace());
        // Part 8i: a BufferedReader over System.in reads the same live line.
        var reader = interactive("interactive-reader", """
                import java.io.*;

                public class Main {
                    public static void main(String[] args) throws IOException {
                        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                        System.out.print("Name: ");
                        String name = in.readLine();
                        System.out.println("Hello " + name);
                    }
                }
                """, "Ada");
        assertEquals("Name: Hello Ada\n", stdout(reader));
        assertTrue(reader.trace().contains("\"status\":\"completed\"") && reader.trace().contains("java.io.BufferedReader.readLine()"), reader.trace());
        // Without a live console the same program sees end of input at once (Java's own exception).
        var closed = run("no-console", DOUBLER);
        assertTrue(closed.trace().contains("\"status\":\"failed\"") && closed.trace().contains("java.util.NoSuchElementException"));
    }

    /** Part 8h inside the container: a helper throws, main catches, finally runs on both paths. */
    @Test void exceptionsAreThrownCaughtAndUnwoundInTheContainer() throws Exception {
        var r = run("exceptions", """
                public class Main {
                    static class InsufficientFunds extends RuntimeException {
                        InsufficientFunds(String message) { super(message); }
                    }
                    static int withdraw(int balance, int amount) {
                        if (amount > balance) throw new InsufficientFunds("short by " + (amount - balance));
                        return balance - amount;
                    }
                    public static void main(String[] args) {
                        int balance = 100;
                        int[] requests = {30, 500, 20};
                        for (int amount : requests) {
                            try {
                                balance = withdraw(balance, amount);
                            } catch (InsufficientFunds e) {
                                System.out.println("refused: " + e.getMessage());
                            } finally {
                                System.out.println("balance " + balance);
                            }
                        }
                    }
                }
                """);
        assertEquals("balance 70\nrefused: short by 430\nbalance 70\nbalance 50\n", stdout(r));
        assertTrue(r.trace().contains("\"execution\":{\"status\":\"completed\"") && r.trace().contains("\"capture\":{\"status\":\"complete\""));
        assertTrue(r.trace().contains("\"kind\":\"CATCH\"") && r.trace().contains("\"className\":\"Main$InsufficientFunds\""));
        assertTrue(r.trace().contains("\"reason\":\"unwind\""), "the helper's frame unwinds when the exception leaves it");
    }

    @Test void noOwnedContainerRemainsAfterTracedRuns() throws Exception {
        bubbleSortIsTracedInsideTheContainer();
        try (var control = new BoundedCommandRunner()) {
            String owned = control.execute(List.of(docker().toString(), "--context", "desktop-linux", "ps", "--all",
                    "--no-trunc", "--filter", "label=" + ContainerProfile.LABEL, "--format", "{{.ID}}"), Duration.ofSeconds(5));
            assertTrue(owned.isBlank(), "owned containers left: " + owned);
        }
    }
}
