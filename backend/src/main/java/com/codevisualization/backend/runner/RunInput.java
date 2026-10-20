package com.codevisualization.backend.runner;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live standard input of one interactive run (Part 8g, execution-isolation Part 4r).
 * Whole lines are admitted in sequence order within fixed bounds while the run
 * executes, written in order by the phase transport, and recorded as written so
 * the trace builder can replay Scanner reads over exactly those bytes.
 * Acceptance means "will be written in order", not "was read by the program".
 */
public final class RunInput {
    public static final int LINE_LIMIT = 4096, TOTAL_LIMIT = 65_536, PENDING_LIMIT = 16_384;
    private static final int REMEMBERED = 64; // accepted sequence numbers kept for duplicate acknowledgement

    /** Outcome of one input message; anything but ACCEPTED and DUPLICATE is a rejection code. */
    public enum Outcome { ACCEPTED, DUPLICATE, INPUT_CLOSED, INPUT_LINE_LIMIT, INPUT_TOTAL_LIMIT, INPUT_PENDING_LIMIT,
        INPUT_SEQUENCE, INPUT_INVALID }

    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private final Map<Long, String> recent = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) { return size() > REMEMBERED; }
    };
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private int pendingBytes, acceptedBytes;
    private long nextSequence = 1;
    private boolean open, eof, closed;

    /** The runtime phase started: input may be admitted from now on. */
    synchronized void open() { if (!closed) open = true; }

    public synchronized boolean accepting() { return open && !eof && !closed; }

    public synchronized Outcome offer(long sequence, String text) {
        if (text == null) return Outcome.INPUT_INVALID;
        if (sequence < nextSequence) return text.equals(recent.get(sequence)) ? Outcome.DUPLICATE : Outcome.INPUT_SEQUENCE;
        if (sequence > nextSequence) return Outcome.INPUT_SEQUENCE;
        if (!accepting()) return Outcome.INPUT_CLOSED;
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || !wellFormed(text)) return Outcome.INPUT_INVALID;
        byte[] line = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > LINE_LIMIT) return Outcome.INPUT_LINE_LIMIT;
        if (acceptedBytes + line.length > TOTAL_LIMIT) return Outcome.INPUT_TOTAL_LIMIT;
        if (pendingBytes + line.length > PENDING_LIMIT) return Outcome.INPUT_PENDING_LIMIT;
        pending.addLast(line);
        pendingBytes += line.length;
        acceptedBytes += line.length;
        recent.put(sequence, text);
        nextSequence++;
        notifyAll();
        return Outcome.ACCEPTED;
    }

    /** End of input: standard input closes once every accepted line is written. Repeating it is harmless. */
    public synchronized void eof() {
        if (!open || closed) return;
        eof = true;
        notifyAll();
    }

    /** The run stopped: unwritten input is discarded and nothing more is written. */
    synchronized void close() {
        closed = true;
        pending.clear();
        pendingBytes = 0;
        notifyAll();
    }

    /** Writer side: the next line, or null when input ends (EOF after draining, or closed). */
    synchronized byte[] next() throws InterruptedException {
        while (pending.isEmpty() && !eof && !closed) wait();
        if (closed) return null;
        return pending.isEmpty() ? null : pending.peekFirst();
    }

    /** Writer side: the line returned by {@link #next()} reached the program's standard input. */
    synchronized void written(byte[] line) {
        if (closed || pending.peekFirst() != line) return;
        pending.removeFirst();
        pendingBytes -= line.length;
        written.writeBytes(line);
    }

    /** Every byte written to the program's standard input, in order. */
    public synchronized byte[] transcript() { return written.toByteArray(); }

    private static boolean wellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return false;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
