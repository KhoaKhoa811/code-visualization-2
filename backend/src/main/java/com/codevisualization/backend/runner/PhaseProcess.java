package com.codevisualization.backend.runner;

import java.io.IOException;
import java.io.InputStream;
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
    private final ExecutorService workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(3), runnable -> {
                Thread thread = new Thread(runnable, "runner-phase");
                thread.setDaemon(true);
                return thread;
            });

    Result execute(List<String> command, RunOutput output, Duration budget) {
        var gate = new PhaseGate(output, budget);
        try {
            List<String> arguments = List.copyOf(command);
            if (arguments.stream().anyMatch(argument -> argument.indexOf('"') >= 0))
                throw new IllegalArgumentException("Embedded quotes outside fixed command vocabulary");
            Future<Integer> completion = workers.submit(() -> invoke(arguments, gate));
            while (gate.cause() == null) {
                try {
                    int exit = completion.get(10, TimeUnit.MILLISECONDS);
                    PhaseGate.Cause cause = gate.finish(PhaseGate.Cause.EXIT);
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
            closed.set(true);
            Process process = active.get();
            if (process != null && process.isAlive()) process.destroyForcibly();
            workers.shutdown();
        }
    }

    private int invoke(List<String> command, PhaseGate gate) throws Exception {
        if (closed.get() || gate.cause() != null) throw new IOException("Phase launch fenced");
        Process process = new ProcessBuilder(command).start();
        active.set(process);
        try {
            if (closed.get() || gate.cause() != null) throw new IOException("Late phase launch fenced");
            process.getOutputStream().close();
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
