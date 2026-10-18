public class Main {
    static int fact(int n) {
        if (n <= 1) {
            return 1;
        }
        int rest = fact(n - 1);
        return n * rest;
    }

    public static void main(String[] args) {
        int n = 3;
        System.out.println(fact(n));
    }
}
