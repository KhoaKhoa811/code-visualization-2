# Runner setup (Parts 5a–5k)

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

## Fixed cancellation probe (Part 5g)

```powershell
.\runner\smoke.ps1 -Cancel
$LASTEXITCODE
```

This mode reuses the parent/child fixture and restricted container. After observing
the distinct Java processes and their parent relationship, the supervisor requests
cancellation while the runtime attachment is still active and before the five-second
deadline. It latches `RUN_CANCELLED` before force-stopping the whole container.
Success requires the existing exited-state/PID-zero checks, closed attachment,
exact readiness output, empty stderr, and verified owned-container removal.
Termination confirmation and cleanup retain their separate five-second budgets.

If ancestry is not observed in time, the runtime deadline still stops the container,
but the cancellation probe fails. Early exit also fails. `-Cancel` cannot be combined
with either timeout switch. The default compile/run check remains unchanged.

Verified on 2026-10-13: cancellation latched at 337 ms after observing parent PID
458 and child PID 477; termination and cleanup passed. An early-exiting child and
readiness markers without a real child were rejected with verified cleanup. Test
fixture changes were restored. Existing parent/child timeout (5,006 ms), single-loop
timeout (5,013 ms), and normal compile/output checks passed.

Cancellation here is a fixed supervisor request, not an application API or UI action.
The label is not a trace envelope. Queued/compiling cancellation, repeated requests,
completion/deadline races, input/trace cutoffs, and arbitrary descendants remain
unverified. This small probe does not establish full cancellation enforcement.

## Filesystem protection probe (Part 5h)

```powershell
.\runner\smoke.ps1 -Filesystem
$LASTEXITCODE
```

The fixed [shell fixture](fixtures/filesystem.sh) runs as UID/GID 10001 in the
existing restricted container. It checks `/proc/mounts` for a read-only root mount,
then requires attempted new-file writes at `/` and `/opt/java/openjdk` to fail.
Write denial alone does not prove read-only mounting: user permissions can also
deny a write. The separate mount check supplies that evidence.

The fixture requires `/work`, `/tmp`, and `/dev/shm` to be writable tmpfs mounts.
It creates a small file in each, compares its bytes, removes it, and checks absence.
The supervisor requires successful container exit and the expected marker, then
verifies owned-container cleanup. The fixed-command wait remains 15 seconds;
cleanup retains its separate five-second budget. Windows line endings are
normalized before staging the shell fixture. This mode excludes all stop switches.

Verified on 2026-10-13: the filesystem probe and cleanup passed. Temporarily
expecting a writable root mount or treating `/tmp` as protected made the probe
fail with verified cleanup; the fixture was restored. Cancellation (322 ms),
parent/child timeout (5,009 ms), single-loop timeout (5,005 ms), and the normal
Java compile/output check also passed. PowerShell syntax and conflicting-mode
checks passed.

This verifies selected paths with a trusted shell fixture. Storage byte/inode
limits, cross-run reads, a complete writable-path audit, network restrictions,
and submitted Java enforcement remain separate unfinished checks.

## Network isolation probe (Part 5i)

```powershell
.\runner\smoke.ps1 -Network
$LASTEXITCODE
```

The fixed [Java fixture](fixtures/NetworkIsolation.java) compiles and runs in the
existing `--network=none` profile. The supervisor rejects published ports. The
fixture requires exactly one active loopback interface, only loopback addresses,
and no IPv4/IPv6 routes using another interface. A local TCP listener and client
transfer one byte successfully as a positive control.

Four numeric TCP destinations are tested with 500 ms connect deadlines:
`192.0.2.1`, `172.17.0.1`, `172.17.0.2`, and `2001:db8::1`, all on port 9.
Only the pinned runtime's observed `Network is unreachable` socket error passes;
successful connections, refusal, timeouts, and other errors fail. No DNS lookup
is needed. The existing 15-second fixed-command bound includes compilation and
execution; cleanup retains its separate five-second budget. `-Network` excludes
all other probe switches.

