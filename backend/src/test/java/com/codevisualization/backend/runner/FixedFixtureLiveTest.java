package com.codevisualization.backend.runner;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "codeviz.docker.live", matches = "true")
class FixedFixtureLiveTest {
    @ParameterizedTest @EnumSource(FixedFixture.class)
    void verifiesFixtureOutcomeAndCleanup(FixedFixture fixture) {
        var result = new FixedFixtureLifecycle(Path.of(System.getProperty("codeviz.docker.executable"))).run(fixture);
        System.out.println("Fixture " + fixture + ": " + result.name() + " " + result.outcome());
        assertTrue(result.terminationConfirmed(), result.toString());
        assertTrue(result.cleanupVerified(), result.toString());
        assertTrue(result.infrastructureFailures().isEmpty(), result.toString());
        var expected = switch (fixture) {
            case NORMAL, OUTPUT_BELOW, OUTPUT_EXACT -> FixedFixtureLifecycle.Outcome.COMPLETED;
            case COMPILE_ERROR -> FixedFixtureLifecycle.Outcome.COMPILE_NONZERO;
            case COMPILE_TIMEOUT -> FixedFixtureLifecycle.Outcome.COMPILATION_TIMEOUT;
            case RUNTIME_TIMEOUT -> FixedFixtureLifecycle.Outcome.EXECUTION_TIMEOUT;
            default -> FixedFixtureLifecycle.Outcome.OUTPUT_LIMIT;
        };
        assertEquals(expected, result.outcome());
        assertEquals(fixture != FixedFixture.COMPILE_ERROR && fixture != FixedFixture.COMPILE_TIMEOUT
                && fixture != FixedFixture.COMPILE_OUTPUT_OVER, result.runtimeRequested());
        if (fixture == FixedFixture.NORMAL) {
            assertEquals("runner-ok\n", new String(result.stdout(), StandardCharsets.UTF_8));
            assertEquals(0, result.stderr().length);
        }
        if (expected == FixedFixtureLifecycle.Outcome.OUTPUT_LIMIT || fixture == FixedFixture.OUTPUT_EXACT)
            assertEquals(RunOutput.LIMIT, result.stdout().length + result.stderr().length);
        if (fixture == FixedFixture.OUTPUT_BELOW) assertEquals(RunOutput.LIMIT - 1, result.stdout().length);
        if (fixture == FixedFixture.OUTPUT_PHASES) assertArrayEquals(new byte[]{67}, result.stderr());
        if (fixture == FixedFixture.OUTPUT_STDERR) assertEquals(0, result.stdout().length);
        if (fixture == FixedFixture.OUTPUT_MULTIBYTE) {
            byte[] bytes = result.stdout();
            for (int i = 0; i < bytes.length; i++) assertEquals(new byte[]{(byte) 0xe2, (byte) 0x82, (byte) 0xac}[i % 3], bytes[i]);
        }
    }
}
