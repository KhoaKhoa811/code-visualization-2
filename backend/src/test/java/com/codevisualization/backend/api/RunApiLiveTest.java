package com.codevisualization.backend.api;

import static org.junit.jupiter.api.Assertions.*;

import com.codevisualization.backend.runner.BoundedCommandRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Part 7d live checks: the real application over loopback HTTP, the single
 * supervisor and real restricted containers. Every snapshot with a trace must
 * pass contracts/run/check.cjs (run-v1 plus the shared trace-v1 checks).
 */
@EnabledIfSystemProperty(named = "codeviz.docker.live", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunApiLiveTest {
    private static final String BUBBLE_SORT = """
            public class Main {
                public static void main(String[] args) {
                    int[] a = {3, 1, 2};
                    for (int i = 0; i < a.length - 1; i++) {
                        for (int j = 0; j < a.length - 1 - i; j++) {
                            if (a[j] > a[j + 1]) {
                                int t = a[j];
                                a[j] = a[j + 1];
                                a[j + 1] = t;
                            }
                        }
                    }
                    for (int k = 0; k < a.length; k++) System.out.println(a[k]);
                }
            }
            """;
    private static final String LOOP = "public class Main {\n    public static void main(String[] args) {\n"
            + "        int n = 1;\n        while (true) { }\n    }\n}\n";

    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String submit(String source) throws Exception {
        var accepted = send("POST", "/api/runs", RunControllerTest.request(source, sha(source)));
        assertEquals(202, accepted.statusCode(), accepted.body());
        return accepted.headers().firstValue("Location").orElseThrow().substring("/api/runs/".length());
    }

    private String await(String id, String phase) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String body = send("GET", "/api/runs/" + id, null).body();
        while (!body.contains("\"phase\":\"" + phase + "\"") && System.nanoTime() < deadline) {
            Thread.sleep(50);
            body = send("GET", "/api/runs/" + id, null).body();
        }
        assertTrue(body.contains("\"phase\":\"" + phase + "\""), body.length() > 400 ? body.substring(0, 400) : body);
        return body;
    }

    static void check(String name, String snapshot) throws Exception {
        Path dir = Path.of("target", "live-runs");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".json");
        Files.writeString(file, snapshot, StandardCharsets.UTF_8);
        Process node = new ProcessBuilder("node", Path.of("../contracts/run/check.cjs").toString(), file.toString())
                .redirectErrorStream(true).start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(node.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, node.exitValue(), "run-v1 check failed: " + output);
    }

    @Test void bubbleSortRunsThroughTheApiAndLeavesNoContainer() throws Exception {
        String id = submit(BUBBLE_SORT);
        String finished = await(id, "finished");
        check("bubble-sort", finished);
        assertTrue(finished.contains("\"status\":\"completed\"") && finished.contains("\"status\":\"complete\""));
        assertTrue(finished.contains("\"slotHeld\":false") && finished.endsWith("\"infrastructureFailures\":[]}"));
        var output = send("GET", "/api/runs/" + id + "/output", null);
        assertEquals("{\"runId\":\"" + id + "\",\"stdout\":\"1\\n2\\n3\\n\",\"stderr\":\"\"}", output.body());
        try (var control = new BoundedCommandRunner()) {
            String owned = control.execute(List.of(System.getProperty("codeviz.docker.executable"), "--context", "desktop-linux",
                    "ps", "--all", "--no-trunc", "--filter", "label=codeviz.owner", "--format", "{{.ID}}"), Duration.ofSeconds(5));
            assertTrue(owned.isBlank(), "owned containers left: " + owned);
        }
    }

    @Test void queueBusyAndCancellationThroughTheApi() throws Exception {
        String active = submit(LOOP);
        String next = submit(BUBBLE_SORT);
        String queued = submit(BUBBLE_SORT);
        var busy = send("POST", "/api/runs", RunControllerTest.request(BUBBLE_SORT, sha(BUBBLE_SORT)));
        assertEquals(503, busy.statusCode());
        assertEquals("{\"error\":\"RUNNER_BUSY\"}", busy.body());

        assertEquals(202, send("POST", "/api/runs/" + queued + "/cancel", null).statusCode());
        String queuedCancelled = await(queued, "finished");
        check("queued-cancelled", queuedCancelled);
        assertTrue(queuedCancelled.contains("RUN_CANCELLED") && queuedCancelled.contains("\"status\":\"unavailable\""));

        assertTrue(await(next, "queued").contains("\"slotHeld\":false"));
        await(active, "executing");
        Thread.sleep(300); // let the JVM start and record main entry
        assertEquals(202, send("POST", "/api/runs/" + active + "/cancel", null).statusCode());
        String activeCancelled = await(active, "finished");
        check("active-cancelled", activeCancelled);
        assertTrue(activeCancelled.contains("\"status\":\"cancelled\"") && activeCancelled.contains("RUN_CANCELLED"));
        assertEquals(409, send("POST", "/api/runs/" + active + "/cancel", null).statusCode());

        String promoted = await(next, "finished");
        check("promoted", promoted);
        assertTrue(promoted.contains("\"status\":\"completed\""));
    }
}
