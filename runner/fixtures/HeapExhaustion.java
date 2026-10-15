import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;

public class HeapExhaustion {
    private static byte[][] retained;

    public static void main(String[] args) {
        var diagnostic = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        long configuredHeap = Long.parseLong(diagnostic.getVMOption("MaxHeapSize").getValue());
        long usableHeap = Runtime.getRuntime().maxMemory();
        if (configuredHeap != 134217728L || usableHeap <= 0 || usableHeap > configuredHeap) {
            throw new IllegalStateException("Expected a 128 MiB heap");
        }
        System.out.println("heap-started");
        if (args.length == 1 && args[0].equals("caught")) {
            try {
                exhaust();
            } catch (OutOfMemoryError failure) {
                retained = null;
                if (!"Java heap space".equals(failure.getMessage())) {
                    throw failure;
                }
                System.out.println("heap-caught");
            }
            System.out.println("heap-continued");
        } else if (args.length == 1 && args[0].equals("uncaught")) {
            exhaust();
        } else {
            throw new IllegalArgumentException("Expected caught or uncaught");
        }
    }

    private static void exhaust() {
        retained = new byte[256][];
        for (int index = 0; index < retained.length; index++) {
            retained[index] = new byte[1024 * 1024];
        }
        throw new IllegalStateException("Expected heap exhaustion");
    }
}
