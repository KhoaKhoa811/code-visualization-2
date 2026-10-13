import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ParentChildTimeout {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            System.out.println("child-started");
            while (true) {
                Thread.sleep(1000);
            }
        }

        Process child = new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-Xmx128m", "-Djava.io.tmpdir=/tmp", "-cp", "/work",
            "ParentChildTimeout", "child"
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        BufferedReader output = new BufferedReader(
            new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8)
        );
        if (!"child-started".equals(output.readLine()) || !child.isAlive()) {
            throw new IllegalStateException("Child did not become ready");
        }
        System.out.println("parent-started");
        System.out.println("child-started");
        while (true) {
            if (!child.isAlive()) {
                throw new IllegalStateException("Child exited before timeout");
            }
            Thread.sleep(100);
        }
    }
}
