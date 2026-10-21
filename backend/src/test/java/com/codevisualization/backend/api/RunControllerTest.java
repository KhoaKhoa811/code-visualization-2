package com.codevisualization.backend.api;

import static org.junit.jupiter.api.Assertions.*;

import com.codevisualization.backend.runner.TestRuns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class RunControllerTest {
    private final TestRuns runs = new TestRuns();
    private final RunnerAccess access = new RunnerAccess(runs::open);
    private final RunController controller = new RunController(access);

    @AfterEach void close() { runs.release.countDown(); access.close(); }

    static String request(String text, String sha) {
        return "{\"schemaVersion\":\"1.2.0\",\"source\":{\"file\":\"Main.java\",\"text\":"
                + quote(text) + ",\"sha256\":\"" + sha + "\"},\"inputMode\":\"none\"}";
    }

    /** Part 8g: a request whose console may send input. */
    static String liveRequest(String text, String sha) {
        return request(text, sha).replace("\"inputMode\":\"none\"", "\"inputMode\":\"live\"");
    }

    /** Part 10a: a request carrying test input and its SHA-256. */
    static String providedRequest(String text, String sha, String input, String inputSha) {
        return request(text, sha).replace("\"inputMode\":\"none\"", "\"inputMode\":\"provided\",\"input\":{\"text\":"
                + quote(input) + ",\"sha256\":\"" + inputSha + "\"}");
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private ResponseEntity<byte[]> post(String body, String contentType) {
        var request = new MockHttpServletRequest("POST", "/api/runs");
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return controller.submit(request);
    }

    private static String body(ResponseEntity<byte[]> response) { return new String(response.getBody(), StandardCharsets.UTF_8); }

    private static String id(ResponseEntity<byte[]> response) {
        return response.getHeaders().getLocation().getPath().substring("/api/runs/".length());
    }

    private ResponseEntity<byte[]> awaitPhase(String id, String phase) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var response = controller.poll(id);
        while (!body(response).contains("\"phase\":\"" + phase + "\"") && System.nanoTime() < deadline) {
            Thread.sleep(5);
            response = controller.poll(id);
        }
        return response;
    }

    @Test void submitPollOutputAndCancelFollowTheContract() throws Exception {
        var accepted = post(request(TestRuns.SOURCE, TestRuns.SHA), "application/json");
        assertEquals(202, accepted.getStatusCode().value());
        String id = id(accepted);
        assertTrue(body(accepted).startsWith("{\"schemaVersion\":\"1.1.0\",\"runId\":\"" + id + "\""), body(accepted));
        assertEquals(409, controller.output(id).getStatusCode().value());
        assertEquals("{\"error\":\"RECOVERY_NOT_APPLICABLE\"}", body(controller.recover(id)));
        awaitPhase(id, "executing");
        var cancelled = controller.cancel(id);
        assertEquals(202, cancelled.getStatusCode().value());
        var finished = awaitPhase(id, "finished");
        assertTrue(body(finished).contains("\"slotHeld\":false") && body(finished).contains("RUN_CANCELLED"), body(finished));
        assertTrue(body(finished).endsWith(",\"infrastructureFailures\":[]}"));
        assertEquals(409, controller.cancel(id).getStatusCode().value());
        var output = controller.output(id);
        assertEquals(200, output.getStatusCode().value());
        assertEquals("{\"runId\":\"" + id + "\",\"stdout\":\"\",\"stderr\":\"\"}", body(output));
    }

    /** Part 10a: test input is admitted with its own bound, Unicode and hash, and its hash is in the snapshot. */
    @Test void providedInputIsAdmittedAndAssociatedWithTheRun() {
        String input = "3\n1 2 3\n";
        String inputSha = com.codevisualization.backend.runner.RunSupervisor.sha256(input.getBytes(StandardCharsets.UTF_8));
        var accepted = post(providedRequest(TestRuns.SOURCE, TestRuns.SHA, input, inputSha), "application/json");
        assertEquals(202, accepted.getStatusCode().value(), body(accepted));
        assertTrue(body(accepted).contains(",\"input\":{\"mode\":\"provided\",\"sha256\":\"" + inputSha + "\"},"), body(accepted));
        assertFalse(body(accepted).contains("1 2 3"), "input text is never echoed");
        assertEquals("{\"error\":\"INPUT_HASH_MISMATCH\"}",
                body(post(providedRequest(TestRuns.SOURCE, TestRuns.SHA, input, "0".repeat(64)), "application/json")));
        var tooLarge = post(providedRequest(TestRuns.SOURCE, TestRuns.SHA, "x".repeat(65_537), "0".repeat(64)), "application/json");
        assertEquals(413, tooLarge.getStatusCode().value());
        assertEquals("{\"error\":\"INPUT_LIMIT\"}", body(tooLarge));
        String lone = providedRequest(TestRuns.SOURCE, TestRuns.SHA, "x", "0".repeat(64)).replace("\"text\":\"x\"", "\"text\":\"\\ud800\"");
        assertEquals("{\"error\":\"INPUT_INVALID\"}", body(post(lone, "application/json")));
        var none = post(request(TestRuns.SOURCE, TestRuns.SHA), "application/json");
        assertTrue(body(none).contains(",\"input\":{\"mode\":\"none\",\"sha256\":null},"), body(none));
    }

    @Test void requestErrorsAreFixedCodesWithoutEcho() {
        String secret = TestRuns.SOURCE.replace("x", "secretName");
        assertEquals("{\"error\":\"REQUEST_INVALID\"}", body(post("{}", "application/json")));
        assertEquals("{\"error\":\"SOURCE_HASH_MISMATCH\"}", body(post(request(secret, TestRuns.SHA), "application/json")));
        assertEquals(415, post(request(TestRuns.SOURCE, TestRuns.SHA), "text/plain").getStatusCode().value());
        assertEquals(415, post(request(TestRuns.SOURCE, TestRuns.SHA), "application/json;charset=UTF-16").getStatusCode().value());
        var big = post(" ".repeat(RunController.BODY_LIMIT + 1), "application/json");
        assertEquals(413, big.getStatusCode().value());
        assertEquals("{\"error\":\"REQUEST_TOO_LARGE\"}", body(big));
        String oversized = "x".repeat(262_145);
        var limit = post(request(oversized, "0".repeat(64)), "application/json");
        assertEquals(413, limit.getStatusCode().value());
        assertEquals("{\"error\":\"SOURCE_LIMIT\"}", body(limit));
        var encoded = new MockHttpServletRequest("POST", "/api/runs");
        encoded.setContentType("application/json");
        encoded.addHeader("Content-Encoding", "gzip");
        encoded.setContent(request(TestRuns.SOURCE, TestRuns.SHA).getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"error\":\"UNSUPPORTED_ENCODING\"}", body(controller.submit(encoded)));
    }

    @Test void busyAndUnknownRunsAreSchedulingErrors() {
        for (int i = 0; i < 3; i++) assertEquals(202, post(request(TestRuns.SOURCE, TestRuns.SHA), "application/json").getStatusCode().value());
        var busy = post(request(TestRuns.SOURCE, TestRuns.SHA), "application/json");
        assertEquals(503, busy.getStatusCode().value());
        assertEquals("{\"error\":\"RUNNER_BUSY\"}", body(busy));
        assertEquals(404, controller.poll("run-00000000-0000-0000-0000-000000000000").getStatusCode().value());
        assertEquals(404, controller.poll("../etc").getStatusCode().value());
    }

    @Test void queueExpiryIsGone() {
        post(request(TestRuns.SOURCE, TestRuns.SHA), "application/json");
        String queued = id(post(request(TestRuns.SOURCE, TestRuns.SHA), "application/json"));
        runs.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
        var gone = controller.poll(queued);
        assertEquals(410, gone.getStatusCode().value());
        assertEquals("{\"error\":\"QUEUE_EXPIRED\"}", body(gone));
    }

    @Test void unavailableRunnerIsRetriedOnTheNextSubmission() {
        int[] attempts = {0};
        var flaky = new RunController(new RunnerAccess(() -> {
            if (attempts[0]++ == 0) throw new IOException("no docker");
            return runs.open();
        }));
        var request = new MockHttpServletRequest("POST", "/api/runs");
        request.setContentType("application/json");
        request.setContent(request(TestRuns.SOURCE, TestRuns.SHA).getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"error\":\"RUNNER_UNAVAILABLE\"}", body(flaky.submit(request)));
        assertEquals(404, flaky.poll("run-00000000-0000-0000-0000-000000000000").getStatusCode().value());
        var retry = new MockHttpServletRequest("POST", "/api/runs");
        retry.setContentType("application/json");
        retry.setContent(request(TestRuns.SOURCE, TestRuns.SHA).getBytes(StandardCharsets.UTF_8));
        assertEquals(202, flaky.submit(retry).getStatusCode().value());
    }

    @Test void requestParserIsStrict() {
        String valid = request(TestRuns.SOURCE, TestRuns.SHA);
        assertDoesNotThrow(() -> RunRequest.parse(valid.getBytes(StandardCharsets.UTF_8)));
        for (String bad : new String[]{
                valid.replace("\"inputMode\":\"none\"", "\"inputMode\":\"tty\""),
                valid.replace("1.2.0", "1.1.0"),
                valid.replace("\"inputMode\":\"none\"", "\"inputMode\":\"provided\""), // provided needs input
                valid.replace("\"inputMode\":\"none\"", "\"inputMode\":\"none\",\"input\":{\"text\":\"x\",\"sha256\":\"" + TestRuns.SHA + "\"}"),
                providedRequest(TestRuns.SOURCE, TestRuns.SHA, "x", "short"),
                providedRequest(TestRuns.SOURCE, TestRuns.SHA, "x", TestRuns.SHA).replace("\"text\":\"x\",", ""),
                valid.replace("\"inputMode\":\"none\"", "\"inputMode\":\"none\",\"inputMode\":\"none\""),
                valid.replace("\"inputMode\":\"none\"", "\"inputMode\":\"none\",\"command\":\"java\""),
                valid.replace("Main.java", "../Main.java"),
                valid.replace("1.2.0", "2.0.0"),
                valid + " {}",
                valid.replace(TestRuns.SHA, TestRuns.SHA.toUpperCase()),
                "[" .repeat(40) + "]".repeat(40),
                "{\"schemaVersion\":\"1.2.0\"}"}) {
            assertThrows(RunRequest.Invalid.class, () -> RunRequest.parse(bad.getBytes(StandardCharsets.UTF_8)), bad);
        }
        byte[] malformed = valid.getBytes(StandardCharsets.UTF_8);
        malformed[malformed.length - 3] = (byte) 0xC3; // truncated multi-byte sequence
        assertThrows(RunRequest.Invalid.class, () -> RunRequest.parse(malformed));
    }
}
