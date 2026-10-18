package com.codevisualization.backend.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** One owned fixed-idle environment; failures retain identity for later reconciliation. */
public final class DockerControlAdapter implements FixtureControl, WorkspaceControl {
    private final Path executable;
    private final CommandRunner runner;
    private final ContainerProfile.Identity identity = ContainerProfile.Identity.fresh();
    private final ReentrantLock operation = new ReentrantLock();
    private volatile String containerId;
    private volatile boolean quarantined;
    private boolean created, started, stopped, removed;
    private final java.util.function.Supplier<CommandRunner> recoveryRunners;
    private CommandRunner recoveryRunner;

    public DockerControlAdapter(Path executable, BoundedCommandRunner runner) {
        this(executable, (CommandRunner) runner);
    }

    DockerControlAdapter(Path executable, CommandRunner runner) {
        this(executable, runner, BoundedCommandRunner::new);
    }

    DockerControlAdapter(Path executable, CommandRunner runner, java.util.function.Supplier<CommandRunner> recoveryRunners) {
        if (!executable.isAbsolute()) throw new IllegalArgumentException("Absolute Docker executable required");
        this.executable = executable;
        this.runner = java.util.Objects.requireNonNull(runner);
        this.recoveryRunners = java.util.Objects.requireNonNull(recoveryRunners);
    }

    public ContainerProfile.Identity identity() { return identity; }
    public String containerId() { return containerId; }
    public boolean quarantined() { return quarantined; }

    public void create() throws IOException {
        create(Duration.ofSeconds(10));
    }

    void create(Duration remaining) throws IOException {
        perform(remaining, deadline -> {
            if (created || containerId != null) throw new IOException("Container already created");
            String id = call(ContainerProfile.createArguments(identity), deadline).strip();
            if (!id.matches("[0-9a-f]{64}")) throw new IOException("Create did not return a full container ID");
            containerId = id;
            inspect(deadline).verifyProfile();
            created = true;
        });
    }

    public void startIdle() throws IOException {
        startIdle(Duration.ofSeconds(5));
    }

    void startIdle(Duration remaining) throws IOException {
        perform(remaining, deadline -> {
            requireCreated();
            if (started || stopped) throw new IOException("Idle container cannot restart after start/stop");
            ContainerInspection before = inspect(deadline);
            before.verifyProfile();
            if (before.running()) throw new IOException("Unexpected running container");
            call(List.of("start", containerId), deadline);
            if (!inspect(deadline).running()) throw new IOException("Start not confirmed");
            started = true;
        });
    }

    public void stop() throws IOException {
        perform(Duration.ofSeconds(5), deadline -> {
            requireCreated();
            stopped = true;
            if (inspect(deadline).running()) call(List.of("kill", containerId), deadline);
            if (inspect(deadline).running()) throw new IOException("Termination not confirmed");
        });
    }

    public void remove() throws IOException {
        perform(Duration.ofSeconds(5), deadline -> {
            requireCreated();
            if (inspect(deadline).running()) throw new IOException("Refusing removal of running container");
            call(List.of("rm", containerId), deadline);
            String remaining = call(List.of("ps", "--all", "--no-trunc", "--filter", "id=" + containerId,
                    "--format", "{{.ID}}"), deadline);
            if (!remaining.isBlank()) throw new IOException("Removal not confirmed");
            removed = true;
        });
    }

    private void requireCreated() throws IOException {
        if (!created || removed) throw new IOException("No active owned container");
    }

    void stage(FixedFixture fixture, Duration remaining) throws IOException {
        perform(remaining, deadline -> {
            requireRunning(deadline);
            call(execArguments(fixture.stagingCommand()), deadline);
        });
    }

    public void prepare(FixedFixture fixture) throws IOException {
        prepare(fixture, Duration.ofSeconds(10));
    }

