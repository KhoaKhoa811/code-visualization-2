import java.util.Arrays;

public class ContainerMemoryPressure {
    private static byte[][] retained;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("worker")) {
            retained = new byte[80][];
            for (int index = 0; index < retained.length; index++) {
                retained[index] = new byte[1024 * 1024];
                Arrays.fill(retained[index], (byte) 1);
            }
        } else if (args.length == 0) {
            System.out.println("pressure-started");
            for (int index = 0; index < 6; index++) {
                new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
                    "-Xmx128m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
                    "-Djava.io.tmpdir=/tmp", "-cp", "/work",
                    "ContainerMemoryPressure", "worker").inheritIO().start();
            }
        } else {
            throw new IllegalArgumentException("Expected no arguments or worker");
        }
        while (true) {
            Thread.sleep(1000);
        }
    }
}
