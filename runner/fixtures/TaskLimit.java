import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class TaskLimit {
    private static long count(String file) throws IOException {
        return Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/" + file)).trim());
    }

    private static long denials() throws IOException {
        String text = Files.readString(Path.of("/sys/fs/cgroup/pids.events")).trim();
        if (!text.matches("max [0-9]+")) throw new AssertionError("Invalid PID counter");
        return Long.parseLong(text.substring(4));
    }

    public static void main(String[] args) throws Exception {
        if (count("pids.max") != 128 || denials() != 0) {
            throw new AssertionError("Wrong cap or preexisting denial");
        }
        // Leave this child alive for the supervisor's whole-container stop check.
        Process child = new ProcessBuilder("/bin/sleep", "infinity")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        long baseline = denials();
        boolean threadDenied = false;
        for (int index = 0; index < 128; index++) {
            Thread worker = new Thread(() -> {
                try { Thread.sleep(Long.MAX_VALUE); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            worker.setDaemon(true);
            try { worker.start(); }
            catch (OutOfMemoryError exhausted) { threadDenied = true; break; }
        }
        long afterThread = denials();
        if (!threadDenied || count("pids.current") != 128 || afterThread <= baseline) {
            throw new AssertionError("Thread denial lacks saturated PID evidence");
        }
        boolean processDenied = false;
        try {
            Process unexpected = new ProcessBuilder("/bin/sleep", "infinity").start();
            unexpected.destroyForcibly();
        } catch (IOException exhausted) {
            processDenied = true;
        }
        if (!processDenied || count("pids.current") != 128 || denials() <= afterThread || !child.isAlive()) {
            throw new AssertionError("Process denial lacks saturated PID evidence");
        }
        System.out.println("tasks-ok");
        // JVM exit releases daemon threads; child and PID 1 remain for forced stop.
    }
}