    public void prepare(FixedFixture fixture, Duration remaining) throws IOException {
        long deadline = System.nanoTime() + remaining.toNanos();
        create(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        startIdle(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        stage(fixture, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
    }

    @Override
    public void prepareWorkspace(java.util.Map<String, byte[]> files, Duration remaining) throws IOException {
        long deadline = System.nanoTime() + remaining.toNanos();
        create(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        startIdle(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        for (var file : files.entrySet()) stageFile(file.getKey(), file.getValue(), Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
    }

    void stageFile(String name, byte[] content, Duration remaining) throws IOException {
        if (!name.matches("[A-Za-z][A-Za-z0-9._-]{0,63}")) throw new IOException("Staged file name outside the fixed vocabulary");
        perform(remaining, deadline -> {
            requireRunning(deadline);
            var command = new ArrayList<>(List.of(executable.toString(), "--context", "desktop-linux", "exec", "-i", containerId,
                    "/usr/bin/env", "-i", "PATH=/opt/java/openjdk/bin:/usr/bin:/bin", "HOME=/tmp",
                    "/bin/sh", "-ec", "cat > /work/" + name));
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) throw new IOException("Control operation deadline exceeded");
            runner.execute(command, Duration.ofNanos(nanos), content);
        });
    }

    @Override
    public List<String> workspaceCommand(List<String> fixedCommand) throws IOException {
        if (!operation.tryLock()) throw new IOException("Container control busy");
        try {
            if (quarantined || !started || stopped || removed) throw new IOException("Phase launch fenced");
            var command = new ArrayList<>(List.of(executable.toString(), "--context", "desktop-linux"));
            command.addAll(execArguments(fixedCommand));
            return List.copyOf(command);
        } finally {
            operation.unlock();
        }
    }

    @Override
    public String readFile(String path, int limit, Duration remaining, boolean killFirst) throws IOException {
        if (!path.matches("/work/[A-Za-z0-9._/-]{1,64}") || path.contains("..")) throw new IOException("Read path outside the fixed vocabulary");
        if (!operation.tryLock()) throw new IOException("Container control busy");
        try {
            if (quarantined || !started || stopped || removed) throw new IOException("Read fenced");
            // A read-only exec: failure is reported but never quarantines ownership state.
            var command = new ArrayList<>(List.of(executable.toString(), "--context", "desktop-linux"));
            command.addAll(execArguments(List.of("/bin/sh", "-ec", (killFirst ? "kill -9 -1 2>/dev/null || true; " : "") + "cat " + path)));
            try (var transfer = new BoundedCommandRunner(limit)) {
                return transfer.execute(command, remaining);
            }
        } finally {
            operation.unlock();
        }
    }

    public void recover() throws IOException {
        if (!operation.tryLock()) throw new IOException("Container control busy");
        try {
            if (containerId == null) throw new IOException("Unknown create outcome; cannot prove absence");
            if (!runner.settled() || (recoveryRunner != null && !recoveryRunner.settled()))
                throw new IOException("Prior control worker unsettled");
            stopped = true; // Recovery never permits another launch.
            recoveryRunner = recoveryRunners.get();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            try {
                String ids = recoveryCall(List.of("ps", "--all", "--no-trunc", "--filter", "id=" + containerId,
                        "--format", "{{.ID}}"), deadline).strip();
                if (!ids.isEmpty()) {
                    if (!ids.equals(containerId)) throw new IOException("Unexpected recovery inventory");
                    new ContainerInspection(recoveryCall(List.of("inspect", "--type=container", containerId), deadline),
                            containerId, identity);
                    recoveryCall(List.of("rm", "--force", containerId), deadline);
                    if (!recoveryCall(List.of("ps", "--all", "--no-trunc", "--filter", "id=" + containerId,
                            "--format", "{{.ID}}"), deadline).isBlank()) throw new IOException("Recovery absence unconfirmed");
                }
                removed = true;
            } finally { if (recoveryRunner instanceof BoundedCommandRunner bounded) bounded.close(); }
        } finally { operation.unlock(); }
    }

    private String recoveryCall(List<String> arguments, long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Recovery deadline exceeded");
        var command = new ArrayList<>(List.of(executable.toString(), "--context", "desktop-linux"));
        command.addAll(arguments);
        return recoveryRunner.execute(command, Duration.ofNanos(remaining));
    }

    public List<String> phaseCommand(FixedFixture fixture, boolean compilation) throws IOException {
        if (!operation.tryLock()) throw new IOException("Container control busy");
        try {
            if (quarantined || !started || stopped || removed) throw new IOException("Phase launch fenced");
            var command = new ArrayList<>(List.of(executable.toString(), "--context", "desktop-linux"));
            command.addAll(execArguments(compilation ? fixture.compileCommand() : fixture.runtimeCommand()));
            return List.copyOf(command);
        } finally {
            operation.unlock();
        }
    }

    private void requireRunning(long deadline) throws IOException {
        requireCreated();
        if (!started || stopped || !inspect(deadline).running()) throw new IOException("Container not running");
    }

    private List<String> execArguments(List<String> fixedCommand) {
        var args = new ArrayList<>(List.of("exec", containerId, "/usr/bin/env", "-i",
                "PATH=/opt/java/openjdk/bin:/usr/bin:/bin", "HOME=/tmp"));
        args.addAll(fixedCommand);
        return args;
    }

    private ContainerInspection inspect(long deadline) throws IOException {
        return new ContainerInspection(call(List.of("inspect", "--type=container", containerId), deadline), containerId, identity);
    }

    private String call(List<String> arguments, long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("Control operation deadline exceeded");
        var command = new ArrayList<>(List.of(executable.toString(), "--context", "desktop-linux"));
        command.addAll(arguments);
        return runner.execute(command, Duration.ofNanos(nanos));
    }

    private void perform(Duration budget, Action action) throws IOException {
        if (!operation.tryLock()) throw new IOException("Container control busy");
        try {
            if (quarantined) throw new IOException("Container quarantined; explicit reconciliation required");
            if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(10)) > 0)
                throw new IOException("Invalid operation budget");
            action.run(System.nanoTime() + budget.toNanos());
        } catch (IOException | RuntimeException failure) {
            quarantined = true;
            throw failure;
        } finally {
            operation.unlock();
        }
    }

    @FunctionalInterface
    private interface Action { void run(long deadline) throws IOException; }
}
