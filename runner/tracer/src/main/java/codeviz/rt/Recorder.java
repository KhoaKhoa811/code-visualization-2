package codeviz.rt;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
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
 * {@code u<site>} an uncaptured statement starts (Part 8a); {@code A<id> <class> <len> <values...>} the
 * contents of an array seen for the first time, or re-read after a statement that may write arrays
 * ({@code <class>} is the JVM class name such as {@code [I} or {@code [[D}; Part 8c);
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
    // Part 8d: a call site announced by traced code (consumed by the next entry), invocations
    // entered from uncaptured code (they record nothing), and active traced frames including main.
    private static int pendingCall, silent, depth;
    static final int MAX_DEPTH = 64;

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
            if (silent > 0) return;
            if (dirty) { dirty = false; resync(); }
            drain(); // first sights referenced by this record come first
            write(record);
        }
    }

    // ---- identities (Parts 8a, 8c, 8e) ---------------------------------------------------
    // An object seen for the first time is registered at once (cycles end there) and queued; its
    // record is written before the next record that may reference it. Records of first sights may
    // reference each other in any order: the builder captures them when covered code uses them.

    private static final ArrayDeque<Object> INTRODUCED = new ArrayDeque<>();
    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();
    /** Captured identities per run (Part 4t budget); one more halts the run. */
    static final int MAX_IDENTITIES = 1024;

    /** Re-reads every known array and object after an uncaptured statement that may have changed them. */
    private static void resync() {
        Object[] byId = new Object[IDS.size() + 1];
        for (var entry : IDS.entrySet()) byId[entry.getValue()] = entry.getKey();
        for (int id = 1; id < byId.length; id++) {
            Object o = byId[id];
            if (o == null || o instanceof String) continue;
            String body = contents(o); // may introduce objects it now references
            drain(); // their records come first: a re-read is applied at once
            write((o.getClass().isArray() ? "A" : "O") + id + " " + body);
        }
    }

    /** {@code <class> <len> <values...>} for an array, {@code <class> <n> (<field> <value>)...} for an object. */
    private static String contents(Object o) {
        StringBuilder b = new StringBuilder().append(o.getClass().getName());
        if (o.getClass().isArray()) values(b, o);
        else fields(b, o);
        return b.toString();
    }

    /** {@code <len> <values...>} with 8b value tokens and r/n references. */
    private static void values(StringBuilder b, Object array) {
        int n = java.lang.reflect.Array.getLength(array);
        b.append(' ').append(n);
        for (int i = 0; i < n; i++) b.append(' ').append(token(array, i));
    }

    private static String token(Object array, int i) {
        if (array instanceof int[] a) return Integer.toString(a[i]);
        if (array instanceof long[] a) return Long.toString(a[i]);
        if (array instanceof double[] a) return d(a[i]);
        if (array instanceof float[] a) return f(a[i]);
        if (array instanceof char[] a) return Integer.toString(a[i]);
        if (array instanceof byte[] a) return Integer.toString(a[i]);
        if (array instanceof short[] a) return Integer.toString(a[i]);
        if (array instanceof boolean[] a) return a[i] ? "1" : "0";
        return id(((Object[]) array)[i]);
    }

    /** Instance fields of a traced class, read without running any user code. */
    private static void fields(StringBuilder b, Object o) {
        Field[] fields = FIELDS.computeIfAbsent(o.getClass(), c -> {
            List<Field> out = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                f.setAccessible(true);
                out.add(f);
            }
            return out.toArray(new Field[0]);
        });
        b.append(' ').append(fields.length);
        try {
            for (Field f : fields) {
                Class<?> t = f.getType();
                String v = t == int.class || t == short.class || t == byte.class ? Integer.toString(f.getInt(o))
                        : t == char.class ? Integer.toString(f.getChar(o))
                        : t == boolean.class ? (f.getBoolean(o) ? "1" : "0")
                        : t == long.class ? Long.toString(f.getLong(o))
                        : t == float.class ? f(f.getFloat(o))
                        : t == double.class ? d(f.getDouble(o))
                        : id(f.get(o));
                b.append(' ').append(f.getName()).append(' ').append(v);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Identity token; an object not seen before is registered and queued for its record. */
    private static String id(Object o) {
        if (o == null) return "n";
        Integer known = IDS.get(o);
        if (known != null) return "r" + known;
        if (!(o instanceof String) && !o.getClass().isArray() && o.getClass().getName().startsWith("java.")) return "?";
        int id = register(o);
        INTRODUCED.add(o);
        return "r" + id;
    }

    private static int register(Object o) {
        if (IDS.size() >= MAX_IDENTITIES) {
            try { OUT.write("T\n"); OUT.flush(); } catch (IOException ignored) { }
            System.out.flush();
            System.err.flush();
            Runtime.getRuntime().halt(LIMIT_EXIT);
        }
        int id = IDS.size() + 1;
        IDS.put(o, id);
        return id;
    }

    /** Writes the records of every queued first sight; writing one may queue more. */
    private static void drain() {
        while (!INTRODUCED.isEmpty()) {
            Object o = INTRODUCED.poll();
            int id = IDS.get(o);
            if (o instanceof String s) write("S" + id + " " + hex(s));
            else write((o.getClass().isArray() ? "A" : "O") + id + " " + contents(o));
        }
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

    /** Reference token for a record; first sights are recorded before it (after any pending re-read). */
    private static String ref(Object o) {
        if (o == null) return "n";
        if (silent > 0) return "?"; // never written; silent code registers nothing
        Integer known = IDS.get(o);
        if (known != null) return "r" + known;
        synchronized (LOCK) {
            if (dirty) { dirty = false; resync(); } // re-reads first: they must not include the new objects
            String token = id(o);
            drain();
            return token;
        }
    }

    private static void fail(int site, RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        line("x" + site + " " + e.getClass().getName() + " " + hex(message));
        try { OUT.flush(); } catch (IOException ignored) { }
    }

    // ---- frame ------------------------------------------------------------------

    public static void enter(String[] args) {
        if (depth > 0) { silent++; return; } // main called again from uncaptured code
        depth = 1;
        IDS.put(args, 1); // part of the initial state, not a first sight
        line("M r1 " + args.length);
    }

    // ---- uncaptured statements (Part 8a) -------------------------------------------

    public static void region(int site, boolean mayWriteArrays) {
        synchronized (LOCK) {
            if (silent > 0) return;
            line("u" + site);
            if (mayWriteArrays) dirty = true;
        }
    }

    /** Registers an array allocated by covered code; its ALLOCATE record carries the contents. */
    private static String fresh(Object o) {
        synchronized (LOCK) {
            if (silent > 0) return "?";
            if (dirty) { dirty = false; resync(); } // the re-read must not include the new array
        }
        int id = register(o);
        return "r" + id;
    }

    public static void exit() {
        synchronized (LOCK) { if (silent > 0) { silent--; return; } }
        line("R");
        try { OUT.flush(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // ---- traced helpers (Part 8d) ---------------------------------------------------

    /** A traced call site, just before its invokestatic. Ignored while silent. */
    public static void call(int site) {
        synchronized (LOCK) { if (silent == 0) pendingCall = site; }
    }

    /**
     * Entry of a traced helper. Without an announced call site (uncaptured code, a static
     * initializer, a library callback) the invocation is silent, and so is everything it calls.
     */
    public static void begin(int method) {
        synchronized (LOCK) {
            int site = pendingCall;
            pendingCall = 0;
            if (silent > 0 || site == 0) { silent++; return; }
            if (depth >= MAX_DEPTH) {
                try { OUT.write("D\n"); OUT.flush(); } catch (IOException ignored) { }
                System.out.flush();
                System.err.flush();
                Runtime.getRuntime().halt(LIMIT_EXIT);
            }
            depth++;
            line("c" + method + " " + site);
        }
    }

    /** Ends the current invocation; true if it was silent (nothing is recorded). */
    private static boolean leave() {
        if (silent > 0) { silent--; return true; }
        depth--;
        return false;
    }

    public static void ret(int site) { synchronized (LOCK) { if (!leave()) line(Integer.toString(site)); } }
    public static void reti(int v, int site) { synchronized (LOCK) { if (!leave()) line(site + " " + v); } }
    public static void retl(long v, int site) { synchronized (LOCK) { if (!leave()) line(site + " " + v); } }
    public static void retf(float v, int site) { synchronized (LOCK) { if (!leave()) line(site + " " + f(v)); } }
    public static void retd(double v, int site) { synchronized (LOCK) { if (!leave()) line(site + " " + d(v)); } }
    public static void reta(Object v, int site) { synchronized (LOCK) { if (!leave()) { String r = ref(v); line(site + " " + r); } } }

    /** An exception leaves a traced helper (the instrumenter's catch-all handler, which rethrows). */
    public static void unwind() { synchronized (LOCK) { leave(); } }

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

    public static int arraylength(Object a, int site) {
        int n;
        try { n = java.lang.reflect.Array.getLength(a); } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + n);
        return n;
    }

    // ---- Part 8c: arrays of every primitive type, String and arrays ------------------------

    /** newarray: {@code atype} is the JVM operand (T_BOOLEAN 4 .. T_LONG 11). Elements are defaults. */
    public static Object newarray(int n, int atype, int site) {
        Object a;
        try {
            a = switch (atype) {
                case 4 -> new boolean[n]; case 5 -> new char[n]; case 6 -> new float[n]; case 7 -> new double[n];
                case 8 -> new byte[n]; case 9 -> new short[n]; case 10 -> new int[n]; default -> new long[n];
            };
        } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + fresh(a) + " " + n);
        return a;
    }

    /** anewarray: an array of null references. */
    public static Object anewarray(int n, Class<?> component, int site) {
        Object a;
        try { a = java.lang.reflect.Array.newInstance(component, n); } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + fresh(a) + " " + n);
        return a;
    }

    /** multianewarray with 2 or 3 lengths of an array type; inner arrays are recorded as first sights. */
    public static Object multi(int d1, int d2, Class<?> type, int site) { return multi(new int[]{d1, d2}, type, site); }
    public static Object multi(int d1, int d2, int d3, Class<?> type, int site) { return multi(new int[]{d1, d2, d3}, type, site); }

    private static Object multi(int[] dims, Class<?> type, int site) {
        Class<?> component = type;
        for (int i = 0; i < dims.length; i++) component = component.getComponentType();
        Object a;
        try { a = java.lang.reflect.Array.newInstance(component, dims); } catch (RuntimeException e) { fail(site, e); throw e; }
        StringBuilder b = new StringBuilder().append(site).append(' ').append(fresh(a));
        values(b, a);
        line(b.toString());
        return a;
    }

    /** Called once an initializer has stored its last element (or at creation when empty). */
    public static void literal(Object a, int site) {
        StringBuilder b = new StringBuilder().append(site).append(' ').append(fresh(a));
        values(b, a);
        line(b.toString());
    }

    public static int baload(byte[] a, int i, int site) {
        int v;
        try { v = a[i]; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + i + " " + v);
        return v;
    }
    public static int zaload(boolean[] a, int i, int site) {
        int v;
        try { v = a[i] ? 1 : 0; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + i + " " + v);
        return v;
    }
    public static int caload(char[] a, int i, int site) {
        int v;
        try { v = a[i]; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + i + " " + v);
        return v;
    }
    public static int saload(short[] a, int i, int site) {
        int v;
        try { v = a[i]; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + i + " " + v);
        return v;
    }
    public static long laload(long[] a, int i, int site) {
        long v;
        try { v = a[i]; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + i + " " + v);
        return v;
    }
    public static float faload(float[] a, int i, int site) {
        float v;
        try { v = a[i]; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + i + " " + f(v));
        return v;
    }
    public static double daload(double[] a, int i, int site) {
        double v;
        try { v = a[i]; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + ref(a) + " " + i + " " + d(v));
        return v;
    }
    public static Object aaload(Object[] a, int i, int site) {
        Object v;
        try { v = a[i]; } catch (RuntimeException e) { fail(site, e); throw e; }
        String r = ref(a), value = ref(v);
        line(site + " " + r + " " + i + " " + value);
        return v;
    }

    // Stores narrow exactly as bastore/castore/sastore do; the recorded value is the stored one.
    public static void bastore(byte[] a, int i, int v, int site) {
        String r = ref(a);
        try { a[i] = (byte) v; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + r + " " + i + " " + (byte) v);
    }
    public static void zastore(boolean[] a, int i, int v, int site) {
        String r = ref(a);
        try { a[i] = (v & 1) != 0; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + r + " " + i + " " + (v & 1));
    }
    public static void castore(char[] a, int i, int v, int site) {
        String r = ref(a);
        try { a[i] = (char) v; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + r + " " + i + " " + (int) (char) v);
    }
    public static void sastore(short[] a, int i, int v, int site) {
        String r = ref(a);
        try { a[i] = (short) v; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + r + " " + i + " " + (short) v);
    }
    public static void lastore(long[] a, int i, long v, int site) {
        String r = ref(a);
        try { a[i] = v; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + r + " " + i + " " + v);
    }
    public static void fastore(float[] a, int i, float v, int site) {
        String r = ref(a);
        try { a[i] = v; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + r + " " + i + " " + f(v));
    }
    public static void dastore(double[] a, int i, double v, int site) {
        String r = ref(a);
        try { a[i] = v; } catch (RuntimeException e) { fail(site, e); throw e; }
        line(site + " " + r + " " + i + " " + d(v));
    }
    public static void aastore(Object[] a, int i, Object v, int site) {
        String r = ref(a); // before the store: a first-sight snapshot must show the previous contents
        try { a[i] = v; } catch (RuntimeException e) { fail(site, e); throw e; }
        String value = ref(v);
        line(site + " " + r + " " + i + " " + value);
    }

    // ---- fields of traced objects (Part 8e) --------------------------------------------
    // The original getfield/putfield runs in user code; these record it. A null receiver fails
    // here first, so the failure is recorded (its message names the field, not a helpful NPE).

    public static void nn(Object o, String message, int site) {
        if (o != null) return;
        NullPointerException e = new NullPointerException(message);
        fail(site, e);
        throw e;
    }

    public static int rgI(Object o, int v, int site) { String r = ref(o); line(site + " " + r + " " + v); return v; }
    public static long rgJ(Object o, long v, int site) { String r = ref(o); line(site + " " + r + " " + v); return v; }
    public static float rgF(Object o, float v, int site) { String r = ref(o); line(site + " " + r + " " + f(v)); return v; }
    public static double rgD(Object o, double v, int site) { String r = ref(o); line(site + " " + r + " " + d(v)); return v; }
    public static Object rgA(Object o, Object v, int site) { String r = ref(o), t = ref(v); line(site + " " + r + " " + t); return v; }

    public static void wpI(Object o, int v, int site) { String r = ref(o); line(site + " " + r + " " + v); }
    public static void wpJ(Object o, long v, int site) { String r = ref(o); line(site + " " + r + " " + v); }
    public static void wpF(Object o, float v, int site) { String r = ref(o); line(site + " " + r + " " + f(v)); }
    public static void wpD(Object o, double v, int site) { String r = ref(o); line(site + " " + r + " " + d(v)); }
    public static void wpA(Object o, Object v, int site) { String r = ref(o), t = ref(v); line(site + " " + r + " " + t); }

    /** A switch selector, recorded before the original tableswitch/lookupswitch uses it. */
    public static int sw(int value, int site) {
        line(site + " " + value);
        return value;
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
