package com.codevisualization.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real loopback HTTP checks of the run API that need no Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendApplicationTest {
    @LocalServerPort
    private int port;

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    @Test
    void invalidSubmissionsAreRejectedBeforeTheRunnerOpens() throws Exception {
        var invalid = send(HttpRequest.newBuilder(uri("/api/runs"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json"));
        assertEquals(400, invalid.statusCode());
        assertEquals("{\"error\":\"REQUEST_INVALID\"}", invalid.body());
        var tooLarge = send(HttpRequest.newBuilder(uri("/api/runs"))
                .POST(HttpRequest.BodyPublishers.ofString(" ".repeat(2 * 1024 * 1024 + 1))).header("Content-Type", "application/json"));
        assertEquals(413, tooLarge.statusCode());
    }

    @Test
    void unknownRunsAreNotFound() throws Exception {
        var unknown = send(HttpRequest.newBuilder(uri("/api/runs/run-00000000-0000-0000-0000-000000000000")).GET());
        assertEquals(404, unknown.statusCode());
        assertEquals("{\"error\":\"RUN_NOT_FOUND\"}", unknown.body());
    }
}
