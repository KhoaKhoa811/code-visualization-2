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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One traced run of a Java source inside the owned restricted container (Part 7c).
 * Preparation stages the tracer runtime and the exact source through stdin; the
 * compile phase runs {@code codeviz.tracer.Prepare} (javac + instrumentation, no
 * user code); the backend reads plan.json before the runtime phase; after the
 * runtime phase, for any cause, the run user's processes are killed and the raw
 * records read while the container still exists; then the whole container is
 * stopped and removed. Trace-v1 is built here, outside the container, and only
 * once termination is confirmed. {@link RunSupervisor} owns the slot (Part 7d).
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

    /** Supervisor infrastructure codes of the run-v1 contract; never Java execution statuses. */
    public enum Failure { SETUP_FAILED, WORKER_FAILED, TRANSPORT_FAILED, TERMINATION_UNCONFIRMED, CLEANUP_UNCONFIRMED }

    /** Rejected before any container work (execution-isolation Part 4h). */
    public static final class AdmissionException extends Exception {
        private final String code;
        public AdmissionException(String code, String message) { super(message); this.code = code; }
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
    // Part 8g: live standard input of an interactive run, and whether the controller disconnected.
    private volatile RunInput input;
    private volatile boolean interactive, disconnected;
    // Part 6g: verified waiting for input (null: not known), and whether the input-wait limit stopped the run.
    private volatile Boolean waiting;
    private volatile boolean inputWaitExpired;
    private Duration inputWait = INPUT_WAIT;
    static final Duration INPUT_WAIT = Duration.ofSeconds(30), WAIT_POLL = Duration.ofSeconds(1);
    static final Duration RUNTIME = Duration.ofSeconds(5), INTERACTIVE_RUNTIME = Duration.ofSeconds(120);
    private volatile String phase = "not_started";
    private volatile Result latest;
    // Built once termination is confirmed, possibly later by explicit recovery.
    private Supplier<String> pendingTrace;

    record Progress(String containerId, String phase, int outputBytes) { }
    Progress progress() { return new Progress(control.containerId(), phase, output.size()); }

    public TracedRunLifecycle(Path dockerExecutable) {
        this(dockerExecutable, bundledRuntime());
    }

    /** Shares one loaded runtime jar across runs. */
    TracedRunLifecycle(Path dockerExecutable, byte[] runtimeJar) {
        var runner = new BoundedCommandRunner();
        control = new DockerControlAdapter(dockerExecutable, runner);
        phases = (command, out, budget) -> {
            var transport = new PhaseProcess();
            transports.add(transport);
            return transport.execute(command, out, budget, cancellation, runtimePhase, runtimePhase ? input : null);
        };
        closeControl = runner::close;
        this.runtimeJar = runtimeJar;
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

    /** Part 8g: the controlling console connection was lost; the run ends cancelled with INPUT_DISCONNECTED. */
    public boolean cancelDisconnected() {
        if (!cancellation.request()) return false;
        disconnected = true;
        return true;
    }

    public boolean interactive() { return interactive; }

    /** Part 6g: whether the executing interactive program is blocked reading standard input; null when not known. */
    public Boolean waiting() { return waiting; }

    /** Tests only: a shorter input-wait limit. */
    void inputWait(Duration limit) { inputWait = limit; }

    /** Standard input of the executing run: live (interactive), provided (Part 10a), or null when closed. */
    public RunInput input() { return input; }

    /** Console bytes accepted so far, in arrival order, from chunk {@code index} on. */
    public List<RunOutput.Chunk> outputSince(int index) { return output.since(index); }

    boolean cancellationRequested() { return cancellation.requested(); }

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

    public Result run(String source) throws AdmissionException { return run(source, RunStdin.NONE); }

    /** {@code live}: input may arrive through {@link #input()} while an interactive program executes (Part 8g). */
    public Result run(String source, boolean live) throws AdmissionException { return run(source, live ? RunStdin.LIVE : RunStdin.NONE); }

    /** Part 10a: standard input closed, live, or given with the request. */
    public Result run(String source, RunStdin stdin) throws AdmissionException {
        return runReserved(source, "run-" + control.identity().owner(), System.nanoTime() + Duration.ofSeconds(10).toNanos(), stdin);
    }

    /** Runs with a run ID and a preparation deadline that started when the slot was reserved. */
    Result runReserved(String source, String runId, long preparationDeadline) throws AdmissionException {
        return runReserved(source, runId, preparationDeadline, RunStdin.NONE);
    }

    Result runReserved(String source, String runId, long preparationDeadline, RunStdin stdin) throws AdmissionException {
        byte[] sourceBytes = admit(source);
        if (!used.compareAndSet(false, true)) throw new IllegalStateException("Lifecycle is one-shot");
        List<Failure> failures = new ArrayList<>();
        Supplier<String> trace = null; // built only after termination is confirmed
        boolean prepared = false, terminated = false, cleaned = false;
        try {
            if (cancellation.requested()) {
                trace = stopped(source, runId, Tracer.Cause.CANCELLED);
            } else {
                long remaining = preparationDeadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Preparation deadline expired before launch");
                phase = "preparing";
                prepared = true;
                var files = new LinkedHashMap<String, byte[]>();
                files.put(RUNTIME_JAR, runtimeJar);
                files.put("Main.java", sourceBytes);
                control.prepareWorkspace(files, Duration.ofNanos(remaining));
                if (cancellation.requested()) {
                    trace = stopped(source, runId, Tracer.Cause.CANCELLED);
                } else {
                    phase = "compiling";
                    var compile = phases.execute(control.workspaceCommand(PREPARE), output, Duration.ofSeconds(10));
                    if (compile.cause() != PhaseGate.Cause.EXIT) {
                        Tracer.Cause cause = compileStop(compile.cause());
                        if (cause == null) failures.add(Failure.TRANSPORT_FAILED);
                        else trace = stopped(source, runId, cause);
                    } else if (!Integer.valueOf(0).equals(compile.exitCode())) {
                        failures.add(Failure.WORKER_FAILED); // the trusted tool itself failed
                    } else {
                        Tracer.Prepared plan = readPlan(source, failures);
                        if (plan == null) {
                            // Failure recorded; no submitted code runs without a trusted plan.
                        } else if (!plan.compiled()) {
                            trace = () -> Tracer.trace(plan, runId, "", Tracer.Outcome.exited(1));
                        } else if (cancellation.requested()) {
                            trace = stopped(source, runId, Tracer.Cause.CANCELLED);
                        } else {
                            // Interactive: a live console may send input (120 s deadline). Provided input (Part 10a) is
                            // written at once and then closed; nobody waits for it, so the normal deadline applies.
                            interactive = stdin.mode() == RunStdin.Mode.LIVE && plan.interactive();
                            boolean attached = plan.interactive() && stdin.mode() != RunStdin.Mode.NONE;
                            if (interactive) input = new RunInput();
                            else if (attached) input = RunInput.provided(stdin.provided());
                            List<String> command = control.workspaceCommand(plan.traced() ? RUN_TRACED : RUN_PLAIN, attached);
                            phase = "executing";
                            runtimePhase = true;
                            if (input != null) input.open();
                            Thread watch = interactive ? Thread.ofPlatform().daemon().name("input-wait").start(this::watchInput) : null;
                            PhaseProcess.Result runtime;
                            try {
                                runtime = phases.execute(command, output, interactive ? INTERACTIVE_RUNTIME : RUNTIME);
                            } finally {
                                if (watch != null) {
                                    watch.interrupt();
                                    try { watch.join(Duration.ofSeconds(3)); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                                }
                                waiting = null;
                            }
                            Tracer.Outcome outcome = runtimeOutcome(runtime);
                            if (outcome == null) {
                                failures.add(Failure.TRANSPORT_FAILED);
                            } else {
                                String raw = "";
                                if (plan.traced()) {
                                    try {
                                        raw = control.readFile("/work/raw.trace", RAW_LIMIT, Duration.ofSeconds(5), true);
                                    } catch (IOException | RuntimeException unreadable) {
                                        failures.add(Failure.TRANSPORT_FAILED); // capture becomes unavailable
                                    }
                                }
                                String records = raw;
                                byte[] delivered = input != null ? input.transcript() : new byte[0];
                                trace = () -> Tracer.trace(plan, runId, records, outcome, delivered);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            failures.add("preparing".equals(phase) || "not_started".equals(phase) ? Failure.SETUP_FAILED : Failure.TRANSPORT_FAILED);
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
        synchronized (this) {
            pendingTrace = trace;
            String json = terminated && trace != null ? trace.get() : null;
            latest = new Result(runId, json, output.stdout(), output.stderr(), terminated, cleaned, failures);
            if (terminated && cleaned) phase = "finished";
            return latest;
        }
    }

    /**
     * Explicit recovery of an unconfirmed stop or removal: force-removes the exact
     * container ID and verifies absence. Keeps the original stop cause, output and
     * failures; builds the withheld trace once termination is proven.
     */
    public synchronized Result recover() {
        Result previous = latest;
        if (previous == null) throw new IllegalStateException("Run has not settled");
        if (previous.terminationConfirmed() && previous.cleanupVerified()) return previous;
        try {
            if (!transports.stream().allMatch(PhaseProcess::settled)) throw new IOException("Phase worker unsettled");
            control.recover();
        } catch (IOException | RuntimeException failure) {
            return previous; // still held; failures already record why
        }
        String json = previous.trace() != null ? previous.trace() : pendingTrace != null ? pendingTrace.get() : null;
        latest = new Result(previous.runId(), json, previous.stdout(), previous.stderr(), true, true,
                previous.infrastructureFailures());
        phase = "finished";
        return latest;
    }

    /**
     * Part 6g (execution-isolation Part 4r): while an interactive program executes, the kernel is asked
     * once a second whether it is blocked reading its standard input. Only an unbroken run of such
     * observations counts; a running or unknown answer starts over. After the input-wait limit of
     * continuous waiting the run stops (a cancellation whose cause is INPUT_WAIT_TIMEOUT), unless
     * another stop cause came first. Without evidence only the overall 120 s deadline applies.
     */
    private void watchInput() {
        long since = -1;
        while (!Thread.currentThread().isInterrupted() && !cancellation.requested()) {
            Boolean blocked;
            try { blocked = control.stdinWaiting(Duration.ofSeconds(2)); }
            catch (IOException | RuntimeException unknown) { blocked = null; }
            waiting = blocked;
            long now = System.nanoTime();
            if (Boolean.TRUE.equals(blocked)) {
                if (since < 0) since = now;
                if (now - since >= inputWait.toNanos()) {
                    if (cancellation.request()) inputWaitExpired = true;
                    return;
                }
            } else {
                since = -1;
            }
            try { Thread.sleep(WAIT_POLL); } catch (InterruptedException stop) { return; }
        }
    }

    private Tracer.Prepared readPlan(String source, List<Failure> failures) {
        String json;
        try {
            json = control.readFile("/work/out/plan.json", PLAN_LIMIT, Duration.ofSeconds(5), false);
        } catch (IOException | RuntimeException unreadable) {
            failures.add(Failure.TRANSPORT_FAILED);
            return null;
        }
        try {
            return Tracer.fromPlan(source, json);
        } catch (RuntimeException invalid) {
            failures.add(Failure.WORKER_FAILED); // malformed plan or a plan for other source
            return null;
        }
    }

    private static Supplier<String> stopped(String source, String runId, Tracer.Cause cause) {
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

    private Tracer.Outcome runtimeOutcome(PhaseProcess.Result result) {
        return switch (result.cause()) {
            case EXIT -> Tracer.Outcome.exited(result.exitCode());
            case TIMEOUT -> new Tracer.Outcome(interactive ? Tracer.Cause.INTERACTIVE_TIMEOUT : Tracer.Cause.EXECUTION_TIMEOUT, 0);
            case OUTPUT_LIMIT -> new Tracer.Outcome(Tracer.Cause.OUTPUT_LIMIT, 0);
            case CANCELLED -> new Tracer.Outcome(inputWaitExpired ? Tracer.Cause.INPUT_WAIT_TIMEOUT
                    : disconnected ? Tracer.Cause.INPUT_DISCONNECTED : Tracer.Cause.CANCELLED, 0);
            case TRANSPORT_FAILURE -> null;
        };
    }
}
