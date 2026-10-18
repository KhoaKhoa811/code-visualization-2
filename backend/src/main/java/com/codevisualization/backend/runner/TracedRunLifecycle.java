package com.codevisualization.backend.runner;

import codeviz.tracer.Tracer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One traced run of a Java source inside the owned restricted container (Part 7c).
 * Preparation stages the tracer runtime and the exact source through stdin; the
 * compile phase runs {@code codeviz.tracer.Prepare} (javac + instrumentation, no
 * user code); the backend reads plan.json before the runtime phase; after the
 * runtime phase, for any cause, the run user's processes are killed and the raw
 * records read while the container still exists; then the whole container is
 * stopped and removed. Trace-v1 is built here, outside the container, and only
 * once termination is confirmed. No HTTP route or supervisor wiring yet (Part 7d).
 */
public final class TracedRunLifecycle {
    public static final int SOURCE_LIMIT = 262_144;
    static final int PLAN_LIMIT = 4 * 1024 * 1024;
    static final int RAW_LIMIT = 16 * 1024 * 1024 + 64 * 1024;
    static final String RUNTIME_JAR = "tracer-runtime.jar";
    static final List<String> PREPARE = List.of("java", "-Xmx256m", "-Djava.io.tmpdir=/tmp",
            "-cp", "/work/" + RUNTIME_JAR, "codeviz.tracer.Prepare", "/work/Main.java", "/work/out");
    static final List<String> RUN_TRACED = List.of("java", "-Xmx128m", "-Djava.io.tmpdir=/tmp",
            "-Dcodeviz.trace=/work/raw.trace", "-cp", "/work/out:/work/" + RUNTIME_JAR, "Main");
    static final List<String> RUN_PLAIN = List.of("java", "-Xmx128m", "-Djava.io.tmpdir=/tmp", "-cp", "/work/out", "Main");

    public enum Failure { SETUP_OR_TRANSPORT_FAILED, TRACE_TRANSFER_FAILED, TERMINATION_UNCONFIRMED, CLEANUP_UNCONFIRMED }

    /** Rejected before any container work (execution-isolation Part 4h). */
    public static final class AdmissionException extends Exception {
        private final String code;
        AdmissionException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    /** {@code trace} is null unless whole-container termination was confirmed and a trace could be built. */
    public record Result(String runId, String trace, byte[] stdout, byte[] stderr, boolean terminationConfirmed,
                         boolean cleanupVerified, List<Failure> infrastructureFailures) {
        public Result {
            stdout = stdout.clone(); stderr = stderr.clone();
            infrastructureFailures = List.copyOf(infrastructureFailures);
        }
        @Override public byte[] stdout() { return stdout.clone(); }
        @Override public byte[] stderr() { return stderr.clone(); }
    }

    private final WorkspaceControl control;
    private final FixedFixtureLifecycle.PhaseExecutor phases;
    private final Runnable closeControl;
    private final byte[] runtimeJar;
    private final AtomicBoolean used = new AtomicBoolean();
    private final RunCancellation cancellation = new RunCancellation();
    private final RunOutput output = new RunOutput();
    private final List<PhaseProcess> transports = new CopyOnWriteArrayList<>();
    private volatile boolean runtimePhase;
    private volatile String phase = "not_started";

    record Progress(String containerId, String phase, int outputBytes) { }
    Progress progress() { return new Progress(control.containerId(), phase, output.size()); }

    public TracedRunLifecycle(Path dockerExecutable) {
        var runner = new BoundedCommandRunner();
        control = new DockerControlAdapter(dockerExecutable, runner);
        phases = (command, out, budget) -> {
            var transport = new PhaseProcess();
            transports.add(transport);
            return transport.execute(command, out, budget, cancellation, runtimePhase);
        };
        closeControl = runner::close;
        runtimeJar = bundledRuntime();
    }

    TracedRunLifecycle(WorkspaceControl control, FixedFixtureLifecycle.PhaseExecutor phases, byte[] runtimeJar) {
        this.control = control; this.phases = phases; this.runtimeJar = runtimeJar.clone(); closeControl = () -> { };
    }

    static byte[] bundledRuntime() {
        try (InputStream in = TracedRunLifecycle.class.getResourceAsStream("/runner-tracer/" + RUNTIME_JAR)) {
            if (in == null) throw new IllegalStateException("Tracer runtime missing; build from the root pom (runner/tracer first)");
            return in.readNBytes(BoundedCommandRunner.INPUT_LIMIT);
        } catch (IOException e) {
            throw new IllegalStateException("Tracer runtime unreadable", e);
        }
    }

    public boolean cancel() { return cancellation.request(); }

    /** Exact-text admission: well-formed Unicode and at most 256 KiB of UTF-8. */
    public static byte[] admit(String source) throws AdmissionException {
        if (source == null) throw new AdmissionException("SOURCE_INVALID", "Source is required");
        if (source.length() > SOURCE_LIMIT) throw new AdmissionException("SOURCE_LIMIT", "Source exceeds 262144 bytes of UTF-8");
        try {
            var encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var buffer = encoder.encode(CharBuffer.wrap(source));
            if (buffer.remaining() > SOURCE_LIMIT) throw new AdmissionException("SOURCE_LIMIT", "Source exceeds 262144 bytes of UTF-8");
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException malformed) {
            throw new AdmissionException("SOURCE_INVALID", "Source is not well-formed Unicode");
        }
    }

