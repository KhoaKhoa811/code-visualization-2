package com.codevisualization.backend.api;

import com.codevisualization.backend.runner.RunInput;
import com.codevisualization.backend.runner.RunOutput;
import com.codevisualization.backend.runner.RunSupervisor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/**
 * Live console of a run over WebSocket (Part 8g, ADR 0001, specs/live-console.md):
 * streamed output, ordered input lines, EOF and state. The first connection to a run
 * is its controller; losing it while an interactive run executes cancels the run.
 * Messages are a fixed vocabulary of small JSON objects, never echoed back.
 */
@Configuration
@EnableWebSocket
public class ConsoleSocket implements WebSocketConfigurer {
    static final int MESSAGE_LIMIT = 32_768;
    private static final String PATH = "/api/runs/*/console";
    private static final java.util.regex.Pattern RUN_ID =
            java.util.regex.Pattern.compile("/api/runs/(run-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/console");
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxTokenCount(64).build())
            .build();

    private final RunnerAccess runner;

    public ConsoleSocket(RunnerAccess runner) { this.runner = runner; }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Local development origins only (the Vite dev server and the backend itself).
        registry.addHandler(new Handler(runner), PATH).setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    @Bean public ServletServerContainerFactoryBean consoleContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MESSAGE_LIMIT);
        container.setMaxBinaryMessageBufferSize(1024);
        return container;
    }

    /** One connection: its run, whether it controls input, and how far output has been sent. */
    private static final class Connection {
        final RunSupervisor supervisor;
        final RunSupervisor.Run run;
        final WebSocketSession session;
        final boolean controller;
        final CharsetDecoder out = decoder(), err = decoder();
        int sent;
        String state;
        ScheduledFuture<?> pump;

        Connection(RunSupervisor supervisor, RunSupervisor.Run run, WebSocketSession session, boolean controller) {
            this.supervisor = supervisor; this.run = run; this.session = session; this.controller = controller;
        }
    }

    static final class Handler extends TextWebSocketHandler {
        private final RunnerAccess runner;
        private final Map<String, Connection> connections = new ConcurrentHashMap<>();
        private final Set<String> controlled = ConcurrentHashMap.newKeySet(); // runs that ever had a controller
        private final ScheduledExecutorService pumps = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "console-pump");
            thread.setDaemon(true);
            return thread;
        });

        Handler(RunnerAccess runner) { this.runner = runner; }

        @Override public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
            var matcher = raw.getUri() == null ? null : RUN_ID.matcher(raw.getUri().getPath());
            Optional<RunSupervisor> supervisor = runner.current();
            Optional<RunSupervisor.Run> run = matcher != null && matcher.matches() && supervisor.isPresent()
                    ? supervisor.get().find(matcher.group(1)) : Optional.empty();
            if (run.isEmpty() || supervisor.get().expired(run.get())) {
                raw.close(new CloseStatus(4404, "RUN_NOT_FOUND"));
                return;
            }
            // A slow client whose unsent messages exceed the buffer is disconnected (Part 4r).
            var session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 512 * 1024);
            boolean controller = controlled.add(run.get().id());
            var connection = new Connection(supervisor.get(), run.get(), session, controller);
            connections.put(raw.getId(), connection);
            connection.pump = pumps.scheduleWithFixedDelay(() -> pump(connection), 0, 50, TimeUnit.MILLISECONDS);
        }

        /** Sends new state and output; after confirmed termination sends the rest, then end, and closes. */
        private void pump(Connection c) {
            try {
                var console = c.supervisor.console(c.run);
                String state = "{\"type\":\"state\",\"phase\":\"" + console.phase().wire() + "\",\"interactive\":" + console.interactive()
                        + ",\"controller\":" + c.controller + ",\"inputOpen\":" + (console.input() != null && console.input().accepting()) + "}";
                if (!state.equals(c.state)) { c.state = state; send(c, state); }
                var chunks = c.supervisor.outputSince(c.run, c.sent);
                for (RunOutput.Chunk chunk : chunks) {
                    String text = decode(chunk.error() ? c.err : c.out, chunk.bytes(), false);
                    if (!text.isEmpty()) send(c, output(chunk.error(), text));
                }
                c.sent += chunks.size();
                if (console.ended()) {
                    String outRest = decode(c.out, new byte[0], true), errRest = decode(c.err, new byte[0], true);
                    if (!outRest.isEmpty()) send(c, output(false, outRest));
                    if (!errRest.isEmpty()) send(c, output(true, errRest));
                    send(c, "{\"type\":\"end\"}");
                    c.pump.cancel(false);
                    c.session.close(CloseStatus.NORMAL);
                }
            } catch (IOException | RuntimeException failure) {
                c.pump.cancel(false);
                try { c.session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { }
            }
        }

        @Override protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws Exception {
            Connection c = connections.get(raw.getId());
            if (c == null) return;
            Long sequence = null;
            String type = null, text = null;
            try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), message.getPayload())) {
                Set<String> seen = new HashSet<>();
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException();
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String name = parser.currentName();
                    if (!seen.add(name)) throw new IllegalArgumentException();
                    JsonToken value = parser.nextToken();
                    switch (name) {
                        case "type" -> { if (value != JsonToken.VALUE_STRING) throw new IllegalArgumentException(); type = parser.getString(); }
                        case "text" -> { if (value != JsonToken.VALUE_STRING) throw new IllegalArgumentException(); text = parser.getString(); }
                        case "seq" -> { if (value != JsonToken.VALUE_NUMBER_INT) throw new IllegalArgumentException(); sequence = parser.getLongValue(); }
                        default -> throw new IllegalArgumentException();
                    }
                }
                if (parser.nextToken() != null) throw new IllegalArgumentException();
            } catch (JacksonException | IllegalArgumentException malformed) {
                send(c, "{\"type\":\"reject\",\"code\":\"INPUT_INVALID\"}");
                return;
            }
            boolean input = "input".equals(type) && sequence != null && sequence > 0 && text != null;
            boolean eof = "eof".equals(type) && sequence == null && text == null;
            if (!input && !eof) { send(c, "{\"type\":\"reject\",\"code\":\"INPUT_INVALID\"}"); return; }
            String code;
            if (!c.controller) code = "NOT_CONTROLLER";
            else {
                var console = c.supervisor.console(c.run);
                RunInput channel = console.input();
                if (!console.interactive()) code = "INPUT_NOT_INTERACTIVE";
                else if (channel == null) code = "INPUT_CLOSED";
                else if (eof) { channel.eof(); return; }
                else {
                    RunInput.Outcome outcome = channel.offer(sequence, text);
                    code = outcome == RunInput.Outcome.ACCEPTED || outcome == RunInput.Outcome.DUPLICATE ? null : outcome.name();
                }
            }
            if (code == null) send(c, "{\"type\":\"ack\",\"seq\":" + sequence + "}");
            else send(c, "{\"type\":\"reject\"" + (sequence == null ? "" : ",\"seq\":" + sequence) + ",\"code\":\"" + code + "\"}");
        }

        @Override public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
            Connection c = connections.remove(raw.getId());
            if (c == null) return;
            c.pump.cancel(false);
            if (!c.controller) return;
            var console = c.supervisor.console(c.run);
            if (console.interactive() && console.phase() == RunSupervisor.Phase.EXECUTING) c.supervisor.cancelDisconnected(c.run);
        }

        @Override public void handleTransportError(WebSocketSession raw, Throwable failure) throws Exception {
            raw.close(CloseStatus.SERVER_ERROR);
        }

        private static void send(Connection c, String json) throws IOException {
            if (c.session.isOpen()) c.session.sendMessage(new TextMessage(json));
        }

        private static String output(boolean error, String text) {
            var b = new StringBuilder("{\"type\":\"output\",\"stream\":\"").append(error ? "stderr" : "stdout").append("\",\"text\":");
            RunJson.quote(b, text);
            return b.append('}').toString();
        }
    }

    private static CharsetDecoder decoder() {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    /** Decodes UTF-8 across chunk boundaries: an incomplete trailing sequence waits for the next chunk. */
    private static String decode(CharsetDecoder decoder, byte[] bytes, boolean end) {
        synchronized (decoder) {
            ByteBuffer in = carry(decoder, bytes);
            CharBuffer out = CharBuffer.allocate(in.remaining() * 2 + 4);
            decoder.decode(in, out, end);
            if (end) decoder.flush(out);
            leftovers.put(decoder, in.hasRemaining() ? java.util.Arrays.copyOfRange(in.array(), in.position(), in.limit()) : new byte[0]);
            out.flip();
            return out.toString();
        }
    }

    private static final Map<CharsetDecoder, byte[]> leftovers = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static ByteBuffer carry(CharsetDecoder decoder, byte[] bytes) {
        byte[] previous = leftovers.getOrDefault(decoder, new byte[0]);
        byte[] all = new byte[previous.length + bytes.length];
        System.arraycopy(previous, 0, all, 0, previous.length);
        System.arraycopy(bytes, 0, all, previous.length, bytes.length);
        return ByteBuffer.wrap(all);
    }
}
