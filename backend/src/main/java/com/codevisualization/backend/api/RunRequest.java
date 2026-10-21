package com.codevisualization.backend.api;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/**
 * Run request v1 (contracts/run/v1/request.schema.json, version 1.2.0), read with a strict
 * streaming parser: UTF-8 only, duplicate keys rejected, nesting at most 32 and
 * at most 65,536 tokens (execution-isolation Part 4p). Exact source text is kept;
 * its size, Unicode and hash are checked at admission. Part 10a: {@code inputMode} provided
 * carries test input text and its SHA-256 ({@code input}); the other modes must not.
 */
record RunRequest(String text, String sha256, String inputMode, String inputText, String inputSha256) {
    boolean live() { return "live".equals(inputMode); }

    static final class Invalid extends Exception {
        Invalid() { super("REQUEST_INVALID", null, false, false); }
    }

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(32).maxTokenCount(65_536).build())
            .build();

    static RunRequest parse(byte[] body) throws Invalid {
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException malformed) {
            throw new Invalid();
        }
        try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), json)) {
            String schemaVersion = null, inputMode = null, file = null, text = null, sha256 = null, inputText = null, inputSha = null;
            boolean hasInput = false;
            Set<String> seen = new HashSet<>();
            expect(parser.nextToken(), JsonToken.START_OBJECT);
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                if (!seen.add(name)) throw new Invalid();
                switch (name) {
                    case "schemaVersion" -> schemaVersion = string(parser);
                    case "inputMode" -> inputMode = string(parser);
                    case "source" -> {
                        expect(parser.nextToken(), JsonToken.START_OBJECT);
                        Set<String> sourceSeen = new HashSet<>();
                        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                            String field = parser.currentName();
                            if (!sourceSeen.add(field)) throw new Invalid();
                            switch (field) {
                                case "file" -> file = string(parser);
                                case "text" -> text = string(parser);
                                case "sha256" -> sha256 = string(parser);
                                default -> throw new Invalid();
                            }
                        }
                        expect(parser.currentToken(), JsonToken.END_OBJECT);
                    }
                    case "input" -> {
                        hasInput = true;
                        expect(parser.nextToken(), JsonToken.START_OBJECT);
                        Set<String> inputSeen = new HashSet<>();
                        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                            String field = parser.currentName();
                            if (!inputSeen.add(field)) throw new Invalid();
                            switch (field) {
                                case "text" -> inputText = string(parser);
                                case "sha256" -> inputSha = string(parser);
                                default -> throw new Invalid();
                            }
                        }
                        expect(parser.currentToken(), JsonToken.END_OBJECT);
                    }
                    default -> throw new Invalid();
                }
            }
            expect(parser.currentToken(), JsonToken.END_OBJECT);
            if (parser.nextToken() != null) throw new Invalid();
            if (!"1.2.0".equals(schemaVersion) || !("none".equals(inputMode) || "live".equals(inputMode) || "provided".equals(inputMode))
                    || !"Main.java".equals(file) || text == null || sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new Invalid();
            if ("provided".equals(inputMode) != hasInput) throw new Invalid();
            if (hasInput && (inputText == null || inputSha == null || !inputSha.matches("[0-9a-f]{64}"))) throw new Invalid();
            return new RunRequest(text, sha256, inputMode, inputText, inputSha);
        } catch (JacksonException malformed) {
            throw new Invalid();
        }
    }

    private static String string(JsonParser parser) throws Invalid {
        expect(parser.nextToken(), JsonToken.VALUE_STRING);
        return parser.getString();
    }

    private static void expect(JsonToken actual, JsonToken expected) throws Invalid {
        if (actual != expected) throw new Invalid();
    }
}
