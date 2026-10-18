package com.codevisualization.backend.runner;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
interface CommandRunner {
    String execute(List<String> command, Duration remaining) throws IOException;
    default String execute(List<String> command, Duration remaining, byte[] input) throws IOException {
        if (input != null) throw new IOException("This runner cannot write stdin");
        return execute(command, remaining);
    }
    default boolean settled() { return true; }
}
