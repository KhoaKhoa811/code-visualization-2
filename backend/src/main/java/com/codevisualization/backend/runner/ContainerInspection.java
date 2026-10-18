package com.codevisualization.backend.runner;

import java.io.IOException;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ContainerInspection {
    private final JsonNode root;

    ContainerInspection(String json, String id, ContainerProfile.Identity identity) throws IOException {
        try {
            JsonNode array = JsonMapper.shared().readTree(json);
            require(array.isArray() && array.size() == 1);
            root = array.get(0);
            require(id.equals(root.path("Id").asString())
                    && ("/" + identity.name()).equals(root.path("Name").asString())
                    && identity.owner().toString().equals(root.path("Config").path("Labels")
                            .path(ContainerProfile.LABEL).asString()));
            require(root.path("State").path("Running").isBoolean());
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid container inspection", invalid);
        }
    }

    boolean running() { return root.path("State").path("Running").asBoolean(); }

    void verifyProfile() throws IOException {
        JsonNode config = root.path("Config"), host = root.path("HostConfig");
        require(ContainerProfile.IMAGE.equals(config.path("Image").asString()));
        require("10001:10001".equals(config.path("User").asString()));
        require("/work".equals(config.path("WorkingDir").asString()));
        require(config.path("Entrypoint").equals(JsonMapper.shared().valueToTree(java.util.List.of("/usr/bin/env"))));
        require(config.path("Cmd").equals(JsonMapper.shared().valueToTree(java.util.List.of(
                "-i", "PATH=/opt/java/openjdk/bin:/usr/bin:/bin", "HOME=/tmp", "/bin/sleep", "infinity"))));
        require(host.path("ReadonlyRootfs").asBoolean(false) && !host.path("Privileged").asBoolean(true));
        require("none".equals(host.path("NetworkMode").asString()) && "private".equals(host.path("IpcMode").asString()));
        require("".equals(host.path("PidMode").asString()));
        require(host.path("Memory").asLong() == 536870912 && host.path("MemorySwap").asLong() == 536870912);
        require(host.path("PidsLimit").asLong() == 128 && host.path("ShmSize").asLong() == 16777216);
        require("no".equals(host.path("RestartPolicy").path("Name").asString()));
        require("none".equals(host.path("LogConfig").path("Type").asString()));
        require(host.path("CapDrop").equals(JsonMapper.shared().valueToTree(java.util.List.of("ALL"))));
        require(host.path("SecurityOpt").equals(JsonMapper.shared().valueToTree(java.util.List.of("no-new-privileges=true"))));
        for (String field : java.util.List.of("Binds", "Devices", "DeviceRequests", "CapAdd", "GroupAdd", "PortBindings", "VolumesFrom"))
            require(host.has(field) && (host.get(field).isNull() || host.get(field).isEmpty()));
        require(config.path("Volumes").isMissingNode() || config.path("Volumes").isNull() || config.path("Volumes").isEmpty());
        JsonNode tmpfs = host.path("Tmpfs");
        require(tmpfs.size() == 3 && ContainerProfile.WORK.equals(tmpfs.path("/work").asString())
                && ContainerProfile.TEMP.equals(tmpfs.path("/tmp").asString())
                && ContainerProfile.TEMP.equals(tmpfs.path("/dev/shm").asString()));
        require(root.path("Mounts").isArray());
        for (JsonNode mount : root.path("Mounts"))
            require("tmpfs".equals(mount.path("Type").asString())
                    && Set.of("/work", "/tmp", "/dev/shm").contains(mount.path("Destination").asString()));
        require(host.path("Ulimits").size() == 1);
        JsonNode core = host.path("Ulimits").path(0);
        require("core".equals(core.path("Name").asString()) && core.path("Soft").asLong(-1) == 0
                && core.path("Hard").asLong(-1) == 0);
        require(config.path("Healthcheck").path("Test").equals(JsonMapper.shared().valueToTree(java.util.List.of("NONE"))));
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("Container ownership or restricted profile mismatch");
    }
}
