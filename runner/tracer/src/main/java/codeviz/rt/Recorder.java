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
 * {@code u<site>} an uncaptured statement starts (Part 8a); {@code A<id> <len> <values...>} the
 * contents of an int[] seen for the first time, or re-read after a statement that may write arrays;
 * {@code <site> <values...>} a completed operation. A ref is {@code r<id>} or {@code n}.
 */
public final class Recorder {
    /** Raw byte cap; beyond it the run is halted as limited (builder enforces trace limits). */
    static final long RAW_LIMIT = 16L * 1024 * 1024;
    static final int LIMIT_EXIT = 86;

    /** Records may sit at most this long in the buffer, so a killed run keeps a recent prefix. */
    static final long FLUSH_MILLIS = 100;

    private static final Object LOCK = new Object();
    private static final BufferedWriter OUT = open();
    private static final Map<Object, Integer> IDS = new IdentityHashMap<>();
    private static long written;
    // Set by an uncaptured statement that may write arrays; the next record re-reads them first.
    private static boolean dirty;

    private Recorder() { }

    private static BufferedWriter open() {
        String path = System.getProperty("codeviz.trace");
        if (path == null) throw new IllegalStateException("codeviz.trace not set");
        try {
            BufferedWriter writer = Files.newBufferedWriter(Path.of(path), StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (LOCK) { try { writer.flush(); } catch (IOException ignored) { } }
            }));
            Thread flusher = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(FLUSH_MILLIS); } catch (InterruptedException e) { return; }
                    synchronized (LOCK) { try { writer.flush(); } catch (IOException ignored) { return; } }
                }
            }, "codeviz-recorder-flush");
            flusher.setDaemon(true);
            flusher.start();
            return writer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void line(String record) {
        synchronized (LOCK) {
            if (dirty) { dirty = false; resync(); }
            write(record);
        }
    }

    /** Contents of every known int[], after an uncaptured statement that may have changed them. */
    private static void resync() {
        Object[] byId = new Object[IDS.size() + 1];
        for (var entry : IDS.entrySet()) byId[entry.getValue()] = entry.getKey();
        for (int id = 1; id < byId.length; id++) if (byId[id] instanceof int[] a) write(contents(id, a));
    }

    private static String contents(int id, int[] a) {
        StringBuilder b = new StringBuilder().append('A').append(id).append(' ').append(a.length);
        for (int v : a) b.append(' ').append(v);
        return b.toString();
    }

    private static void write(String record) {
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
            // Write before registering, so a pending re-read does not include this object twice.
            if (o instanceof String s) line("S" + id + " " + hex(s));
            else if (o instanceof int[] a) line(contents(id, a)); // created by uncaptured code
            IDS.put(o, id);
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

    // ---- uncaptured statements (Part 8a) -------------------------------------------

    public static void region(int site, boolean mayWriteArrays) {
        synchronized (LOCK) {
            line("u" + site);
            if (mayWriteArrays) dirty = true;
        }
    }

    /** Registers an array allocated by covered code; its ALLOCATE record carries the contents. */
    private static String fresh(Object o) {
        synchronized (LOCK) { if (dirty) { dirty = false; resync(); } } // the re-read must not include the new array
        Integer id = IDS.size() + 1;
        IDS.put(o, id);
        return "r" + id;
    }

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
        String r = ref(a); // before the store: a first-sight snapshot must show the previous contents
        try { a[i] = v; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + r + " " + i + " " + v);
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
        line(site + " " + fresh(a) + " " + n);
        return a;
    }

    /** Called once a constant-element literal is fully initialized. */
    public static void literal(int[] a, int site) {
        StringBuilder b = new StringBuilder().append(site).append(' ').append(fresh(a)).append(' ').append(a.length);
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

    // ---- Part 8b: other primitive types --------------------------------------------
    // long values are decimal; float and double values are raw IEEE-754 bits in hex.

    private static String f(float v) { return hex(Integer.toHexString(Float.floatToRawIntBits(v)), 8); }
    private static String d(double v) { return hex(Long.toHexString(Double.doubleToRawLongBits(v)), 16); }
    private static String hex(String digits, int width) { return "0".repeat(width - digits.length()) + digits; }

    public static void vl(long v, int site) { line(site + " " + v); }
    public static void vf(float v, int site) { line(site + " " + f(v)); }
    public static void vd(double v, int site) { line(site + " " + d(v)); }

    private static int i3(int l, int r, int v, int site) { line(site + " " + l + " " + r + " " + v); return v; }
    public static int iand(int l, int r, int site) { return i3(l, r, l & r, site); }
    public static int ior(int l, int r, int site) { return i3(l, r, l | r, site); }
    public static int ixor(int l, int r, int site) { return i3(l, r, l ^ r, site); }
    public static int ishl(int l, int r, int site) { return i3(l, r, l << r, site); }
    public static int ishr(int l, int r, int site) { return i3(l, r, l >> r, site); }
    public static int iushr(int l, int r, int site) { return i3(l, r, l >>> r, site); }

    private static long l3(long l, Object r, long v, int site) { line(site + " " + l + " " + r + " " + v); return v; }
    public static long ladd(long l, long r, int site) { return l3(l, r, l + r, site); }
    public static long lsub(long l, long r, int site) { return l3(l, r, l - r, site); }
    public static long lmul(long l, long r, int site) { return l3(l, r, l * r, site); }
    public static long ldiv(long l, long r, int site) {
        long v;
        try { v = l / r; } catch (RuntimeException e) { fail(site, e); throw e; }
        return l3(l, r, v, site);
    }
    public static long lrem(long l, long r, int site) {
        long v;
        try { v = l % r; } catch (RuntimeException e) { fail(site, e); throw e; }
        return l3(l, r, v, site);
    }
    public static long land(long l, long r, int site) { return l3(l, r, l & r, site); }
    public static long lor(long l, long r, int site) { return l3(l, r, l | r, site); }
    public static long lxor(long l, long r, int site) { return l3(l, r, l ^ r, site); }
    public static long lshl(long l, int r, int site) { return l3(l, r, l << r, site); }
    public static long lshr(long l, int r, int site) { return l3(l, r, l >> r, site); }
    public static long lushr(long l, int r, int site) { return l3(l, r, l >>> r, site); }
    public static long lneg(long value, int site) { long v = -value; line(site + " " + value + " " + v); return v; }

    private static float f3(float l, float r, float v, int site) { line(site + " " + f(l) + " " + f(r) + " " + f(v)); return v; }
    public static float fadd(float l, float r, int site) { return f3(l, r, l + r, site); }
    public static float fsub(float l, float r, int site) { return f3(l, r, l - r, site); }
    public static float fmul(float l, float r, int site) { return f3(l, r, l * r, site); }
    public static float fdiv(float l, float r, int site) { return f3(l, r, l / r, site); }
    public static float frem(float l, float r, int site) { return f3(l, r, l % r, site); }
    public static float fneg(float value, int site) { float v = -value; line(site + " " + f(value) + " " + f(v)); return v; }

    private static double d3(double l, double r, double v, int site) { line(site + " " + d(l) + " " + d(r) + " " + d(v)); return v; }
    public static double dadd(double l, double r, int site) { return d3(l, r, l + r, site); }
    public static double dsub(double l, double r, int site) { return d3(l, r, l - r, site); }
    public static double dmul(double l, double r, int site) { return d3(l, r, l * r, site); }
    public static double ddiv(double l, double r, int site) { return d3(l, r, l / r, site); }
    public static double drem(double l, double r, int site) { return d3(l, r, l % r, site); }
    public static double dneg(double value, int site) { double v = -value; line(site + " " + d(value) + " " + d(v)); return v; }

    // ---- casts: one helper per JVM stack-type pair; 'to' narrows an int result -------

    private static int narrow(int v, int to) {
        return switch (to) { case 'B' -> (byte) v; case 'S' -> (short) v; case 'C' -> (char) v; default -> v; };
    }
    public static int cvII(int v, int to, int site) { int r = narrow(v, to); line(site + " " + v + " " + r); return r; }
    public static long cvIJ(int v, int site) { long r = v; line(site + " " + v + " " + r); return r; }
    public static float cvIF(int v, int site) { float r = v; line(site + " " + v + " " + f(r)); return r; }
    public static double cvID(int v, int site) { double r = v; line(site + " " + v + " " + d(r)); return r; }
    public static int cvJI(long v, int to, int site) { int r = narrow((int) v, to); line(site + " " + v + " " + r); return r; }
    public static float cvJF(long v, int site) { float r = v; line(site + " " + v + " " + f(r)); return r; }
    public static double cvJD(long v, int site) { double r = v; line(site + " " + v + " " + d(r)); return r; }
    public static int cvFI(float v, int to, int site) { int r = narrow((int) v, to); line(site + " " + f(v) + " " + r); return r; }
    public static long cvFJ(float v, int site) { long r = (long) v; line(site + " " + f(v) + " " + r); return r; }
    public static double cvFD(float v, int site) { double r = v; line(site + " " + f(v) + " " + d(r)); return r; }
    public static int cvDI(double v, int to, int site) { int r = narrow((int) v, to); line(site + " " + d(v) + " " + r); return r; }
    public static long cvDJ(double v, int site) { long r = (long) v; line(site + " " + d(v) + " " + r); return r; }
    public static float cvDF(double v, int site) { float r = (float) v; line(site + " " + d(v) + " " + f(r)); return r; }

    // ---- long/float/double comparisons: return the cmp result for the original jump ----

    private static boolean jumps(int cmp, int jumpOpcode) {
        return switch (jumpOpcode) {
            case 153 -> cmp == 0; case 154 -> cmp != 0; case 155 -> cmp < 0;
            case 156 -> cmp >= 0; case 157 -> cmp > 0; case 158 -> cmp <= 0;
            default -> throw new IllegalArgumentException("opcode " + jumpOpcode);
        };
    }

    public static int jlcmp(long l, long r, int jump, int site) {
        int cmp = Long.compare(l, r);
        line(site + " " + (jumps(cmp, jump) ? 1 : 0) + " " + l + " " + r);
        return cmp;
    }

    /** fcmpl (149) gives -1 for NaN, fcmpg (150) gives 1; -0.0 equals 0.0. */
    public static int jfcmp(float l, float r, int cmpOpcode, int jump, int site) {
        int cmp = l > r ? 1 : l == r ? 0 : l < r ? -1 : cmpOpcode == 150 ? 1 : -1;
        line(site + " " + (jumps(cmp, jump) ? 1 : 0) + " " + f(l) + " " + f(r));
        return cmp;
    }

    /** dcmpl (151) gives -1 for NaN, dcmpg (152) gives 1; -0.0 equals 0.0. */
    public static int jdcmp(double l, double r, int cmpOpcode, int jump, int site) {
        int cmp = l > r ? 1 : l == r ? 0 : l < r ? -1 : cmpOpcode == 152 ? 1 : -1;
        line(site + " " + (jumps(cmp, jump) ? 1 : 0) + " " + d(l) + " " + d(r));
        return cmp;
    }

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
