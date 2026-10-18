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
        EXECUTION_TIMEOUT, OUTPUT_LIMIT, INFRASTRUCTURE_FAILURE }
    public enum Failure { SETUP_OR_TRANSPORT_FAILED, TERMINATION_UNCONFIRMED, CLEANUP_UNCONFIRMED }
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

    public FixedFixtureLifecycle(Path dockerExecutable) {
        var runner = new BoundedCommandRunner();
        control = new DockerControlAdapter(dockerExecutable, runner);
        phases = (command, output, budget) -> new PhaseProcess().execute(command, output, budget);
        closeControl = runner::close;
    }

    FixedFixtureLifecycle(FixtureControl control, PhaseExecutor phases) {
        this.control = control; this.phases = phases; closeControl = () -> { };
    }

    public Result run(FixedFixture fixture) {
        java.util.Objects.requireNonNull(fixture);
        if (!used.compareAndSet(false, true)) throw new IllegalStateException("Lifecycle is one-shot");
        var output = new RunOutput();
        var failures = new ArrayList<Failure>();
        Outcome outcome = Outcome.INFRASTRUCTURE_FAILURE;
        Integer exitCode = null;
        boolean runtime = false, terminated = false, cleaned = false;
        try {
            control.prepare(fixture);
            var compile = phases.execute(control.phaseCommand(fixture, true), output, Duration.ofSeconds(10));
            outcome = outcome(compile, true); exitCode = compile.exitCode();
            if (compile.cause() == PhaseGate.Cause.EXIT && Integer.valueOf(0).equals(compile.exitCode())) {
                List<String> command = control.phaseCommand(fixture, false);
                runtime = true;
                var execution = phases.execute(command, output, Duration.ofSeconds(5));
                outcome = outcome(execution, false); exitCode = execution.exitCode();
            }
            if (outcome == Outcome.INFRASTRUCTURE_FAILURE) failures.add(Failure.SETUP_OR_TRANSPORT_FAILED);
        } catch (IOException | RuntimeException failure) {
            outcome = Outcome.INFRASTRUCTURE_FAILURE;
            failures.add(Failure.SETUP_OR_TRANSPORT_FAILED);
        } finally {
            // Preserve interruption, but allow bounded external cleanup to run.
            boolean interrupted = Thread.interrupted();
            try {
                try { control.stop(); terminated = true; }
                catch (IOException | RuntimeException failure) { failures.add(Failure.TERMINATION_UNCONFIRMED); }
                if (terminated) {
                    try { control.remove(); cleaned = true; }
                    catch (IOException | RuntimeException failure) { failures.add(Failure.CLEANUP_UNCONFIRMED); }
                }
            } finally {
                closeControl.run();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        return new Result(control.identity().name(), control.containerId(), outcome, exitCode,
                runtime, terminated, cleaned, output.stdout(), output.stderr(), failures);
    }

    private static Outcome outcome(PhaseProcess.Result result, boolean compile) {
        return switch (result.cause()) {
            case TIMEOUT -> compile ? Outcome.COMPILATION_TIMEOUT : Outcome.EXECUTION_TIMEOUT;
            case OUTPUT_LIMIT -> Outcome.OUTPUT_LIMIT;
            case TRANSPORT_FAILURE -> Outcome.INFRASTRUCTURE_FAILURE;
            case EXIT -> Integer.valueOf(0).equals(result.exitCode()) ? Outcome.COMPLETED
                    : compile ? Outcome.COMPILE_NONZERO : Outcome.EXECUTION_NONZERO;
        };
    }
}