    public Result run(String source) throws AdmissionException {
        byte[] sourceBytes = admit(source);
        if (!used.compareAndSet(false, true)) throw new IllegalStateException("Lifecycle is one-shot");
        String runId = "run-" + control.identity().owner();
        List<Failure> failures = new ArrayList<>();
        java.util.function.Supplier<String> trace = null; // built only after termination is confirmed
        boolean prepared = false, terminated = false, cleaned = false;
        try {
            if (cancellation.requested()) {
                trace = stopped(source, runId, Tracer.Cause.CANCELLED);
            } else {
                phase = "preparing";
                prepared = true;
                var files = new LinkedHashMap<String, byte[]>();
                files.put(RUNTIME_JAR, runtimeJar);
                files.put("Main.java", sourceBytes);
                control.prepareWorkspace(files, Duration.ofSeconds(10));
                if (cancellation.requested()) {
                    trace = stopped(source, runId, Tracer.Cause.CANCELLED);
                } else {
                    phase = "compiling";
                    var compile = phases.execute(control.workspaceCommand(PREPARE), output, Duration.ofSeconds(10));
                    if (compile.cause() != PhaseGate.Cause.EXIT) {
                        Tracer.Cause cause = compileStop(compile.cause());
                        if (cause == null) failures.add(Failure.SETUP_OR_TRANSPORT_FAILED);
                        else trace = stopped(source, runId, cause);
                    } else if (!Integer.valueOf(0).equals(compile.exitCode())) {
                        failures.add(Failure.SETUP_OR_TRANSPORT_FAILED); // the trusted tool itself failed
                    } else {
                        Tracer.Prepared plan = Tracer.fromPlan(source,
                                control.readFile("/work/out/plan.json", PLAN_LIMIT, Duration.ofSeconds(5), false));
                        if (!plan.compiled()) {
                            trace = () -> Tracer.trace(plan, runId, "", Tracer.Outcome.exited(1));
                        } else if (cancellation.requested()) {
                            trace = stopped(source, runId, Tracer.Cause.CANCELLED);
                        } else {
                            phase = "executing";
                            runtimePhase = true;
                            var runtime = phases.execute(control.workspaceCommand(plan.traced() ? RUN_TRACED : RUN_PLAIN),
                                    output, Duration.ofSeconds(5));
                            Tracer.Outcome outcome = runtimeOutcome(runtime);
                            if (outcome == null) {
                                failures.add(Failure.SETUP_OR_TRANSPORT_FAILED);
                            } else {
                                String raw = "";
                                if (plan.traced()) {
                                    try {
                                        raw = control.readFile("/work/raw.trace", RAW_LIMIT, Duration.ofSeconds(5), true);
                                    } catch (IOException | RuntimeException unreadable) {
                                        failures.add(Failure.TRACE_TRANSFER_FAILED); // capture becomes unavailable
                                    }
                                }
                                String records = raw;
                                trace = () -> Tracer.trace(plan, runId, records, outcome);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            failures.add(Failure.SETUP_OR_TRANSPORT_FAILED);
            trace = null;
        } finally {
            cancellation.finish();
            boolean interrupted = Thread.interrupted();
            try {
                phase = "stopping";
                try { if (prepared) control.stop(); terminated = true; }
                catch (IOException | RuntimeException failure) { failures.add(Failure.TERMINATION_UNCONFIRMED); }
                if (terminated) {
                    phase = "cleaning";
                    try { if (prepared) control.remove(); cleaned = true; }
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
        String json = terminated && trace != null ? trace.get() : null;
        if (terminated && cleaned) phase = "finished";
        return new Result(runId, json, output.stdout(), output.stderr(), terminated, cleaned, failures);
    }

    private static java.util.function.Supplier<String> stopped(String source, String runId, Tracer.Cause cause) {
        return () -> Tracer.notRun(source, runId, new Tracer.Outcome(cause, 0));
    }

    private static Tracer.Cause compileStop(PhaseGate.Cause cause) {
        return switch (cause) {
            case TIMEOUT -> Tracer.Cause.COMPILATION_TIMEOUT;
            case OUTPUT_LIMIT -> Tracer.Cause.OUTPUT_LIMIT;
            case CANCELLED -> Tracer.Cause.CANCELLED;
            default -> null;
        };
    }

    private static Tracer.Outcome runtimeOutcome(PhaseProcess.Result result) {
        return switch (result.cause()) {
            case EXIT -> Tracer.Outcome.exited(result.exitCode());
            case TIMEOUT -> new Tracer.Outcome(Tracer.Cause.EXECUTION_TIMEOUT, 0);
            case OUTPUT_LIMIT -> new Tracer.Outcome(Tracer.Cause.OUTPUT_LIMIT, 0);
            case CANCELLED -> new Tracer.Outcome(Tracer.Cause.CANCELLED, 0);
            case TRANSPORT_FAILURE -> null;
        };
    }
}
