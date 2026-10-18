package com.codevisualization.backend.runner;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunCancellationTest {
    @Test void acceptedCancellationCutsOffBytesAndCannotBeReplacedByExit() {
        var signal = new RunCancellation(); var output = new RunOutput();
        var gate = new PhaseGate(output, Duration.ofSeconds(5), () -> 0);
        signal.register(gate, true);
        gate.accept(false, new byte[]{65}, 1);
        assertTrue(signal.request()); assertFalse(signal.request());
        gate.accept(false, new byte[]{66}, 1);
        assertArrayEquals(new byte[]{65}, output.stdout());
        assertEquals(PhaseGate.Cause.CANCELLED, signal.completed(gate, 0));
    }

    @Test void completionOrEarlierLimitWinsButSuccessfulCompileIsNotTerminal() {
        for (var cause : new PhaseGate.Cause[]{PhaseGate.Cause.TIMEOUT, PhaseGate.Cause.OUTPUT_LIMIT,
                PhaseGate.Cause.TRANSPORT_FAILURE}) {
            var signal = new RunCancellation();
            var gate = new PhaseGate(new RunOutput(), Duration.ofSeconds(5), () -> 0);
            signal.register(gate, true); gate.finish(cause);
            assertFalse(signal.request()); assertEquals(cause, gate.cause());
        }
        for (int exit : new int[]{0, 1}) {
            var signal = new RunCancellation();
            var gate = new PhaseGate(new RunOutput(), Duration.ofSeconds(5), () -> 0);
            signal.register(gate, true); signal.completed(gate, exit);
            assertFalse(signal.request());
        }
        var compile = new RunCancellation();
        var gate = new PhaseGate(new RunOutput(), Duration.ofSeconds(10), () -> 0);
        compile.register(gate, false); compile.completed(gate, 0);
        assertTrue(compile.request());
        var next = new PhaseGate(new RunOutput(), Duration.ZERO, () -> 0);
        compile.register(next, true);
        assertEquals(PhaseGate.Cause.CANCELLED, next.cause());
    }

    @Test void failedCompileCannotBeOverwrittenByCancellation() {
        var signal = new RunCancellation();
        var gate = new PhaseGate(new RunOutput(), Duration.ofSeconds(10), () -> 0);
        signal.register(gate, false); signal.completed(gate, 1);
        assertFalse(signal.request());
    }
}
