package com.codevisualization.backend.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PhaseProcessTest {
    @TempDir Path directory;
    private List<String> command(String... arguments) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), ControlProcessFixture.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    @Test void drainsSeparateRawStreamsBeforeSuccessAndClosesInput() {
        var output = new RunOutput();
        var result = new PhaseProcess().execute(command("both"), output, Duration.ofSeconds(5));
        assertEquals(PhaseGate.Cause.EXIT, result.cause()); assertEquals(0, result.exitCode());
        assertArrayEquals(new byte[]{(byte) 0xe2, (byte) 0x82}, output.stdout());
        assertArrayEquals("error".getBytes(java.nio.charset.StandardCharsets.UTF_8), output.stderr());
        var inputOutput = new RunOutput();
        assertEquals(0, new PhaseProcess().execute(command("stdin"), inputOutput, Duration.ofSeconds(5)).exitCode());
        assertArrayEquals(new byte[]{'-', '1'}, inputOutput.stdout());
    }

    @Test void fastExitCannotHideBufferedOverflow() {
        var output = new RunOutput();
        var result = new PhaseProcess().execute(command("flood", "1048577"), output, Duration.ofSeconds(5));
        assertEquals(PhaseGate.Cause.OUTPUT_LIMIT, result.cause());
        assertNull(result.exitCode()); assertEquals(RunOutput.LIMIT, output.size());
        var exact = new RunOutput();
        assertEquals(PhaseGate.Cause.EXIT, new PhaseProcess().execute(command("flood", "1048576"), exact, Duration.ofSeconds(5)).cause());
        assertEquals(RunOutput.LIMIT, exact.size());
    }

    @Test void distinguishesExitFailureFromTransportFailure() {
        var failure = new PhaseProcess().execute(command("fail"), new RunOutput(), Duration.ofSeconds(5));
        assertEquals(PhaseGate.Cause.EXIT, failure.cause()); assertEquals(7, failure.exitCode());
        var missing = new PhaseProcess().execute(List.of(directory.resolve("missing-executable").toString()), new RunOutput(), Duration.ofSeconds(5));
        assertEquals(PhaseGate.Cause.TRANSPORT_FAILURE, missing.cause());
    }

    @Test void deadlineStopsLocalCliAndDoesNotWaitForThirtySecondFixture() throws Exception {
        Path pid = directory.resolve("phase-pid");
        long start = System.nanoTime();
        var result = new PhaseProcess().execute(command("wait", pid.toString()), new RunOutput(), Duration.ofSeconds(2));
        assertEquals(PhaseGate.Cause.TIMEOUT, result.cause());
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos());
        assertTrue(Files.exists(pid));
        var process = ProcessHandle.of(Long.parseLong(Files.readString(pid)));
        if (process.isPresent()) process.get().onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(process.isEmpty() || !process.get().isAlive());
    }
}
