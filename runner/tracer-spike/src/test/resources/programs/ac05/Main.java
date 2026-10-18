public class Main {
    public static void main(String[] args) {
        int[] a = {1, 2};
        int[] b = a;
        b[0] = 9;
        System.out.println(a[0] + " " + b[0]);
    }
}
