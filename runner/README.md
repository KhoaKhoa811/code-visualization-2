# Runner setup (Parts 5a–5b)

Read `preflight.ps1` first: it is a prerequisite check, not a Java runner.
The script supports Windows PowerShell 5.1 or later on Windows and changes no
Docker/WSL settings. It does not install tools, pull images, create containers,
or run Java. The separate setup commands below start Docker and obtain the image.

## Pinned image (Part 5b)

[image.json](image.json) records the selected official Eclipse Temurin Java 21
JDK image and `linux/amd64` platform. The full JDK is needed for future compilation;
the explicit Ubuntu 24.04 (Noble) variant fixes the base-OS choice. Adoptium lists
this variant in its [official image documentation](https://adoptium.net/installation/containers/).
The tag records selection provenance only. Use the digest for repeatable setup;
do not silently refresh the tag. Other architectures are not validated by this pin.

From the repository root, these commands start Docker and download the pinned
image if necessary. They do not create or execute a container:

```powershell
docker desktop start --timeout 60
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop did not start.' }
$runnerImage = Get-Content .\runner\image.json -Raw | ConvertFrom-Json
docker --context desktop-linux pull --platform $runnerImage.platform $runnerImage.image
if ($LASTEXITCODE -ne 0) { throw 'Pinned image download failed.' }
.\runner\preflight.ps1 -Image $runnerImage.image
if ($LASTEXITCODE -ne 0) { throw 'Runner prerequisites are not ready.' }
```

Setup uses network access to obtain the image; future submitted-code containers
must still have no network access. Image updates require a reviewed pin change
and new verification. A digest provides reproducibility, not a security audit.

## Read-only preflight (Part 5a)

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
3. The selected local image is Linux and declares Java 21 in `JAVA_VERSION`
   (both `21...` and Temurin's `jdk-21...` forms are accepted).
   Its image ID and available repository digests are printed for review.

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
that those gates passed. Runtime probes and enforcement are later tasks.

Command references: [Docker info](https://docs.docker.com/reference/cli/docker/system/info/),
[image inspect](https://docs.docker.com/reference/cli/docker/image/inspect/),
and [WSL list/verbose](https://learn.microsoft.com/windows/wsl/reference).

Verified on 2026-10-11 with Windows PowerShell 5.1.26100.9444: syntax, eight
simulated prerequisite cases, missing/nonzero CLI failures, and timeout at 10.03
seconds. The host reports `docker-desktop` as WSL2, but its Linux daemon pipe is
unavailable. The real preflight exits `1`; live image inspection and a complete
successful environment check remain unverified. No containers were launched.

Part 5b supersedes that host blocker: Docker Desktop started, Linux/amd64 engine
29.8.0 responded, and the pinned image was downloaded/inspected. It declares
`jdk-21.0.12.1+1`; preflight exits `0`. Three accepted and six rejected metadata
cases, syntax, and local references passed. Java/javac execution, restrictions,
and isolation are still unverified. No container was launched by this task.

Recovery on 2026-10-12 reverified the documented startup/digest-pull/preflight
commands after restarting Docker. Preflight exits `0`; inspected platform,
repository digest, and Java metadata match `image.json`. Nine metadata cases,
PowerShell syntax, JSON pin, local references, and whitespace checks passed.
