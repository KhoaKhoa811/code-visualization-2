package com.codevisualization.backend.runner;

import java.io.ByteArrayOutputStream;

/** One raw-byte budget shared by all sequential phases and both stream readers. */
final class RunOutput {
    static final int LIMIT = 1_048_576;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    synchronized boolean append(boolean error, byte[] bytes, int length) {
        if (length < 0 || length > bytes.length) throw new IllegalArgumentException("Invalid byte count");
        int accepted = Math.min(length, LIMIT - size());
        (error ? stderr : stdout).write(bytes, 0, accepted);
        return accepted < length;
    }

    synchronized int size() { return stdout.size() + stderr.size(); }
    synchronized byte[] stdout() { return stdout.toByteArray(); }
    synchronized byte[] stderr() { return stderr.toByteArray(); }
}