The private addresses represent host/peer address categories; they are not
discovered live services. Isolation evidence comes from the network configuration
and effective interfaces/routes together with the socket checks, not from assuming
those endpoints are running. Direct checks against a Windows host listener and
a live peer container, DNS/UDP probes, and adversarial isolation remain unverified.

Verified on 2026-10-13: the network probe and cleanup passed. Injecting a reachable
loopback endpoint, a refused loopback endpoint, or a wrong interface expectation
caused failure with verified cleanup; temporary fixture edits were restored.
Filesystem, cancellation (347 ms), and normal Java compile/output regression
checks passed. PowerShell syntax, four conflicting modes, and whitespace passed.

## Effective privilege probe (Part 5j)

```powershell
.\runner\smoke.ps1 -Privileges
$LASTEXITCODE
```

The fixed [shell fixture](fixtures/privileges.sh) checks `/proc/1/status` and
the inspecting process's `/proc/self/status`. All real/effective/saved/filesystem
UIDs and GIDs must be 10001. Supplementary group identities must be absent or
10001 only. Inheritable, permitted, effective, bounding, and ambient capability
sets must all be zero. `NoNewPrivs` must be 1; `Seccomp` must be 2 with at least
one installed filter. Missing or duplicate required fields fail the probe.
Field meanings follow the [Linux status documentation](https://www.man7.org/linux/man-pages/man5/proc_pid_status.5.html).

Before launch, the supervisor rejects extra groups, added capabilities, and
security-option overrides. It retains the existing default seccomp selection
and restricted profile. The fixed-command wait remains 15 seconds and cleanup
has its separate five-second budget. `-Privileges` excludes other probe switches.

Verified on 2026-10-14: live status checks and cleanup passed. Seven altered
status cases (root UID, extra group, nonzero effective capability, disabled
no-new-privileges, disabled seccomp, zero filters, missing ambient capability)
failed with verified cleanup. These were parser tests using modified status
copies; container protections were never disabled. Fixture edits were restored.
Filesystem, network, cancellation (398 ms), normal Java compile/output, syntax,
and five conflicting-mode checks passed.

This proves observed process restrictions, not a complete syscall-policy audit
or adversarial privilege-escalation test. Filter mode/count does not identify the
exact installed filter rules. Java-worker-specific status checks remain separate.

## Mount and writable-path audit (Part 5k)

```powershell
.\runner\smoke.ps1 -Mounts
$LASTEXITCODE
```

The supervisor requires exactly the three approved `HostConfig.Tmpfs` destinations
and rejects extra configured volumes, bind mounts, inherited volumes, and device
requests. The fixed [mount fixture](fixtures/mounts.sh) checks every `/proc/mounts`
entry against the observed pinned profile. Unknown/duplicate entries, missing
required mounts, writable root/system mounts, or missing temporary-storage
restrictions fail. This intentionally fails on unreviewed platform layout changes.

The audit checks storage ownership/modes, prevents user writes to `/dev`, scans
its own filesystem for writable regular files/directories, and checks masked proc
devices are null devices. Docker-managed hosts/hostname/resolver files must be
read-only. Standard Docker/Podman socket paths must be absent. Kernel pseudo-filesystems
are classified separately from the three approved regular-file storage mounts.

Observed finding: `/dev/mqueue` allows creating a queue entry, but rejects ordinary
byte writes. The probe removes its own entry and verifies absence. Message-queue
allocation/limits and other writable kernel interfaces are not established safe
by this audit; retain them as gaps for the final isolation review. The container
profile is unchanged. This is not an image-secret audit or exhaustive device/API
security proof. Storage byte/inode exhaustion remains Parts 5l/5m.

Verified on 2026-10-14: the live audit passed. Five altered mount inventories
(unknown mount, writable root, missing noexec, missing tmp, duplicate work) failed
with cleanup. An actual extra 4 KiB tmpfs declaration was rejected before container
start with cleanup. Temporary edits were restored. Privilege, filesystem, network,
cancellation (475 ms), and normal Java compile/output regressions passed, along
with syntax, six conflicting-mode checks, and whitespace. The existing 15-second
fixed-command wait and separate five-second cleanup budget remain unchanged.
