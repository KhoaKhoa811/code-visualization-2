package com.codevisualization.backend.runner;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
interface CommandRunner {
    String execute(List<String> command, Duration remaining) throws IOException;
}
