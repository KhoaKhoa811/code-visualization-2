package codeviz.tracer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON reader for trusted plan files and tests: objects to maps, arrays to lists, numbers to Long. */
final class JsonReader {
    private final String s;
    private int i;

    private JsonReader(String s) { this.s = s; }

    static Object parse(String json) {
        JsonReader p = new JsonReader(json);
        Object v = p.value();
        p.space();
        if (p.i != json.length()) throw new IllegalArgumentException("trailing data at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String json) { return (Map<String, Object>) parse(json); }

    private Object value() {
        space();
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (s.charAt(i) == '}') { i++; return map; }
            while (true) {
                space();
                String key = string();
                space(); expect(':');
                map.put(key, value());
                space();
                if (s.charAt(i++) == '}') return map;
            }
        }
        if (c == '[') {
            i++;
            List<Object> list = new ArrayList<>();
            space();
            if (s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(value());
                space();
                if (s.charAt(i++) == ']') return list;
            }
        }
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return true; }
        if (s.startsWith("false", i)) { i += 5; return false; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int start = i;
        while (i < s.length() && "-0123456789".indexOf(s.charAt(i)) >= 0) i++;
        return Long.parseLong(s.substring(start, i));
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n'); case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);
            }
        }
    }

    private void expect(char c) {
        if (s.charAt(i++) != c) throw new IllegalArgumentException("expected " + c + " at " + (i - 1));
    }

    private void space() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
}
