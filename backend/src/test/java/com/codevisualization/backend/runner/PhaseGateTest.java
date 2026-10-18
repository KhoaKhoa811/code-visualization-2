package com.codevisualization.backend.runner;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhaseGateTest {
    @Test void quotaIsSharedAcrossStreamsAndPhasesAndExactCapSucceeds() {
        var output = new RunOutput();
        var clock = new AtomicLong();
        var compile = new PhaseGate(output, Duration.ofSeconds(10), clock::get);
        compile.accept(true, new byte[]{67}, 1);
        assertEquals(PhaseGate.Cause.EXIT, compile.finish(PhaseGate.Cause.EXIT));
        var run = new PhaseGate(output, Duration.ofSeconds(5), clock::get);
        run.accept(false, new byte[RunOutput.LIMIT - 1], RunOutput.LIMIT - 1);
        assertNull(run.cause());
        assertEquals(RunOutput.LIMIT, output.size());
        assertArrayEquals(new byte[]{67}, output.stderr());
        run.accept(false, new byte[]{65}, 1);
        assertEquals(PhaseGate.Cause.OUTPUT_LIMIT, run.finish(PhaseGate.Cause.EXIT));
        assertEquals(RunOutput.LIMIT, output.size());
    }

    @Test void preservesRawPartialMultibytePrefixAndRejectsLaterBytes() {
        var output = new RunOutput();
        var gate = new PhaseGate(output, Duration.ofSeconds(5), () -> 0);
        gate.accept(false, new byte[RunOutput.LIMIT - 1], RunOutput.LIMIT - 1);
        gate.accept(false, new byte[]{(byte) 0xe2, (byte) 0x82, (byte) 0xac}, 3);
        gate.accept(true, new byte[]{66}, 1);
        assertEquals((byte) 0xe2, output.stdout()[RunOutput.LIMIT - 1]);
        assertEquals(0, output.stderr().length);
        assertEquals(PhaseGate.Cause.OUTPUT_LIMIT, gate.cause());
    }

    @Test void deadlineBoundaryWinsButNeverReplacesEarlierCause() {
        var clock = new AtomicLong();
        var output = new RunOutput();
        var gate = new PhaseGate(output, Duration.ofNanos(10), clock::get);
        clock.set(10);
        gate.accept(false, new byte[]{65}, 1);
        assertEquals(0, output.size());
        assertEquals(PhaseGate.Cause.TIMEOUT, gate.finish(PhaseGate.Cause.EXIT));
        var earlier = new PhaseGate(output, Duration.ofNanos(10), clock::get);
        earlier.finish(PhaseGate.Cause.TRANSPORT_FAILURE);
        clock.set(30);
        assertEquals(PhaseGate.Cause.TRANSPORT_FAILURE, earlier.cause());
    }
}
