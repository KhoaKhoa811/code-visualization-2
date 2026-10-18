public class Main {
    public static void main(String[] args) {
        int[] a = {1, 2, 3};
        for (int i = 0; i < a.length; i++) {
            a[i] = a[i] * 2;
        }
        System.out.println(java.util.Arrays.toString(a));
    }
}
