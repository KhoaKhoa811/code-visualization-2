// Naive source instrumentation of ac07: records the write by re-reading the
// target expression. This re-evaluates i++ and changes program behavior.
public class Main {
    static int calls;

    static int calculate() {
        calls++;
        return 40 + calls;
    }

    static void trace(String what, Object... values) { System.err.println(what + " " + java.util.Arrays.toString(values)); }

    public static void main(String[] args) {
        int[] values = new int[3];
        int i = 0;
        values[i++] = calculate();
        trace("write", i++, values[i - 1]);
        values[i++] += calculate();
        trace("write", i++, values[i - 1]);
        boolean shortCircuit = i > 5 && calculate() > 0;
        trace("compare", i > 5);
        System.out.println(java.util.Arrays.toString(values) + " i=" + i + " calls=" + calls + " " + shortCircuit);
    }
}
