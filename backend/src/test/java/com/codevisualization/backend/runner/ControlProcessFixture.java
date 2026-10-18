package com.codevisualization.backend.runner;

import java.nio.file.Files;
import java.nio.file.Path;

/** Local trusted subprocess for control transport tests, never submitted Java. */
public class ControlProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "echo" -> System.out.print(args[1]);
            case "flood" -> System.out.write(new byte[Integer.parseInt(args[1])]);
            case "fail" -> System.exit(7);
            case "both" -> {
                System.out.write(new byte[]{(byte) 0xe2, (byte) 0x82});
                System.err.print("error");
            }
            case "stdin" -> System.out.print(System.in.read());
            case "wait" -> {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(30_000);
            }
            default -> throw new IllegalArgumentException();
        }
    }
}
