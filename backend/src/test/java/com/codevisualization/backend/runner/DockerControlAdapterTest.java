package com.codevisualization.backend.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class DockerControlAdapterTest {
    private static final String ID = "a".repeat(64);

    private static class FakeDocker implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        DockerControlAdapter adapter;
        boolean running, foreign, unsafe, failCreate, ignoreKill, ignoreRemove;
        public String execute(List<String> args, java.time.Duration remaining) throws IOException {
            assertTrue(remaining.toMillis() <= 10_000);
            calls.add(List.copyOf(args));
            return switch (args.get(3)) {
                case "create" -> {
                    if (failCreate) throw new IOException("uncertain create");
                    yield ID;
                }
                case "inspect" -> inspection();
                case "start" -> { running = true; yield ID; }
                case "kill" -> { if (!ignoreKill) running = false; yield ID; }
                case "rm" -> ID;
                case "ps" -> ignoreRemove ? ID : "";
                default -> throw new AssertionError(args);
            };
        }
        String inspection() {
            ObjectNode root = JsonMapper.shared().createObjectNode();
            root.put("Id", ID).put("Name", "/" + adapter.identity().name());
            root.putObject("State").put("Running", running);
            ObjectNode config = root.putObject("Config");
            config.put("Image", ContainerProfile.IMAGE).put("User", "10001:10001").put("WorkingDir", "/work");
            config.putObject("Labels").put(ContainerProfile.LABEL, foreign ? "foreign" : adapter.identity().owner().toString());
            config.putArray("Entrypoint").add("/usr/bin/env");
            config.putArray("Cmd").add("-i").add("PATH=/opt/java/openjdk/bin:/usr/bin:/bin")
                    .add("HOME=/tmp").add("/bin/sleep").add("infinity");
            config.putObject("Healthcheck").putArray("Test").add("NONE");
            ObjectNode host = root.putObject("HostConfig");
            host.put("ReadonlyRootfs", !unsafe).put("Privileged", false).put("NetworkMode", "none")
                    .put("IpcMode", "private").put("PidMode", "").put("Memory", 536870912)
                    .put("MemorySwap", 536870912).put("PidsLimit", 128).put("ShmSize", 16777216);
            host.putObject("RestartPolicy").put("Name", "no");
            host.putObject("LogConfig").put("Type", "none");
            host.putArray("CapDrop").add("ALL");
            host.putArray("SecurityOpt").add("no-new-privileges=true");
            for (String field : List.of("Binds", "Devices", "DeviceRequests", "CapAdd", "GroupAdd", "PortBindings", "VolumesFrom")) host.putNull(field);
            host.putObject("Tmpfs").put("/work", ContainerProfile.WORK).put("/tmp", ContainerProfile.TEMP).put("/dev/shm", ContainerProfile.TEMP);
            host.putArray("Ulimits").addObject().put("Name", "core").put("Soft", 0).put("Hard", 0);
            root.putArray("Mounts");
            return "[" + root + "]";
        }
    }

    private FakeDocker fake() {
        var fake = new FakeDocker();
        fake.adapter = new DockerControlAdapter(Path.of("docker.exe").toAbsolutePath(), fake);
        return fake;
    }

    @Test void ownsExactIdAndVerifiesNormalLifecycle() throws Exception {
        var fake = fake();
        fake.adapter.create(); fake.adapter.startIdle(); fake.adapter.stop(); fake.adapter.remove();
        assertEquals(ID, fake.adapter.containerId());
        assertFalse(fake.adapter.quarantined());
        for (var call : fake.calls) {
            assertEquals(List.of("--context", "desktop-linux"), call.subList(1, 3));
            if (List.of("start", "kill", "rm").contains(call.get(3))) assertEquals(ID, call.get(4));
        }
    }

    @Test void foreignOwnershipBlocksMutation() throws Exception {
        var fake = fake(); fake.adapter.create(); fake.foreign = true;
        assertThrows(IOException.class, fake.adapter::startIdle);
        assertTrue(fake.adapter.quarantined());
        assertFalse(fake.calls.stream().anyMatch(call -> call.get(3).equals("start")));
    }

    @Test void unsafeProfileAndUncertainCreateRetainIdentityWithoutRetry() {
        var unsafe = fake(); unsafe.unsafe = true;
        assertThrows(IOException.class, unsafe.adapter::create);
        assertEquals(ID, unsafe.adapter.containerId());
        var uncertain = fake(); uncertain.failCreate = true;
        var owner = uncertain.adapter.identity();
        assertThrows(IOException.class, uncertain.adapter::create);
        assertThrows(IOException.class, uncertain.adapter::create);
        assertEquals(owner, uncertain.adapter.identity());
        assertEquals(1, uncertain.calls.size());
    }

    @Test void acknowledgementsDoNotProveStopOrRemoval() throws Exception {
        var stop = fake(); stop.adapter.create(); stop.adapter.startIdle(); stop.ignoreKill = true;
        assertThrows(IOException.class, stop.adapter::stop);
        assertThrows(IOException.class, stop.adapter::remove);
        assertFalse(stop.calls.stream().anyMatch(call -> call.get(3).equals("rm")));
        var remove = fake(); remove.adapter.create(); remove.ignoreRemove = true;
        assertThrows(IOException.class, remove.adapter::remove);
        assertTrue(remove.adapter.quarantined());
    }

    @Test void stopBeforeStartFencesLaterLaunch() throws Exception {
        var fake = fake(); fake.adapter.create(); fake.adapter.stop();
        assertThrows(IOException.class, fake.adapter::startIdle);
        assertFalse(fake.calls.stream().anyMatch(call -> call.get(3).equals("start")));
    }
}
