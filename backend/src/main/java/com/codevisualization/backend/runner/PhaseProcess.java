package com.codevisualization.backend.runner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One-shot phase transport, separate from Docker control and its output quota. */
final class PhaseProcess {
    record Result(PhaseGate.Cause cause, Integer exitCode) { }
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Process> active = new AtomicReference<>();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), runnable -> {
                Thread thread = new Thread(runnable, "runner-phase");
                thread.setDaemon(true);
                return thread;
            });

    boolean settled() { return workers.isTerminated() && active.get() == null; }

    Result execute(List<String> command, RunOutput output, Duration budget) {
        return execute(command, output, budget, new RunCancellation(), true);
    }

    Result execute(List<String> command, RunOutput output, Duration budget,
                   RunCancellation cancellation, boolean finalPhase) {
        return execute(command, output, budget, cancellation, finalPhase, null);
    }

    /** {@code input}: live standard input of an interactive runtime phase (Part 8g), or null for none. */
    Result execute(List<String> command, RunOutput output, Duration budget,
                   RunCancellation cancellation, boolean finalPhase, RunInput input) {
        var gate = new PhaseGate(output, budget);
        cancellation.register(gate, finalPhase);
        try {
            List<String> arguments = List.copyOf(command);
            if (arguments.stream().anyMatch(argument -> argument.indexOf('"') >= 0))
                throw new IllegalArgumentException("Embedded quotes outside fixed command vocabulary");
            Future<Integer> completion = workers.submit(() -> invoke(arguments, gate, input));
            while (gate.cause() == null) {
                try {
                    int exit = completion.get(10, TimeUnit.MILLISECONDS);
                    PhaseGate.Cause cause = cancellation.completed(gate, exit);
                    return new Result(cause, cause == PhaseGate.Cause.EXIT ? exit : null);
                } catch (TimeoutException pending) {
                    // Poll only the monotonic gate; activity cannot extend the phase.
                }
            }
            return new Result(gate.cause(), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(gate.finish(PhaseGate.Cause.TRANSPORT_FAILURE), null);
        } catch (ExecutionException | RuntimeException failure) {
            return new Result(gate.finish(PhaseGate.Cause.TRANSPORT_FAILURE), null);
        } finally {
            if (input != null) input.close(); // the stop cutoff: unwritten input is discarded
            closed.set(true);
            Process process = active.get();
            if (process != null && process.isAlive()) process.destroyForcibly();
            workers.shutdown();
        }
    }

    private int invoke(List<String> command, PhaseGate gate, RunInput input) throws Exception {
        if (closed.get() || gate.cause() != null) throw new IOException("Phase launch fenced");
        Process process = new ProcessBuilder(command).start();
        active.set(process);
        try {
            if (closed.get() || gate.cause() != null) throw new IOException("Late phase launch fenced");
            if (input == null) process.getOutputStream().close(); // no input: a read sees end of input at once
            else workers.submit(() -> feed(process, input));
            Future<?> stdout = workers.submit(() -> drain(process.getInputStream(), false, gate));
            Future<?> stderr = workers.submit(() -> drain(process.getErrorStream(), true, gate));
            int exit = process.waitFor();
            stdout.get();
            stderr.get();
            return exit; // Both streams are drained before a later phase may launch.
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.onExit().join();
            active.compareAndSet(process, null);
        }
    }

    /** Writes accepted lines in order; closes standard input at EOF or when the run stops. */
    private static void feed(Process process, RunInput input) {
        try (OutputStream stdin = process.getOutputStream()) {
            byte[] line;
            while ((line = input.next()) != null) {
                stdin.write(line);
                stdin.flush();
                input.written(line);
            }
        } catch (IOException | InterruptedException ended) {
            // The process ended or the run stopped: nothing more is written; never retry an uncertain write.
        }
    }

    private void drain(InputStream input, boolean error, PhaseGate gate) {
        try (input) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = input.read(bytes)) != -1) {
                gate.accept(error, bytes, count);
                if (gate.cause() != null) return;
            }
        } catch (IOException failure) {
            gate.finish(PhaseGate.Cause.TRANSPORT_FAILURE);
        }
    }
}
