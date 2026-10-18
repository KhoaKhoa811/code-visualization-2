package codeviz.tracer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compile-phase command run inside the isolated runner:
 * {@code java -cp tracer-runtime.jar codeviz.tracer.Prepare <Main.java> <outDir>}.
 * Writes the classes to run (instrumented, or original for output-only) and
 * {@code plan.json}. Exit 0 whenever a plan was written, including compile
 * errors, which the plan reports. Exit 2 means the tool itself failed.
 * Never executes the submitted program.
 */
public final class Prepare {
    private Prepare() { }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: Prepare <Main.java> <outDir>");
            System.exit(2);
        }
        try {
            byte[] bytes = Files.readAllBytes(Path.of(args[0]));
            String source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            Tracer.Prepared prepared = Tracer.prepare(source);
            Path out = Path.of(args[1]);
            Files.createDirectories(out);
            for (var entry : prepared.classes().entrySet()) {
                if (!entry.getKey().matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IOException("unexpected class name");
                Files.write(out.resolve(entry.getKey() + ".class"), entry.getValue());
            }
            Files.writeString(out.resolve("plan.json"), Tracer.plan(prepared), StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            System.err.println("Source is not well-formed UTF-8");
            System.exit(2);
        } catch (IOException | RuntimeException e) {
            System.err.println("Prepare failed: " + e.getClass().getSimpleName());
            System.exit(2);
        }
    }
}
