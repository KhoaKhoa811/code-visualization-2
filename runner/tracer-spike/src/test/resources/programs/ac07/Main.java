public class Main {
    static int calls;

    static int calculate() {
        calls++;
        return 40 + calls;
    }

    public static void main(String[] args) {
        int[] values = new int[3];
        int i = 0;
        values[i++] = calculate();
        values[i++] += calculate();
        boolean shortCircuit = i > 5 && calculate() > 0;
        System.out.println(java.util.Arrays.toString(values) + " i=" + i + " calls=" + calls + " " + shortCircuit);
    }
}
