package com.codevisualization.backend.runner;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

/** Closed trusted probes. No caller-supplied source or command fragments. */
public enum FixedFixture {
    NORMAL("Main", ""), COMPILE_ERROR("CompileError", ""),
    COMPILE_TIMEOUT("Main", ""), RUNTIME_TIMEOUT("TimeoutLoop", ""),
    OUTPUT_BELOW("OutputFlood", "below"), OUTPUT_EXACT("OutputFlood", "exact"),
    OUTPUT_OVER("OutputFlood", "over"), OUTPUT_STDERR("OutputFlood", "stderr"),
    OUTPUT_COMBINED("OutputFlood", "combined"), OUTPUT_MULTIBYTE("OutputFlood", "multibyte"),
    OUTPUT_PHASES("OutputFlood", "phases"), COMPILE_OUTPUT_OVER("Main", "");

    private final String className;
    private final String argument;

    FixedFixture(String className, String argument) {
        this.className = className;
        this.argument = argument;
    }

    List<String> stagingCommand() throws IOException {
        try (var source = FixedFixture.class.getResourceAsStream("/runner-fixtures/" + className + ".java")) {
            if (source == null) throw new IOException("Bundled fixture missing");
            byte[] bytes = source.readNBytes(16_385);
            if (bytes.length > 16_384) throw new IOException("Fixed fixture exceeds staging bound");
            String encoded = Base64.getEncoder().encodeToString(bytes);
            return List.of("/bin/sh", "-ec", "printf %s " + encoded + " | base64 -d > /work/" + className + ".java");
        }
    }

    List<String> compileCommand() {
        String compiler = "exec javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -encoding UTF-8 -proc:none --release 21 -d /work ";
        if (this == COMPILE_TIMEOUT)
            return List.of("/bin/sh", "-ec", "mkfifo /work/compiler-args; " + compiler + "@/work/compiler-args");
        String prefix = this == OUTPUT_PHASES ? "printf C >&2; "
                : this == COMPILE_OUTPUT_OVER ? "head -c 1048577 /dev/zero; " : "";
        return List.of("/bin/sh", "-ec", prefix + compiler + "/work/" + className + ".java");
    }

    List<String> runtimeCommand() {
        var args = new java.util.ArrayList<>(List.of("java", "-Xmx128m", "-Djava.io.tmpdir=/tmp", "-cp", "/work", className));
        if (!argument.isEmpty()) args.add(argument);
        return List.copyOf(args);
    }
}
