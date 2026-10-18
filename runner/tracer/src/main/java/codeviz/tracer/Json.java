package codeviz.tracer;

import java.util.List;
import java.util.Map;

/** Minimal deterministic JSON writer for maps, lists, strings, ints, booleans and null. */
final class Json {
    private Json() { }

    static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object value) {
        switch (value) {
            case null -> b.append("null");
            case String s -> string(b, s);
            case Integer i -> b.append(i);
            case Long l -> b.append(l);
            case Boolean bool -> b.append(bool);
            case Map<?, ?> map -> {
                b.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    string(b, (String) entry.getKey());
                    b.append(':');
                    write(b, entry.getValue());
                }
                b.append('}');
            }
            case List<?> list -> {
                b.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) b.append(',');
                    write(b, list.get(i));
                }
                b.append(']');
            }
            default -> throw new IllegalArgumentException("Unsupported JSON value " + value.getClass());
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    // Escape controls and lone surrogates so output is valid UTF-8 JSON.
                    if (c < 0x20 || Character.isSurrogate(c) && !pairedSurrogate(s, i)) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    private static boolean pairedSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }
}
