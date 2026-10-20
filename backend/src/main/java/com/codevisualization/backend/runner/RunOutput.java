package com.codevisualization.backend.runner;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** One raw-byte budget shared by all sequential phases and both stream readers. */
public final class RunOutput {
    static final int LIMIT = 1_048_576;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    /** Accepted bytes in arrival order, for the live console (Part 8g). */
    public record Chunk(boolean error, byte[] bytes) { }
    private final List<Chunk> chunks = new ArrayList<>();

    synchronized boolean append(boolean error, byte[] bytes, int length) {
        if (length < 0 || length > bytes.length) throw new IllegalArgumentException("Invalid byte count");
        int accepted = Math.min(length, LIMIT - size());
        (error ? stderr : stdout).write(bytes, 0, accepted);
        if (accepted > 0) chunks.add(new Chunk(error, java.util.Arrays.copyOf(bytes, accepted)));
        return accepted < length;
    }

    synchronized int size() { return stdout.size() + stderr.size(); }
    synchronized List<Chunk> since(int index) { return List.copyOf(chunks.subList(Math.min(index, chunks.size()), chunks.size())); }
    synchronized byte[] stdout() { return stdout.toByteArray(); }
    synchronized byte[] stderr() { return stderr.toByteArray(); }
}
