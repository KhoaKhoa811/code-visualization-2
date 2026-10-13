public class TimeoutLoop {
    public static void main(String[] args) {
        System.out.println("loop-started");
        while (true) {
            Thread.onSpinWait();
        }
    }
}
