package com.codevisualization.backend.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Trusted fixture coordinator only; no API, source admission, trace or slot management. */
public final class FixedFixtureLifecycle {
    public enum Outcome { COMPLETED, COMPILE_NONZERO, EXECUTION_NONZERO, COMPILATION_TIMEOUT,
        EXECUTION_TIMEOUT, OUTPUT_LIMIT, CANCELLED, INFRASTRUCTURE_FAILURE }
    public enum Failure { SETUP_OR_TRANSPORT_FAILED, TERMINATION_UNCONFIRMED, CLEANUP_UNCONFIRMED, RECOVERY_FAILED }
    public record Result(String name, String containerId, Outcome outcome, Integer phaseExitCode,
                         boolean runtimeRequested, boolean terminationConfirmed, boolean cleanupVerified,
                         byte[] stdout, byte[] stderr, List<Failure> infrastructureFailures) {
        public Result {
            stdout = stdout.clone(); stderr = stderr.clone();
            infrastructureFailures = List.copyOf(infrastructureFailures);
        }
        @Override public byte[] stdout() { return stdout.clone(); }
        @Override public byte[] stderr() { return stderr.clone(); }
    }
    @FunctionalInterface
    interface PhaseExecutor {
        PhaseProcess.Result execute(List<String> command, RunOutput output, Duration budget);
    }

    private final FixtureControl control;
    private final PhaseExecutor phases;
    private final Runnable closeControl;
    private final AtomicBoolean used = new AtomicBoolean();
    private final RunCancellation cancellation = new RunCancellation();
    private volatile Result latest;
    private volatile String phase = "not_started";
    private volatile boolean runtimePhase;
    private final RunOutput output = new RunOutput();
    // Written by the run thread, read by recover() on another thread; at most two entries.
    private final List<PhaseProcess> transports = new java.util.concurrent.CopyOnWriteArrayList<>();

    record Progress(String name, String containerId, String phase, int outputBytes) { }
    Progress progress() { return new Progress(control.identity().name(), control.containerId(), phase, output.size()); }

    public FixedFixtureLifecycle(Path dockerExecutable) {
        var runner = new BoundedCommandRunner();
        control = new DockerControlAdapter(dockerExecutable, runner);
        phases = (command, output, budget) -> {
            var transport = new PhaseProcess(); transports.add(transport);
            return transport.execute(command, output, budget, cancellation, runtimePhase);
        };
        closeControl = runner::close;
    }

    FixedFixtureLifecycle(FixtureControl control, PhaseExecutor phases) {
        this.control = control; this.phases = phases; closeControl = () -> { };
    }

    public Result run(FixedFixture fixture) {
        return runReserved(fixture, System.nanoTime() + Duration.ofSeconds(10).toNanos());
    }

    public boolean cancel() { return cancellation.request(); }

    boolean cancellationRequested() { return cancellation.requested(); }

    Result runReserved(FixedFixture fixture, long preparationDeadline) {
        java.util.Objects.requireNonNull(fixture);
        if (!used.compareAndSet(false, true)) throw new IllegalStateException("Lifecycle is one-shot");
        var failures = new ArrayList<Failure>();
        Outcome outcome = Outcome.INFRASTRUCTURE_FAILURE;
        Integer exitCode = null;
        boolean runtime = false, terminated = false, cleaned = false;
        boolean preparationStarted = false;
        try {
            if (cancellation.requested()) outcome = Outcome.CANCELLED;
            else {
                long remaining = preparationDeadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Preparation deadline expired before launch");
                preparationStarted = true;
                phase = "preparing";
                control.prepare(fixture, Duration.ofNanos(remaining));
                if (cancellation.requested()) outcome = Outcome.CANCELLED;
                else {
                    phase = "compiling";
                    var compile = phases.execute(control.phaseCommand(fixture, true), output, Duration.ofSeconds(10));
                    outcome = outcome(compile, true); exitCode = compile.exitCode();
                    if (compile.cause() == PhaseGate.Cause.EXIT && Integer.valueOf(0).equals(compile.exitCode())) {
                        if (cancellation.requested()) outcome = Outcome.CANCELLED;
                        else {
                            List<String> command = control.phaseCommand(fixture, false);
                            runtime = true; runtimePhase = true;
                            phase = "executing";
                            var execution = phases.execute(command, output, Duration.ofSeconds(5));
                            outcome = outcome(execution, false); exitCode = execution.exitCode();
                        }
                    }
                }
            }
            if (outcome == Outcome.INFRASTRUCTURE_FAILURE) failures.add(Failure.SETUP_OR_TRANSPORT_FAILED);
        } catch (IOException | RuntimeException failure) {
            outcome = cancellation.requested() ? Outcome.CANCELLED : Outcome.INFRASTRUCTURE_FAILURE;
            failures.add(Failure.SETUP_OR_TRANSPORT_FAILED);
        } finally {
            cancellation.finish();
            // Preserve interruption, but allow bounded external cleanup to run.
            boolean interrupted = Thread.interrupted();
            try {
                phase = "stopping";
                try { if (preparationStarted) control.stop(); terminated = true; }
                catch (IOException | RuntimeException failure) { failures.add(Failure.TERMINATION_UNCONFIRMED); }
                if (terminated) {
                    phase = "cleaning";
                    try { if (preparationStarted) control.remove(); cleaned = true; }
                    catch (IOException | RuntimeException failure) { failures.add(Failure.CLEANUP_UNCONFIRMED); }
                }
                if (cleaned && !transports.stream().allMatch(PhaseProcess::settled)) {
                    cleaned = false; failures.add(Failure.CLEANUP_UNCONFIRMED);
                }
            } finally {
                closeControl.run();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        latest = new Result(control.identity().name(), control.containerId(), outcome, exitCode,
                runtime, terminated, cleaned, output.stdout(), output.stderr(), failures);
        if (terminated && cleaned) phase = "finished";
        return latest;
    }

    public synchronized Result recover() {
        Result previous = latest;
        if (previous == null) throw new IllegalStateException("Run has not settled");
        if (previous.terminationConfirmed() && previous.cleanupVerified()) return previous;
        var failures = new ArrayList<>(previous.infrastructureFailures());
        boolean recovered = false;
        try {
            if (!transports.stream().allMatch(PhaseProcess::settled)) throw new IOException("Phase worker unsettled");
            control.recover(); recovered = true;
        }
        catch (IOException | RuntimeException failure) {
            if (!failures.contains(Failure.RECOVERY_FAILED)) failures.add(Failure.RECOVERY_FAILED);
        }
        latest = new Result(previous.name(), previous.containerId(), previous.outcome(), previous.phaseExitCode(),
                previous.runtimeRequested(), recovered || previous.terminationConfirmed(), recovered,
                previous.stdout(), previous.stderr(), failures);
        if (recovered) phase = "finished";
        return latest;
    }

    private static Outcome outcome(PhaseProcess.Result result, boolean compile) {
        return switch (result.cause()) {
            case TIMEOUT -> compile ? Outcome.COMPILATION_TIMEOUT : Outcome.EXECUTION_TIMEOUT;
            case OUTPUT_LIMIT -> Outcome.OUTPUT_LIMIT;
            case CANCELLED -> Outcome.CANCELLED;
            case TRANSPORT_FAILURE -> Outcome.INFRASTRUCTURE_FAILURE;
            case EXIT -> Integer.valueOf(0).equals(result.exitCode()) ? Outcome.COMPLETED
                    : compile ? Outcome.COMPILE_NONZERO : Outcome.EXECUTION_NONZERO;
        };
    }
}
