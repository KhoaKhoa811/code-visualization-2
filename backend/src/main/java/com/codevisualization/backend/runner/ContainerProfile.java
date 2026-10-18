package com.codevisualization.backend.runner;

import java.util.List;
import java.util.UUID;

/** Fixed trusted idle profile; no caller-supplied commands or container options. */
public final class ContainerProfile {
    public static final String IMAGE = "eclipse-temurin@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87";
    static final String LABEL = "codeviz.owner";
    static final String WORK = "rw,nosuid,nodev,noexec,size=67108864,nr_inodes=4096,uid=10001,gid=10001,mode=700";
    static final String TEMP = "rw,nosuid,nodev,noexec,size=16777216,nr_inodes=1024,uid=10001,gid=10001,mode=700";

    private ContainerProfile() { }

    public record Identity(UUID owner) {
        public Identity { java.util.Objects.requireNonNull(owner); }
        public static Identity fresh() { return new Identity(UUID.randomUUID()); }
        public String name() { return "codeviz-run-" + owner; }
    }

    static List<String> createArguments(Identity identity) {
        return List.of("create", "--pull=never", "--name", identity.name(),
                "--label", LABEL + "=" + identity.owner(), "--platform", "linux/amd64",
                "--user", "10001:10001", "--cap-drop=ALL", "--security-opt=no-new-privileges=true",
                "--read-only", "--network=none", "--ipc=private", "--memory=536870912",
                "--memory-swap=536870912", "--pids-limit=128", "--restart=no", "--log-driver=none",
                "--ulimit", "core=0:0", "--no-healthcheck", "--shm-size=16777216",
                "--tmpfs", "/work:" + WORK, "--tmpfs", "/tmp:" + TEMP,
                "--tmpfs", "/dev/shm:" + TEMP, "--workdir", "/work", "--entrypoint", "/usr/bin/env",
                IMAGE, "-i", "PATH=/opt/java/openjdk/bin:/usr/bin:/bin", "HOME=/tmp",
                "/bin/sleep", "infinity");
    }
}
