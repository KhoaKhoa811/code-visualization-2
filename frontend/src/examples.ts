// Starter programs are conveniences only; tracing never depends on their text.
export const EXAMPLES: Record<string, string> = {
  'Bubble sort': `public class Main {
    public static void main(String[] args) {
        int[] a = {5, 2, 4, 1, 3};
        for (int i = 0; i < a.length - 1; i++) {
            for (int j = 0; j < a.length - 1 - i; j++) {
                if (a[j] > a[j + 1]) {
                    int t = a[j];
                    a[j] = a[j + 1];
                    a[j + 1] = t;
                }
            }
        }
        for (int k = 0; k < a.length; k++) System.out.println(a[k]);
    }
}
`,
  'Running total': `public class Main {
    public static void main(String[] args) {
        int[] values = new int[4];
        int total = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = i * i;
            total = total + values[i];
        }
        boolean big = total > 10 && values[3] != 0;
        System.out.println(total);
        System.out.println(big);
    }
}
`,
};
