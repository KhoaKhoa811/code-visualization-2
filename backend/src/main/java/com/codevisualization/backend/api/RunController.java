package com.codevisualization.backend.api;

import com.codevisualization.backend.runner.RunStdin;
import com.codevisualization.backend.runner.RunSupervisor;
import com.codevisualization.backend.runner.TracedRunLifecycle;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal noninteractive run API (Part 7d): submit, poll, cancel, recover and
 * read output. Snapshots follow contracts/run/v1/result.schema.json; errors are
 * fixed JSON codes without request echo. Submitted Java never runs in this JVM.
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {
    static final int BODY_LIMIT = 2 * 1024 * 1024;
    private static final String RUN_ID = "run-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RunnerAccess runner;

    public RunController(RunnerAccess runner) { this.runner = runner; }

    @PostMapping
    public ResponseEntity<byte[]> submit(HttpServletRequest request) {
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_ENCODING");
        if (!isJson(request.getContentType())) return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(BODY_LIMIT + 1);
        } catch (IOException unreadable) {
            return error(HttpStatus.BAD_REQUEST, "REQUEST_INVALID");
        }
        if (body.length > BODY_LIMIT) return error(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE");
        RunRequest parsed;
        try { parsed = RunRequest.parse(body); }
        catch (RunRequest.Invalid invalid) { return error(HttpStatus.BAD_REQUEST, "REQUEST_INVALID"); }
        RunSupervisor supervisor;
        try { supervisor = runner.openOrGet(); }
        catch (IOException | RuntimeException unavailable) { return error(HttpStatus.SERVICE_UNAVAILABLE, "RUNNER_UNAVAILABLE"); }
        try {
            RunStdin stdin = switch (parsed.inputMode()) {
                case "live" -> RunStdin.LIVE;
                case "provided" -> {
                    RunStdin given = RunStdin.provided(parsed.inputText());
                    if (!RunSupervisor.sha256(given.provided()).equals(parsed.inputSha256()))
                        throw new TracedRunLifecycle.AdmissionException("INPUT_HASH_MISMATCH", "Input hash mismatch");
                    yield given;
                }
                default -> RunStdin.NONE;
            };
            var run = supervisor.submit(parsed.text(), parsed.sha256(), stdin);
            return ResponseEntity.accepted().location(URI.create("/api/runs/" + run.id()))
                    .contentType(MediaType.APPLICATION_JSON).body(RunJson.snapshot(supervisor.snapshot(run)));
        } catch (TracedRunLifecycle.AdmissionException rejected) {
            boolean tooLarge = rejected.code().equals("SOURCE_LIMIT") || rejected.code().equals("INPUT_LIMIT");
            return error(tooLarge ? HttpStatus.CONTENT_TOO_LARGE : HttpStatus.BAD_REQUEST, rejected.code());
        } catch (RunSupervisor.BusyException busy) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "RUNNER_BUSY");
        } catch (IllegalStateException closed) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "RUNNER_UNAVAILABLE");
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> poll(@PathVariable String id) {
        return withRun(id, (supervisor, run) -> ok(HttpStatus.OK, RunJson.snapshot(supervisor.snapshot(run))));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<byte[]> cancel(@PathVariable String id) {
        return withRun(id, (supervisor, run) -> supervisor.cancel(run)
                ? ok(HttpStatus.ACCEPTED, RunJson.snapshot(supervisor.snapshot(run)))
                : error(HttpStatus.CONFLICT, "CANCEL_NOT_ACCEPTED"));
    }

    /** Explicit recovery of a run holding the slot after unconfirmed stop or removal. */
    @PostMapping("/{id}/recover")
    public ResponseEntity<byte[]> recover(@PathVariable String id) {
        return withRun(id, (supervisor, run) -> {
            var before = supervisor.snapshot(run);
            if (!before.slotHeld() || before.phase() != RunSupervisor.Phase.STOPPING && before.phase() != RunSupervisor.Phase.CLEANING
                    || before.infrastructureFailures().isEmpty()) return error(HttpStatus.CONFLICT, "RECOVERY_NOT_APPLICABLE");
            supervisor.recover(run);
            return ok(HttpStatus.OK, RunJson.snapshot(supervisor.snapshot(run)));
        });
    }

    /** Program output, available once whole-container termination is confirmed. */
    @GetMapping("/{id}/output")
    public ResponseEntity<byte[]> output(@PathVariable String id) {
        return withRun(id, (supervisor, run) -> supervisor.output(run)
                .map(result -> ok(HttpStatus.OK, RunJson.output(run.id(), result)))
                .orElseGet(() -> error(HttpStatus.CONFLICT, "OUTPUT_NOT_READY")));
    }

    private interface RunAction {
        ResponseEntity<byte[]> apply(RunSupervisor supervisor, RunSupervisor.Run run);
    }

    private ResponseEntity<byte[]> withRun(String id, RunAction action) {
        Optional<RunSupervisor> supervisor = runner.current();
        if (!id.matches(RUN_ID) || supervisor.isEmpty()) return error(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND");
        var run = supervisor.get().find(id);
        if (run.isEmpty()) return error(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND");
        try {
            if (supervisor.get().expired(run.get())) return error(HttpStatus.GONE, "QUEUE_EXPIRED");
            return action.apply(supervisor.get(), run.get());
        } catch (IllegalStateException expiredMeanwhile) {
            return error(HttpStatus.GONE, "QUEUE_EXPIRED");
        }
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(type)
                    && (type.getCharset() == null || type.getCharset().equals(java.nio.charset.StandardCharsets.UTF_8));
        }
        catch (RuntimeException invalid) { return false; }
    }

    private static ResponseEntity<byte[]> ok(HttpStatus status, byte[] json) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json);
    }

    private static ResponseEntity<byte[]> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(RunJson.error(code));
    }
}
