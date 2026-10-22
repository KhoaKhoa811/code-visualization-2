package com.codevisualization.backend.runner;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Part 8g input bounds and ordering (execution-isolation Part 4r). */
class RunInputTest {
    private static RunInput open() { var input = new RunInput(); input.open(); return input; }

    private static String drain(RunInput input) throws InterruptedException {
        byte[] line;
        StringBuilder all = new StringBuilder();
        while ((line = input.next()) != null) { input.written(line); all.append(new String(line, StandardCharsets.UTF_8)); }
        return all.toString();
    }

    @Test void linesAreAcceptedInSequenceAndWrittenInOrder() throws Exception {
        var input = open();
        assertEquals(RunInput.Outcome.ACCEPTED, input.offer(1, "21"));
        assertEquals(RunInput.Outcome.ACCEPTED, input.offer(2, "  Ada  "));
        assertEquals(RunInput.Outcome.DUPLICATE, input.offer(1, "21"), "a retried line is acknowledged, not written twice");
        assertEquals(RunInput.Outcome.INPUT_SEQUENCE, input.offer(1, "22"), "a conflicting retry is rejected");
        assertEquals(RunInput.Outcome.INPUT_SEQUENCE, input.offer(4, "x"), "a gap is rejected");
        assertEquals(RunInput.Outcome.ACCEPTED, input.offer(3, ""), "an empty Enter sends LF");
        input.eof();
        assertEquals("21\n  Ada  \n\n", drain(input));
        assertEquals("21\n  Ada  \n\n", new String(input.transcript(), StandardCharsets.UTF_8));
        assertFalse(input.accepting());
        assertEquals(RunInput.Outcome.INPUT_CLOSED, input.offer(4, "late"));
    }

    @Test void boundsRejectWithoutWritingAPrefix() {
        var input = open();
        assertEquals(RunInput.Outcome.INPUT_INVALID, input.offer(1, "a\nb"));
        assertEquals(RunInput.Outcome.INPUT_INVALID, input.offer(1, "a\rb"));
        assertEquals(RunInput.Outcome.INPUT_INVALID, input.offer(1, "\uD800"), "malformed Unicode");
        assertEquals(RunInput.Outcome.ACCEPTED, input.offer(1, "x".repeat(RunInput.LINE_LIMIT - 1)), "exactly 4 KiB with LF fits");
        assertEquals(RunInput.Outcome.INPUT_LINE_LIMIT, input.offer(2, "x".repeat(RunInput.LINE_LIMIT)));
        for (int s = 2; s <= 4; s++) assertEquals(RunInput.Outcome.ACCEPTED, input.offer(s, "y".repeat(RunInput.LINE_LIMIT - 1)));
        assertEquals(RunInput.Outcome.INPUT_PENDING_LIMIT, input.offer(5, "z"), "16 KiB unwritten is the backpressure bound");
        assertEquals(0, input.transcript().length, "nothing was written yet");
    }

    /** Part 10a: provided input is written exactly as given (CR and long lines included), then closed. */
    @Test void providedInputIsWrittenAsGivenThenClosed() throws Exception {
        String text = "a\r\n" + "x".repeat(RunInput.LINE_LIMIT + 10) + "\nlast without LF";
        var input = RunInput.provided(text.getBytes(StandardCharsets.UTF_8));
        input.open();
        assertFalse(input.accepting(), "no live input next to provided input");
        assertEquals(RunInput.Outcome.INPUT_CLOSED, input.offer(1, "more"));
        assertEquals(text, drain(input));
        assertEquals(text, new String(input.transcript(), StandardCharsets.UTF_8));
        var empty = RunInput.provided(new byte[0]);
        empty.open();
        assertNull(empty.next(), "empty input is end of input at once");
    }

    @Test void totalInputIsBoundedAndStoppingDiscardsPendingInput() throws Exception {
        var input = open();
        long seq = 1;
        int lines = RunInput.TOTAL_LIMIT / RunInput.LINE_LIMIT;
        for (int i = 0; i < lines; i++) {
            assertEquals(RunInput.Outcome.ACCEPTED, input.offer(seq++, "x".repeat(RunInput.LINE_LIMIT - 1)));
            input.written(input.next());
        }
        assertEquals(RunInput.Outcome.INPUT_TOTAL_LIMIT, input.offer(seq, "y"), "64 KiB per run");
        var stopped = open();
        assertEquals(RunInput.Outcome.ACCEPTED, stopped.offer(1, "never"));
        stopped.close();
        assertNull(stopped.next(), "the stop cutoff discards pending input");
        assertEquals(0, stopped.transcript().length);
        var notYet = new RunInput();
        assertEquals(RunInput.Outcome.INPUT_CLOSED, notYet.offer(1, "early"), "input is admitted only while executing");
    }
}
