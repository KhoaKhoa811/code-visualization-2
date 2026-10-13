# Runner setup (Parts 5a–5f)

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

## Toolchain smoke check (Part 5c)

After setup, run from the repository root:

```powershell
.\runner\smoke.ps1
$LASTEXITCODE
```

Unlike preflight, this creates and starts one uniquely named container. Part 5c
runs fixed `id`, `java -version`, and `javac -version` commands against the pinned
image; Part 5d adds the fixed repository fixture below. It accepts no source or
command parameters and never pulls an image.
Exit `0` requires the expected UID/GID, Java/javac 21, normal exit, and verified
container removal. Read the script in order: bounded Docker calls, container
creation/configuration check, tool commands, then ownership-based cleanup.

The profile uses UID/GID 10001, no network, read-only root, dropped capabilities,
no-new-privileges, default seccomp, private IPC, 512 MiB memory with no extra swap,
128 kernel tasks, disabled core dumps/restarts/healthchecks/persistent logs, and
the specified 64/16/16 MiB tmpfs mounts with inode limits. No host mounts are added.
The image entrypoint is replaced; `env -i` limits the tool environment to PATH
and HOME. Runtime/compiler heaps are 128/256 MiB, used sequentially.

Container creation allows 10 seconds, attached commands 15 seconds total, and
other Docker calls 5 seconds each. Cleanup shares a 5-second budget. These are
smoke-check waits, not implementation of the submitted-program phase timers.
On failure the script attempts removal by its unique ownership label. If a CLI
call times out, pending daemon work is uncertain: the script reports failure
even if a later inventory is empty. Preserve the printed container name for
manual recovery; do not interpret timeout as confirmed removal.

Verified on 2026-10-12: Java/javac `21.0.12.1`, UID/GID 10001, normal container
exit, and removal. Seven simulated lifecycle cases passed: success, bad
configuration (no start), wrong Java, tool failure, timeout, removal failure,
and a container still present. Syntax and whitespace checks passed.

This is a trusted toolchain check. Configuration inspection is not proof of
kernel limit enforcement, all writable paths, hostile-code isolation, bounded
untrusted output, or the full lifecycle/recovery protocol. Compilation of user
source and all remaining prototype gates are still separate work.
See Docker's [create options](https://docs.docker.com/reference/cli/docker/container/create/)
and [tmpfs options](https://docs.docker.com/engine/storage/tmpfs/).

## Compile/run fixture (Part 5d)

The same `smoke.ps1` command now also compiles and runs
[fixtures/Main.java](fixtures/Main.java), which prints `runner-ok` followed by LF.
The fixture is a fixed installation check, not a tracing template or submitted-source API.

The script encodes the repository fixture as Base64 and decodes it into the
container's `/work/Main.java`. This keeps source text out of shell syntax and
requires no host mount. `javac` uses UTF-8, `--release 21`, disabled annotation
processing, and the existing compiler heap. `java` loads the resulting class
from `/work` with the runtime heap. Source, classes, and output files stay in
temporary container storage; no host `.class` files are produced.

The shell stops on compilation/execution failure. A byte comparison requires
exactly `runner-ok` plus LF, and a separate check requires empty program stderr.
Any failure still goes through the existing ownership/cleanup handling. Container
restrictions and the shared 15-second command wait remain unchanged.

Verified on 2026-10-12: the fixture compiles/runs successfully; compiler error,
wrong output, and missing final LF are rejected. All four live cases verified
container removal. The original fixture was restored byte-for-byte after negative
checks. No submitted-source support or broader isolation gate is claimed.

## Runtime timeout probe (Part 5e)

```powershell
.\runner\smoke.ps1 -RuntimeTimeout
$LASTEXITCODE
```

This selects a second fixed fixture, [TimeoutLoop.java](fixtures/TimeoutLoop.java),
which prints one startup marker and loops forever. The default command still runs
Part 5d's normal-output check. Both use the same pinned image, container restrictions,
configuration inspection, ownership labels, and cleanup. No image is downloaded.

Read [timeout-probe.ps1](timeout-probe.ps1) for the focused timeout sequence:

1. Start the restricted container with an idle process; compile the fixed loop
   through a bounded Docker exec call before starting the runtime clock.
2. Start an external monotonic **5,000 ms** timer immediately before requesting
   the Java exec launch. Docker overhead and JVM startup count; compilation does not.
3. Latch timeout at the deadline, kill the whole container, and use a separate
   **5,000 ms** confirmation budget for kill, state inspection, and attachment exit.
4. Require exited container state, PID zero, no OOM flag, closed output, and the
   exact Java startup marker. Then the existing cleanup path removes the owned
   container and verifies absence with its separate five-second budget.

Exit `0` means the expected timeout and cleanup were verified. An early return,
compiler failure, missing startup evidence, failed stop, or failed cleanup is a
failed probe. A later exit cannot turn a latched timeout into normal completion.
Timed-out control calls/unresolved attachments remain explicit failures; preserve
the printed container identity for recovery. `EXECUTION_TIMEOUT` in the output is
a probe label, not an implemented API response or trace envelope.

Verified on 2026-10-12: the five-second deadline was handled at **5,017 ms**;
container termination and removal passed. Early-return and compiler-error fixtures
were rejected with cleanup. Injected stop failure stayed visible; an independent
inventory confirmed removal without the probe claiming successful termination.
The normal Part 5d check also passed. Modified test files were restored byte-for-byte.
Host scheduling can delay deadline handling; this measurement is not a hard latency
guarantee. Completion/deadline races, arbitrary descendants, cancellation, traces,
and broader isolation/recovery enforcement remain separate prototype gates.

## Parent/child timeout probe (Part 5f)

```powershell
.\runner\smoke.ps1 -ChildTimeout
$LASTEXITCODE
```

[ParentChildTimeout.java](fixtures/ParentChildTimeout.java) starts a second JVM
with the same class and a child argument. Each JVM has a 128 MiB heap; both share
the existing container limits. The parent waits for the child's startup line,
prints two readiness markers, and fails if the child exits early. Both stay alive
until timeout. This is a fixed runner test, not a required user-code pattern.

The existing timeout helper compiles the selected fixture before the runtime
clock starts. Inside the same five-second budget, it uses
[Docker's process list](https://docs.docker.com/reference/cli/docker/container/top/)
to require two distinct Java PIDs with the child's PPID matching the parent PID.
Markers alone cannot pass. Queries have a one-second cap and no new query starts
in the final second; polling never resets or extends the runtime deadline.

The existing whole-container kill, exited-state/PID-zero checks, closed runtime
attachment, and owned-container removal verify termination for this observed
pair. Failed observations remain failures. `-ChildTimeout` and `-RuntimeTimeout`
are mutually exclusive; the default Part 5d check stays unchanged.

Verified on 2026-10-13: parent PID 1466 and child PID 1484 were observed; timeout
was handled at 5,014 ms and termination/removal passed. An early-exiting child
and a parent printing both markers without starting a child were rejected with
cleanup. The single-loop timeout (5,001 ms) and normal compile/run checks passed.
Negative-test fixture changes were restored. This proves the fixed two-JVM case;
arbitrary process trees, adversarial races, and full isolation remain unverified.
