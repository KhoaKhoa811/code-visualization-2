package com.codevisualization.backend.api;

import com.codevisualization.backend.runner.RunSupervisor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the application's single {@link RunSupervisor}. It opens on the first
 * submission, after the owner-label inventory proves no earlier runner ownership;
 * until then, or if Docker is missing, submissions get RUNNER_UNAVAILABLE and the
 * next submission tries again. Never creates a second supervisor once one is open.
 */
@Component
public class RunnerAccess implements AutoCloseable {
    @FunctionalInterface
    public interface Opener { RunSupervisor open() throws IOException; }

    private final Opener opener;
    private RunSupervisor supervisor;

    @Autowired
    public RunnerAccess(@Value("${codeviz.docker.executable:}") String dockerExecutable) {
        this(() -> RunSupervisor.open(docker(dockerExecutable)));
    }

    public RunnerAccess(Opener opener) { this.opener = opener; }

    /** The open supervisor, or empty when none has been opened yet. */
    synchronized Optional<RunSupervisor> current() { return Optional.ofNullable(supervisor); }

    synchronized RunSupervisor openOrGet() throws IOException {
        if (supervisor == null) supervisor = opener.open();
        return supervisor;
    }

    @Override public synchronized void close() { if (supervisor != null) supervisor.close(); }

    static Path docker(String configured) throws IOException {
        if (!configured.isBlank()) return Path.of(configured).toAbsolutePath();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                for (String name : new String[]{"docker.exe", "docker"}) {
                    if (dir.isBlank()) continue;
                    try {
                        Path candidate = Path.of(dir, name);
                        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath();
                    } catch (InvalidPathException ignored) {
                        // Skip malformed PATH entries.
                    }
                }
            }
        }
        throw new IOException("Docker executable not found; set codeviz.docker.executable");
    }
}
