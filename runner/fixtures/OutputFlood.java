import java.io.OutputStream;
import java.util.Arrays;

public class OutputFlood {
    private static void emit(OutputStream stream, int count, boolean multibyte) throws Exception {
        byte[] block = new byte[3072];
        if (multibyte) {
            for (int i = 0; i < block.length; i += 3) {
                block[i] = (byte) 0xE2; block[i + 1] = (byte) 0x82; block[i + 2] = (byte) 0xAC;
            }
        } else {
            Arrays.fill(block, (byte) (stream == System.out ? 'A' : 'B'));
        }
        while (count > 0) {
            int length = Math.min(count, block.length);
            stream.write(block, 0, length);
            count -= length;
        }
        stream.flush();
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("combined")) {
            Thread error = new Thread(() -> {
                try { emit(System.err, 600000, false); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            error.start();
            emit(System.out, 600000, false);
            error.join();
        } else if (mode.equals("flood")) {
            while (true) emit(System.out, 3072, false);
        } else {
            int count = mode.equals("below") ? 1048575 : mode.equals("exact") || mode.equals("phases") ? 1048576 : 1048577;
            emit(mode.equals("stderr") ? System.err : System.out, count, mode.equals("multibyte"));
        }
    }
}
