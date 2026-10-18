package com.codevisualization.backend.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BoundedCommandRunnerTest {
    @TempDir Path directory;

    private List<String> command(String... args) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), ControlProcessFixture.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    @Test void passesLiteralArgumentsWithoutShellExpansion() throws Exception {
        try (var runner = new BoundedCommandRunner()) {
            String literal = "spaces & $(never-run)";
            assertEquals(literal, runner.execute(command("echo", literal), Duration.ofSeconds(5)));
            assertThrows(IOException.class, () -> runner.execute(command("echo", "\"quote\""), Duration.ofSeconds(5)));
        }
    }

    @Test void acceptsExactOutputCapAndRejectsOverflow() throws Exception {
        try (var runner = new BoundedCommandRunner()) {
            assertEquals(65_536, runner.execute(command("flood", "65536"), Duration.ofSeconds(5)).length());
        }
        try (var runner = new BoundedCommandRunner()) {
            assertThrows(IOException.class, () -> runner.execute(command("flood", "65537"), Duration.ofSeconds(5)));
            assertThrows(IOException.class, () -> runner.execute(command("echo", "no retry"), Duration.ofSeconds(5)));
        }
    }

    @Test void nonzeroExitAndInvalidBudgetFail() {
        try (var runner = new BoundedCommandRunner()) {
            assertThrows(IOException.class, () -> runner.execute(command("echo", "x"), Duration.ZERO));
            assertThrows(IOException.class, () -> runner.execute(command("echo", "x"), Duration.ofSeconds(11)));
            assertThrows(IOException.class, () -> runner.execute(command("fail"), Duration.ofSeconds(5)));
        }
    }

    @Test void timeoutBoundsCallerKillsLocalProcessAndClosesRunner() throws Exception {
        Path pidFile = directory.resolve("pid");
        try (var runner = new BoundedCommandRunner()) {
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> runner.execute(command("wait", pidFile.toString()), Duration.ofSeconds(2)));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
            assertTrue(Files.exists(pidFile), "Fixture must start before testing timeout");
            var process = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
            if (process.isPresent()) process.get().onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(process.isEmpty() || !process.get().isAlive());
            assertThrows(IOException.class, () -> runner.execute(command("echo", "no retry"), Duration.ofSeconds(5)));
        }
    }

    @Test void busyRunnerRejectsCallsWithoutQueueing() throws Exception {
        Path pidFile = directory.resolve("busy-pid");
        try (var runner = new BoundedCommandRunner()) {
            var first = java.util.concurrent.CompletableFuture.runAsync(() ->
                    assertThrows(IOException.class, () -> runner.execute(command("wait", pidFile.toString()), Duration.ofSeconds(3))));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile));
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> runner.execute(command("echo", "must not queue"), Duration.ofSeconds(5)));
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos());
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
