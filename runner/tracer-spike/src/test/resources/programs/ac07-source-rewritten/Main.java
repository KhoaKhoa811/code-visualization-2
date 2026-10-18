// Correct source instrumentation of ac07, written by hand to show what a source
// rewriter must generate: every subexpression hoisted into a temporary in Java's
// evaluation order, compound assignment decomposed, short-circuit preserved with
// control flow. A rewriter must do this for every expression context, including
// loop conditions and method arguments where statements cannot be inserted.
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
        {   // values[i++] = calculate();
            int[] t0 = values; int t1 = i++; trace("local", "i", i);
            int t2 = calculate(); t0[t1] = t2; trace("write", t1, t2);
        }
        {   // values[i++] += calculate();
            int[] t0 = values; int t1 = i++; trace("local", "i", i);
            int t2 = t0[t1]; trace("read", t1, t2);
            int t3 = calculate(); int t4 = t2 + t3; t0[t1] = t4; trace("write", t1, t4);
        }
        boolean shortCircuit;
        {   // i > 5 && calculate() > 0
            boolean c0 = i > 5; trace("compare", i, 5, c0);
            if (c0) { int t0 = calculate(); boolean c1 = t0 > 0; trace("compare", t0, 0, c1); shortCircuit = c1; }
            else shortCircuit = false;
        }
        System.out.println(java.util.Arrays.toString(values) + " i=" + i + " calls=" + calls + " " + shortCircuit);
    }
}
