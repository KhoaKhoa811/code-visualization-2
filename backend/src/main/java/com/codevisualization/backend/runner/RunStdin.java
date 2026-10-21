package com.codevisualization.backend.runner;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * What a run's standard input is (Parts 8g, 10a): closed at once, a live console, or text
 * given with the request. A program that never refers to System.in gets a closed standard
 * input in every mode.
 */
public record RunStdin(Mode mode, byte[] provided) {
    public enum Mode { NONE, LIVE, PROVIDED }

    public static final RunStdin NONE = new RunStdin(Mode.NONE, null), LIVE = new RunStdin(Mode.LIVE, null);

    public RunStdin {
        if ((mode == Mode.PROVIDED) != (provided != null)) throw new IllegalArgumentException("provided bytes exactly for PROVIDED");
        provided = provided == null ? null : provided.clone();
    }

    @Override public byte[] provided() { return provided == null ? null : provided.clone(); }

    /**
     * Admits test input given with the request: well-formed Unicode, at most the per-run input
     * bound of execution-isolation Part 4r (65,536 bytes of UTF-8), kept exactly as given.
     */
    public static RunStdin provided(String text) throws TracedRunLifecycle.AdmissionException {
        if (text == null) throw new TracedRunLifecycle.AdmissionException("INPUT_INVALID", "Input text is required");
        if (text.length() > RunInput.TOTAL_LIMIT) throw new TracedRunLifecycle.AdmissionException("INPUT_LIMIT", "Input exceeds 65536 bytes of UTF-8");
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if (buffer.remaining() > RunInput.TOTAL_LIMIT) throw new TracedRunLifecycle.AdmissionException("INPUT_LIMIT", "Input exceeds 65536 bytes of UTF-8");
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return new RunStdin(Mode.PROVIDED, bytes);
        } catch (CharacterCodingException malformed) {
            throw new TracedRunLifecycle.AdmissionException("INPUT_INVALID", "Input is not well-formed Unicode");
        }
    }

    /** Wire word of the mode. */
    public String wire() { return mode.name().toLowerCase(java.util.Locale.ROOT); }
}
