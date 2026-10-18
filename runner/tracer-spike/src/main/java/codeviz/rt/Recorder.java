package codeviz.rt;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Spike runtime called by instrumented bytecode. Writes one JSON object per line
 * to the file named by the {@code codeviz.trace} system property, separate from
 * the program's stdout/stderr. Every helper that replaces an instruction performs
 * the original operation exactly once, with the same exceptions, then records it.
 * Not thread-safe; spike programs are single-threaded. No limits are enforced.
 */
public final class Recorder {
    private static final BufferedWriter OUT = open();
    private static final Map<Object, Integer> IDS = new IdentityHashMap<>();
    private static long seq;
    private static int frames;

    private Recorder() { }

    private static BufferedWriter open() {
        String path = System.getProperty("codeviz.trace");
        if (path == null) throw new IllegalStateException("codeviz.trace not set");
        try {
            BufferedWriter writer = Files.newBufferedWriter(Path.of(path), StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { writer.flush(); } catch (IOException ignored) { }
            }));
            return writer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void emit(String kind, int frame, int line, String fields) {
        try {
            OUT.write("{\"seq\":" + seq++ + ",\"kind\":\"" + kind + "\",\"frame\":" + frame
                    + ",\"line\":" + line + (fields.isEmpty() ? "" : "," + fields) + "}\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int id(Object o) {
        return IDS.computeIfAbsent(o, k -> IDS.size() + 1);
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    /** Typed value. Longs are strings so JavaScript never loses precision. */
    private static String ref(Object o) {
        if (o == null) return "{\"t\":\"null\"}";
        if (o instanceof String s) return "{\"t\":\"String\",\"id\":" + id(o) + ",\"v\":" + quote(s) + "}";
        return "{\"t\":" + quote(o.getClass().getTypeName()) + ",\"id\":" + id(o) + "}";
    }

    private static String num(String type, String v) { return "{\"t\":\"" + type + "\",\"v\":" + v + "}"; }

    private static String typed(String desc, int v) {
        return switch (desc) {
            case "Z" -> num("boolean", v != 0 ? "true" : "false");
            case "C" -> num("char", quote(String.valueOf((char) v)));
            case "B" -> num("byte", Integer.toString(v));
            case "S" -> num("short", Integer.toString(v));
            default -> num("int", Integer.toString(v));
        };
    }

    // ---- frames -------------------------------------------------------------

    public static int enter(String method, int line) {
        int frame = ++frames;
        emit("ENTER", frame, line, "\"method\":" + quote(method));
        return frame;
    }

    public static void exit(int frame, int line) { emit("EXIT", frame, line, ""); }
    public static int exitI(int v, String desc, int frame, int line) { emit("EXIT", frame, line, "\"value\":" + typed(desc, v)); return v; }
    public static long exitJ(long v, int frame, int line) { emit("EXIT", frame, line, "\"value\":" + num("long", "\"" + v + "\"")); return v; }
    public static double exitD(double v, int frame, int line) { emit("EXIT", frame, line, "\"value\":" + num("double", dbl(v))); return v; }
    public static Object exitA(Object v, int frame, int line) { emit("EXIT", frame, line, "\"value\":" + ref(v)); return v; }

    public static Throwable thrown(Throwable t, int frame) {
        emit("THROW_EXIT", frame, -1, "\"exception\":" + quote(t.getClass().getName()));
        return t;
    }

    private static String dbl(double v) {
        return Double.isFinite(v) ? Double.toString(v) : quote(Double.toString(v));
    }

    // ---- locals -------------------------------------------------------------

    public static void localI(int v, String name, String desc, int frame, int line) {
        emit("LOCAL", frame, line, "\"name\":" + quote(name) + ",\"value\":" + typed(desc, v));
    }
    public static void localJ(long v, String name, int frame, int line) {
        emit("LOCAL", frame, line, "\"name\":" + quote(name) + ",\"value\":" + num("long", "\"" + v + "\""));
    }
    public static void localD(double v, String name, int frame, int line) {
        emit("LOCAL", frame, line, "\"name\":" + quote(name) + ",\"value\":" + num("double", dbl(v)));
    }
    public static void localA(Object v, String name, int frame, int line) {
        emit("LOCAL", frame, line, "\"name\":" + quote(name) + ",\"value\":" + ref(v));
    }

    // ---- arrays: each helper performs the original instruction once ----------

    public static void newArray(Object array, int frame, int line) {
        StringBuilder values = new StringBuilder("[");
        int length = java.lang.reflect.Array.getLength(array);
        for (int i = 0; i < length; i++) {
            if (i > 0) values.append(',');
            values.append(element(array, i));
        }
        emit("ARRAY_NEW", frame, line, "\"array\":" + ref(array) + ",\"length\":" + length
                + ",\"values\":" + values.append(']'));
    }

    private static String element(Object array, int i) {
        return switch (array) {
            case int[] a -> num("int", Integer.toString(a[i]));
            case long[] a -> num("long", "\"" + a[i] + "\"");
            case double[] a -> num("double", dbl(a[i]));
            case boolean[] a -> num("boolean", Boolean.toString(a[i]));
            case char[] a -> num("char", quote(String.valueOf(a[i])));
            case Object[] a -> ref(a[i]);
            default -> "{\"t\":\"unsupported\"}";
        };
    }

    public static void iastore(int[] a, int i, int v, int frame, int line) {
        a[i] = v;
        emit("ARRAY_WRITE", frame, line, "\"array\":" + ref(a) + ",\"index\":" + i + ",\"value\":" + num("int", Integer.toString(v)));
    }
    public static int iaload(int[] a, int i, int frame, int line) {
        int v = a[i];
        emit("ARRAY_READ", frame, line, "\"array\":" + ref(a) + ",\"index\":" + i + ",\"value\":" + num("int", Integer.toString(v)));
        return v;
    }
    public static void aastore(Object[] a, int i, Object v, int frame, int line) {
        a[i] = v; // ArrayStoreException preserved by the real store.
        emit("ARRAY_WRITE", frame, line, "\"array\":" + ref(a) + ",\"index\":" + i + ",\"value\":" + ref(v));
    }
    public static Object aaload(Object[] a, int i, int frame, int line) {
        Object v = a[i];
        emit("ARRAY_READ", frame, line, "\"array\":" + ref(a) + ",\"index\":" + i + ",\"value\":" + ref(v));
        return v;
    }

    // ---- comparisons: return the bytecode's jump decision unchanged ----------

    /** {@code op} is the JVM opcode name; {@code jump} is whether the branch is taken. */
    public static boolean icmp(int left, int right, String op, int frame, int line) {
        boolean jump = switch (op) {
            case "IF_ICMPEQ" -> left == right; case "IF_ICMPNE" -> left != right;
            case "IF_ICMPLT" -> left < right; case "IF_ICMPGE" -> left >= right;
            case "IF_ICMPGT" -> left > right; case "IF_ICMPLE" -> left <= right;
            default -> throw new IllegalArgumentException(op);
        };
        emit("COMPARE", frame, line, "\"op\":" + quote(op) + ",\"left\":" + num("int", Integer.toString(left))
                + ",\"right\":" + num("int", Integer.toString(right)) + ",\"jump\":" + jump);
        return jump;
    }

    public static boolean izero(int value, String op, int frame, int line) {
        boolean jump = switch (op) {
            case "IFLT" -> value < 0; case "IFGE" -> value >= 0;
            case "IFGT" -> value > 0; case "IFLE" -> value <= 0;
            default -> throw new IllegalArgumentException(op);
        };
        emit("COMPARE", frame, line, "\"op\":" + quote(op) + ",\"left\":" + num("int", Integer.toString(value))
                + ",\"right\":" + num("int", "0") + ",\"jump\":" + jump);
        return jump;
    }
}
