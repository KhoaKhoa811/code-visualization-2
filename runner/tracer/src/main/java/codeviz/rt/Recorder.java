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
 * Runtime half of the Part 7b tracer, called only by instrumented bytecode.
 * Each helper performs the original JVM instruction exactly once, with the same
 * exception, then appends one raw record: the static site number and values.
 * Records go to the file named by {@code codeviz.trace}, never to stdout/stderr.
 * The trusted {@code TraceBuilder} gives records meaning; nothing here is trusted.
 *
 * Raw record lines (space separated; strings and messages as hex UTF-16 units):
 * {@code M <ref> <len>} main entry; {@code R} normal main exit; {@code L} raw limit;
 * {@code S<id> <hex>} first sight of a string; {@code x<site> <class> <hex>} failure;
 * {@code <site> <values...>} a completed operation. A ref is {@code r<id>} or {@code n}.
 */
public final class Recorder {
    /** Raw byte cap; beyond it the run is halted as limited (builder enforces trace limits). */
    static final long RAW_LIMIT = 16L * 1024 * 1024;
    static final int LIMIT_EXIT = 86;

    private static final BufferedWriter OUT = open();
    private static final Map<Object, Integer> IDS = new IdentityHashMap<>();
    private static long written;

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

    private static void line(String record) {
        try {
            written += record.length() + 1;
            if (written > RAW_LIMIT) {
                OUT.write("L\n");
                OUT.flush();
                System.out.flush();
                System.err.flush();
                Runtime.getRuntime().halt(LIMIT_EXIT);
            }
            OUT.write(record);
            OUT.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String hex(String s) {
        StringBuilder b = new StringBuilder(s.length() * 4 + 1).append('h');
        for (int i = 0; i < s.length(); i++) b.append(String.format("%04x", (int) s.charAt(i)));
        return b.toString();
    }

    private static String ref(Object o) {
        if (o == null) return "n";
        Integer id = IDS.get(o);
        if (id == null) {
            id = IDS.size() + 1;
            IDS.put(o, id);
            if (o instanceof String s) line("S" + id + " " + hex(s));
        }
        return "r" + id;
    }

    private static void fail(int site, RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        line("x" + site + " " + e.getClass().getName() + " " + hex(message));
        try { OUT.flush(); } catch (IOException ignored) { }
    }

    // ---- frame ------------------------------------------------------------------

    public static void enter(String[] args) { line("M " + ref(args) + " " + args.length); }

    public static void exit() {
        line("R");
        try { OUT.flush(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // ---- stored values ----------------------------------------------------------

    public static void vi(int value, int site) { line(site + " " + value); }
    public static void va(Object value, int site) { String r = ref(value); line(site + " " + r); }

    // ---- arrays -----------------------------------------------------------------

    public static int iaload(int[] a, int i, int site) {
        int v;
        try { v = a[i]; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + i + " " + v);
        return v;
    }

    public static void iastore(int[] a, int i, int v, int site) {
        try { a[i] = v; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + i + " " + v);
    }

    public static int arraylength(int[] a, int site) {
        int n;
        try { n = a.length; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + n);
        return n;
    }

    public static int[] newIntArray(int n, int site) {
        int[] a;
        try { a = new int[n]; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + n);
        return a;
    }

    /** Called once a constant-element literal is fully initialized. */
    public static void literal(int[] a, int site) {
        StringBuilder b = new StringBuilder().append(site).append(' ').append(ref(a)).append(' ').append(a.length);
        for (int v : a) b.append(' ').append(v);
        line(b.toString());
    }

    // ---- int arithmetic ---------------------------------------------------------

    public static int iadd(int l, int r, int site) { int v = l + r; line(site + " " + l + " " + r + " " + v); return v; }
    public static int isub(int l, int r, int site) { int v = l - r; line(site + " " + l + " " + r + " " + v); return v; }
    public static int imul(int l, int r, int site) { int v = l * r; line(site + " " + l + " " + r + " " + v); return v; }

    public static int idiv(int l, int r, int site) {
        int v;
        try { v = l / r; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + l + " " + r + " " + v);
        return v;
    }

    public static int irem(int l, int r, int site) {
        int v;
        try { v = l % r; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + l + " " + r + " " + v);
        return v;
    }

    public static int ineg(int value, int site) { int v = -value; line(site + " " + value + " " + v); return v; }

    // ---- condition jumps: return the original jump decision -----------------------

    public static boolean jcmp(int l, int r, int opcode, int site) {
        boolean jump = switch (opcode) {
            case 159 -> l == r; case 160 -> l != r; case 161 -> l < r;
            case 162 -> l >= r; case 163 -> l > r; case 164 -> l <= r;
            default -> throw new IllegalArgumentException("opcode " + opcode);
        };
        line(site + " " + (jump ? 1 : 0) + " " + l + " " + r);
        return jump;
    }

    public static boolean jzero(int v, int opcode, int site) {
        boolean jump = switch (opcode) {
            case 153 -> v == 0; case 154 -> v != 0; case 155 -> v < 0;
            case 156 -> v >= 0; case 157 -> v > 0; case 158 -> v <= 0;
            default -> throw new IllegalArgumentException("opcode " + opcode);
        };
        line(site + " " + (jump ? 1 : 0) + " " + v);
        return jump;
    }

    public static boolean jref(Object l, Object r, int opcode, int site) {
        boolean jump = opcode == 165 ? l == r : l != r;
        String a = ref(l), b = ref(r);
        line(site + " " + (jump ? 1 : 0) + " " + a + " " + b);
        return jump;
    }

    public static boolean jnull(Object v, int opcode, int site) {
        boolean jump = opcode == 198 ? v == null : v != null;
        String a = ref(v);
        line(site + " " + (jump ? 1 : 0) + " " + a);
        return jump;
    }
}
