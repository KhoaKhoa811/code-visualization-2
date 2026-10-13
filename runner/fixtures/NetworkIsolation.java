import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public class NetworkIsolation {
    public static void main(String[] args) throws Exception {
        var interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        if (interfaces.size() != 1 || !interfaces.getFirst().isLoopback()
                || !interfaces.getFirst().isUp() || !interfaces.getFirst().getName().equals("lo")) {
            throw new IllegalStateException("Expected only active loopback networking");
        }
        for (var address : Collections.list(interfaces.getFirst().getInetAddresses())) {
            if (!address.isLoopbackAddress()) {
                throw new IllegalStateException("Unexpected non-loopback address");
            }
        }
        var ipv4Routes = Files.readAllLines(Path.of("/proc/net/route"));
        for (String row : ipv4Routes.subList(1, ipv4Routes.size())) {
            if (!row.trim().isEmpty() && !row.trim().split("\\s+")[0].equals("lo")) {
                throw new IllegalStateException("Unexpected IPv4 route");
            }
        }
        for (String row : Files.readAllLines(Path.of("/proc/net/ipv6_route"))) {
            String[] fields = row.trim().split("\\s+");
            if (!fields[fields.length - 1].equals("lo")) {
                throw new IllegalStateException("Unexpected IPv6 route");
            }
        }
        // Positive control: a working socket must still reach this run's loopback.
        var loopback = InetAddress.getByName("127.0.0.1");
        try (var listener = new ServerSocket(0, 1, loopback); var client = new Socket()) {
            listener.setSoTimeout(500);
            client.connect(new InetSocketAddress(loopback, listener.getLocalPort()), 500);
            try (var accepted = listener.accept()) {
                accepted.setSoTimeout(500);
                client.getOutputStream().write(42);
                if (accepted.getInputStream().read() != 42) {
                    throw new IllegalStateException("Loopback transfer failed");
                }
            }
        }
        // Numeric addresses avoid DNS. Private addresses are representative, not discovered peers.
        for (String target : new String[] { "192.0.2.1", "172.17.0.1", "172.17.0.2", "2001:db8::1" }) {
            requireUnreachable(InetAddress.getByName(target), 9);
        }
        System.out.println("network-ok");
    }

    private static void requireUnreachable(InetAddress target, int port) throws Exception {
        try (var socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress(target, port), 500);
            } catch (SocketException failure) {
                // Refused connections and timeouts are not accepted as isolation evidence.
                if ("Network is unreachable".equals(failure.getMessage())) {
                    return;
                }
                throw failure;
            }
            throw new IllegalStateException("Non-loopback connection unexpectedly succeeded");
        }
    }
}
