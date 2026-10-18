package com.codevisualization.backend.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One control worker, no queue, bounded retained output; not user-output transport. */
public final class BoundedCommandRunner implements CommandRunner, AutoCloseable {
    static final int OUTPUT_LIMIT = 65_536;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Process> active = new AtomicReference<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "docker-control");
                thread.setDaemon(true);
                return thread;
            });

    @Override
    public String execute(List<String> command, Duration remaining) throws IOException {
        if (remaining.isNegative() || remaining.isZero() || remaining.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IOException("Invalid control deadline");
        if (closed.get()) throw new IOException("Control runner closed; reconciliation required");
        List<String> arguments = List.copyOf(command);
        // Current fixed Docker commands need no embedded quotes. Reject ambiguity
        // instead of depending on Windows/JDK legacy argument reconstruction.
        if (arguments.stream().anyMatch(argument -> argument.indexOf('"') >= 0))
            throw new IOException("Embedded quotes are outside the control command contract");
        long deadline = System.nanoTime() + remaining.toNanos();
        Future<String> task;
        try {
            task = worker.submit(() -> invoke(arguments));
        } catch (RejectedExecutionException busy) {
            throw new IOException("Control worker unavailable; no queued calls", busy);
        }
        try {
            String result = task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (System.nanoTime() >= deadline) throw new TimeoutException("Late control completion");
            return result;
        } catch (TimeoutException timeout) {
            close();
            throw new IOException("Control deadline exceeded; daemon outcome unknown", timeout);
        } catch (InterruptedException interrupted) {
            close();
            Thread.currentThread().interrupt();
            throw new IOException("Control interrupted; daemon outcome unknown", interrupted);
        } catch (ExecutionException failed) {
            close();
            throw new IOException("Control command failed; reconciliation may be required", failed.getCause());
        }
    }

    private String invoke(List<String> arguments) throws IOException, InterruptedException {
        if (closed.get()) throw new IOException("Control runner closed");
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        active.set(process);
        try {
            if (closed.get()) throw new IOException("Control cancelled during launch");
            process.getOutputStream().close();
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            try (var input = process.getInputStream()) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > OUTPUT_LIMIT - output.size()) throw new IOException("Control output limit exceeded");
                    output.write(buffer, 0, count);
                }
            }
            if (process.waitFor() != 0) throw new IOException("Docker control exited unsuccessfully");
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            // Keep this worker occupied until its process actually exits; never replace it.
            process.onExit().join();
            active.compareAndSet(process, null);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        Process process = active.get();
        if (process != null && process.isAlive()) process.destroyForcibly();
        worker.shutdown();
    }
}
