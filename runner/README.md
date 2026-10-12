# Runner preflight (Part 5a)

This is a prerequisite check, not a Java runner. Read `preflight.ps1` first.
It supports Windows PowerShell 5.1 or later on Windows and changes no Docker/WSL
settings. It does not install tools, pull images, create containers, or run Java.

From the repository root:

```powershell
.\runner\preflight.ps1
.\runner\preflight.ps1 -Image 'your-existing-local-image:tag'
$LASTEXITCODE
```

Replace the example image with an image already installed locally. No image is
selected by default: the first command checks the environment and reports the
missing selection. Exit code `0` means all metadata checks passed; `1` means a
prerequisite is missing, failed, or unverified. Invalid parameters also fail.

The script checks:

1. WSL lists `docker-desktop` using version 2. Localized state labels are ignored.
2. The explicit `desktop-linux` context points to a local named pipe and its
   daemon reports Docker Desktop with Linux containers. Other contexts and
   `DOCKER_HOST` do not select the daemon for these checks.
3. The selected local image is Linux and declares Java 21 in `JAVA_VERSION`.
   Its image ID and available repository digests are printed for later pinning.

Each CLI call allows 10 seconds, plus up to 1 second for output completion. A
timed-out CLI process is stopped; the script never stops Docker or WSL services.
Missing commands, unavailable services, and access errors produce failed checks.
Use a normal local terminal if a restricted environment blocks daemon/WSL access.

An image without `JAVA_VERSION` metadata is unverified even if it actually has
Java 21. Metadata can be wrong and does not establish a working JDK/compiler,
image trust, architecture compatibility, or digest pinning. Host Java/Maven are
not prerequisites for this check; submitted Java will use the isolated runner.

Container restrictions, execution, timeouts, capture, and cleanup still need the
[prototype gates](../specs/execution-isolation.md#consistency-review-and-prototype-gates--part-4u).
A successful preflight is not permission to execute submitted Java or evidence
that those gates passed. Image selection/pinning and runtime probes are later tasks.

Command references: [Docker info](https://docs.docker.com/reference/cli/docker/system/info/),
[image inspect](https://docs.docker.com/reference/cli/docker/image/inspect/),
and [WSL list/verbose](https://learn.microsoft.com/windows/wsl/reference).

Verified on 2026-10-11 with Windows PowerShell 5.1.26100.9444: syntax, eight
simulated prerequisite cases, missing/nonzero CLI failures, and timeout at 10.03
seconds. The host reports `docker-desktop` as WSL2, but its Linux daemon pipe is
unavailable. The real preflight exits `1`; live image inspection and a complete
successful environment check remain unverified. No containers were launched.
