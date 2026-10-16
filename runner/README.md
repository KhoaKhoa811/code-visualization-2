# Runner setup (Parts 5a–5u)

For the consolidated gate, verified scope, remaining gaps, and proposed next part,
read [the Part 5u prototype review](PROTOTYPE_REVIEW.md). Run
`powershell -NoProfile -File runner/test-prototype.ps1` for the fixed-probe gate.
Older part sections retain their historical measurements; the review is the
current coverage summary. Passing these probes does not complete isolation or V1.

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

## Storage byte-limit probe (Part 5l)

```powershell
.\runner\smoke.ps1 -StorageBytes
$LASTEXITCODE
```

The fixed [shell fixture](fixtures/storage-bytes.sh) checks each tmpfs independently:
`/work` must have 67,108,864 bytes total capacity; `/tmp` and `/dev/shm` must each
have 16,777,216 bytes. Filesystem block counters provide the effective capacity;
the fixture does not infer enforcement from a Docker setting or error text alone.

For each mount, it accounts for existing files, writes real zero-filled pages into
one probe file until available blocks reach zero, and requires an additional byte
append to fail without growing the file. Free inodes must still remain. Removing
the file must restore the original available-block count; a subsequent small
write/read/remove cycle must also succeed. Only one mount is filled at a time.
These capacities include existing source/fixture files; they are not guarantees
of that many bytes of new user payload.

Verified on 2026-10-14: all three mounts passed exhaustion, rejection, restored
space, and reuse checks, with container cleanup. Temporary 32 MiB and 128 MiB
`/work` configurations were rejected before filling; simulated successful overflow
also failed the probe. All edits were restored. Mount/filesystem/default Java
regressions and cancellation (369 ms) passed, as did syntax and seven conflicting
mode checks. The existing 15-second fixed-command wait and separate five-second
cleanup budget remain unchanged; `-StorageBytes` excludes every other mode.

This verifies fixed shell writes against storage byte limits. Inode exhaustion
(Part 5m), Java-specific caught storage errors, terminal classification, concurrent
storage pressure, and the Part 5k message-queue gap remain unverified.

## Inode-limit probe (Part 5m)

```powershell
.\runner\smoke.ps1 -StorageInodes
$LASTEXITCODE
```

The fixed [shell fixture](fixtures/storage-inodes.sh) requires effective inode
capacities of 4,096 for `/work` and 1,024 each for `/tmp` and `/dev/shm`. It checks
the actual tmpfs counters, including slots already used by directories and fixture
files. These are total inode limits, not promises of that many new user files.

Each mount is tested separately. A probe-owned directory consumes one inode;
empty numeric files consume the remaining free slots. At zero free inodes, both
another empty file and another directory must fail to be created while data blocks
remain available. Removing the probe files and directory must restore the baseline
free-inode count; a fresh directory/file creation and removal must also succeed.

Verified on 2026-10-14: all three mounts passed exhaustion, rejection, recovery,
reuse, and cleanup. Temporary `/work` limits of 2,048 and 8,192 were rejected;
simulated successful file/directory creation beyond capacity also failed. Temporary
edits were restored. Storage-byte, mount, filesystem, normal Java compile/output,
and cancellation (393 ms) regressions passed, together with syntax, eight mode
conflicts, and whitespace. The 15-second fixed-command wait and separate five-second
cleanup budget remain unchanged. `-StorageInodes` excludes every other probe mode.

Java-specific caught storage errors, terminal-status classification, concurrent
storage pressure, and message-queue resource limits remain unverified.

## Java heap-exhaustion probe (Part 5n)

```powershell
.\runner\smoke.ps1 -Heap
$LASTEXITCODE
```

The fixed [Java fixture](fixtures/HeapExhaustion.java) retains 1 MiB arrays until
allocation throws `OutOfMemoryError`. It checks the pinned HotSpot runtime's
`MaxHeapSize` option equals 134,217,728 bytes and its reported usable maximum is
positive and no larger. It does not assume usable heap equals the configured cap.

The [shell checks](fixtures/heap-check.sh) run two fresh JVMs sequentially. The
caught case releases retained references, prints its caught/continued markers,
and exits 0 with exact stdout and empty stderr. The uncaught case exits 1 with
only the startup marker on stdout and the expected heap-space error/stack frame
on stderr. A fresh container's cgroup `oom` and `oom_kill` counters must each be
present exactly once and zero before execution and after each case. Docker must
also report no OOM kill. These checks distinguish this fixture's Java heap error
from a container memory-limit event; they do not classify arbitrary submitted text.

Verified on 2026-10-15: both cases and cleanup passed. Seven negative cases were
rejected with cleanup: absent exhaustion, wrong continuation, uncaught success,
64 MiB heap, injected OOM-kill evidence, missing counter, and duplicate counter.
The counter cases use synthetic records, not actual container OOM kills. Temporary
edits were restored. Cancellation (382 ms), timeout (5,003 ms), normal compilation/
output, syntax, nine conflicting modes, and whitespace passed.

The existing 15-second fixed-command wait covers compilation and both trusted
cases; cleanup retains its separate five-second budget. `-Heap` excludes other
modes. Container OOM enforcement remains Part 5o; submitted-Java classification,
trace capture, and partial replay are not implemented by this probe.

## Container memory probe (Part 5o)

```powershell
.\runner\smoke.ps1 -ContainerMemory
$LASTEXITCODE
```

The fixed [Java fixture](fixtures/ContainerMemoryPressure.java) starts six workers,
each retaining 80 MiB of touched arrays with its own 128 MiB heap. Serial GC and
one active processor keep worker thread counts low. All workers share the existing
512 MiB container budget; the container profile is unchanged.

The [supervisor](memory-probe.ps1) reads effective cgroup `memory.max=536870912`
and `memory.swap.max=0` through trusted Docker commands. After compilation, OOM
and OOM-kill counters must be zero. During pressure, `max`, `oom`, and `oom_kill`
must all increase before the runtime deadline. Output text, exit code 137, and
Docker's `OOMKilled` flag alone cannot establish this evidence. A child may be
the kernel's victim while PID 1 and other workers remain alive, so the supervisor
then kills the whole container and verifies exited status, PID zero, attachment
exit, and closed output streams. Normal ownership cleanup verifies absence.

Compilation has a 10-second budget; runtime evidence has five seconds. Termination
and cleanup each have separate five-second budgets. Missing, duplicate, invalid,
late, or preexisting OOM evidence fails the probe. `-ContainerMemory` excludes all
other modes. Fixed-fixture output is small; general output quotas remain Part 5r.

Verified on 2026-10-15: final live evidence arrived after 569 ms with counters
max=235, oom=12, oom_kill=2, followed by whole-container stop and cleanup. Five
negative cases rejected spoofed output without pressure, exit 137 without OOM,
a missing counter, preexisting OOM evidence, and an incorrect expected memory
limit. Every negative case verified cleanup; temporary edits were restored.
Heap, cancellation (414 ms), timeout (5,011 ms), and normal Java regressions passed.
PowerShell syntax, ten conflicting-mode rejections, and whitespace checks passed.

This proves the fixed local pressure case. Kernel victim selection and timings
vary. `MEMORY_LIMIT` is a probe result, not an implemented API/trace status.
Arbitrary submitted programs, partial traces, all failure races, and public
deployment readiness remain outside this evidence.

## Process/thread probe (Part 5p)

```powershell
.\runner\smoke.ps1 -Tasks
$LASTEXITCODE
```

The fixed [Java fixture](fixtures/TaskLimit.java) starts one sleeping child, then
creates sleeping platform threads until creation fails. It requires exactly 128
current kernel tasks and an increased `pids.events:max` counter. At that same
cap, a process-creation attempt must fail with a further counter increase. A Java
error alone cannot pass either check. The loop attempts at most 128 new threads.
The fixture uses 128 MiB heap, 256 KiB thread stacks, Serial GC, and one active
processor; it does not change the container's existing limits.

The [supervisor](task-probe.ps1) checks effective `pids.max=128` and zero denial
counters before and after compilation. Once the fixed fixture exits and releases
its daemon threads, the supervisor independently reads the same container's PID
counter and requires at least two denials, with no cgroup OOM/kill evidence.
It forcibly stops the remaining environment, checks exited status and PID zero,
then uses the existing ownership cleanup and absence verification. The child
remains alive through the fixture's last assertion and is covered by this stop.

Compilation has 10 seconds; the fixture invocation has five seconds. Counter reads
are individually bounded to one second. Termination and cleanup each have separate
five-second budgets. `-Tasks` excludes every other mode. The fixed fixture can
read counters without creating a helper at saturation; a fresh `docker exec`
reader may itself be denied at the cap. This does not implement continuous
monitoring of arbitrary submitted programs, phase-wide denial handling, task
headroom measurements, terminal trace statuses, or race arbitration.

Verified on 2026-10-15: final fixture completed in 529 ms with five PID denials,
followed by whole-container stop and cleanup. Five negative cases rejected fake
success without pressure, a fake process-creation error without a counter change,
a missing counter, preexisting denial, and an incorrect expected cap. Each
verified cleanup, and all temporary edits were restored.
Container-memory (515 ms), heap, cancellation (347 ms), child-timeout (5,016 ms),
and normal Java regressions passed, together with PowerShell syntax, eleven
conflicting-mode rejections, and whitespace checks.

Counter semantics: [Linux cgroup v2 PID controller](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html#pid).
This evidence covers the fixed local profile, not general executor readiness.

## Compilation deadline probe (Part 5q)

```powershell
.\runner\smoke.ps1 -CompilationTimeout
$LASTEXITCODE
```

The [fixed supervisor](compilation-probe.ps1) stages the existing `Main.java`
fixture and creates an argument-file FIFO with no writer. A real Java 21 `javac`
blocks while reading that FIFO. This deliberately stalls compiler startup without
depending on slow hardware, huge source files, or user-supplied compiler plugins.
The supervisor observes `javac` through Docker's process inventory.

One monotonic 10,000 ms deadline starts before the compiler launch request and
includes JVM startup, observation, and output closure. Expiry latches the timeout
and closes the runtime launch gate. Whole-container kill, exited status, PID zero,
attachment exit, and closed output are verified within a separate five-second
termination budget. Existing ownership cleanup has its own five-second budget.
`-CompilationTimeout` excludes all other modes. Setup occurs before the timer.

Verified on 2026-10-15: final timeout latched at 10,007 ms, with runtime never
launched, whole-container stop confirmed, and cleanup verified. Three temporary
controls passed: a normal argument file compiled the fixture and ran `runner-ok`;
invalid Java source preserved compiler diagnostics and blocked runtime; an unrelated
sleeping process failed the real-compiler evidence check. All controls correctly
failed the expected-stall probe and verified cleanup; edits were restored.
Task-limit (521 ms), cancellation (433 ms), child-timeout (5,014 ms), normal Java,
PowerShell syntax, twelve mode conflicts, and whitespace checks passed.

This is one fixed compiler invocation, not general compilation orchestration.
Multiple compiler invocations sharing a budget, noisy compiler output, compiler
descendant fixtures, deadline/completion/cancellation races, and terminal trace
envelopes remain unfinished. `COMPILATION_TIMEOUT` is a probe result only.

## Shared output quota (Part 5r)

```powershell
.\runner\test-output-budget.ps1
.\runner\test-output-quota.ps1
# One case instead of the full integration gate:
.\runner\smoke.ps1 -OutputQuota -OutputCase combined
```

The [byte collector](OutputBudget.cs) runs inside Windows PowerShell; this does
not change the Java/Spring Boot backend choice. Two asynchronous raw-stream readers
share a locked 1,048,576-byte allowance. Exactly the quota is allowed; the first
extra observed byte latches overflow. Each stream retains its fitting prefix and
identity without text decoding or an invented cross-stream write order. Readers
discard excess in 4 KiB chunks until the supervisor terminates the environment.

The [worker supervisor](output-worker.ps1) reuses one collector for compilation
and runtime. It drains both streams before accepting normal exit or starting the
next worker, including after fast process exit. Overflow blocks further launches
and triggers whole-container stop. Confirmation checks exited status, PID zero,
attachment exit, and reader completion. Worker deadlines are 10 seconds for the
fixed compilation and five for execution; termination and ownership cleanup each
have separate five-second budgets. Existing Docker logging remains disabled.

Collector payload capacity is two fixed 1 MiB arrays, although their combined
accepted length never exceeds 1 MiB. Each worker has two 4 KiB read buffers;
post-drain verification snapshots total at most 1 MiB. There are no consumer
queues or decoding buffers in this path. These bounds do not measure total
PowerShell, .NET, Docker, or kernel memory, or implement Part 4p's global budget.

Verified on 2026-10-16: all nine [integration cases](test-output-quota.ps1) passed
with cleanup: quota minus one, exact quota, stdout overflow, stderr overflow,
concurrent combined streams, multibyte bytes cut inside UTF-8, compile/runtime
shared allowance, compile-phase overflow preventing runtime, and continuous flood.
The compile-phase cases use fixed shell output around javac. Every overflow retained
exactly 1 MiB; the shared-phase case retained one stderr byte plus 1,048,575 stdout
bytes. Accepted contents and stream identities were checked byte-for-byte.
Local tests also cover chunk boundaries, invalid raw bytes, concurrent reads,
quota persistence, blocked later launch, and surfaced reader faults. Compilation
timeout (10,011 ms), cancellation (488 ms), normal Java, PowerShell syntax,
thirteen mode conflicts, the OutputCase guard, and whitespace checks passed.

This bounded path is used by `-OutputQuota` only. Legacy trusted probes still use
their previous collectors. General submitted-program integration, analysis output,
trace envelopes, disconnected/slow UI clients, Docker transport-error attribution,
and output/cancellation races remain unfinished. Results are fixed-probe evidence,
not full executor or public-deployment readiness.

## Cross-run file isolation (Part 5s)

```powershell
.\runner\smoke.ps1 -RunIsolation
$LASTEXITCODE
```

The [probe](run-isolation-probe.ps1) keeps one original container alive while
creating, removing, and recreating a peer from the same pinned restricted profile.
At most two containers are alive at once. Both running HostConfig records, image,
and user must match; container identities must differ. Profiles are compared after
startup because Docker populates OomKillDisable during that transition.

The fixed [shell fixture](fixtures/run-isolation.sh) writes synthetic GUID tokens
to the same filename and a token-specific filename on each of `/work`, `/tmp`, and
`/dev/shm`. Each run must see its own exact bytes and no other run's token files.
Writing the peer's same-name files must leave the original run unchanged. The
recreated peer must have a different container ID and no previous markers before
writing its own. The original is checked again after both peer cleanups.

Each peer has a separate ownership label. Removal and absence verification share
a five-second cleanup budget and run even after a failed assertion. Uncertain
cleanup fails the probe; the original still receives its independent cleanup.
Fixed CLI checks retain their existing bounded waits; this is not an admission
or concurrency scheduler. No host mounts or persistent volumes are added.

Verified on 2026-10-16: concurrent marker isolation, fresh-peer storage, unchanged
original contents, and all cleanup checks passed. Two temporary negative cases
crossed run targets and inserted a foreign marker; both failed with peer/original
cleanup. Edits were restored. Final isolation, mount, filesystem, and normal Java
regressions passed, together with syntax, fourteen mode conflicts, and whitespace.

This verifies fixed files on the three approved tmpfs mounts. It does not prove
physical memory erasure, isolation of every kernel/IPC interface, cross-run trace
or console routing, or arbitrary adversarial-program isolation. Those gaps remain
visible alongside the earlier network, message-queue, and transport limitations.

## Stop failures and ordering (Part 5t)

```powershell
.\runner\test-stop-failures.ps1
$LASTEXITCODE
```

The [test harness](test-stop-failures.ps1) copies the relevant runner files into
a unique system-temp directory. Only those copies load the [fault hook](stop-test-hook.ps1);
production probe logic is unchanged. Tests run real restricted containers and
retain each original probe result before independent recovery. Recovery uses the
same bounded Docker CLI helper and the exact invocation's GUID ownership label.
It verifies absence and never removes unrelated containers. The temporary directory
is removed only after checking its absolute location and generated name.

Verified on 2026-10-16:

| Case | Verified result |
| --- | --- |
| Kill call fails | No successful cancellation or cleanup claim; original failure remains visible. |
| False running/PID evidence after kill | Termination stays unconfirmed; no successful cancellation claim. |
| Removal call fails | Probe fails; independent recovery removes the owned remaining container. |
| Removal returns after 5,100 ms delay | Cleanup deadline fails even though removal occurred. |
| Java completes before cancellation | Expected-loop probe fails without latching a stop cause. |
| Cancellation accepted first | RUN_CANCELLED remains the confirmed outcome and cleanup succeeds. |
| Java succeeds after timeout | A 5,200 ms sleeper and 750 ms delayed kill produce attachment exit 0; EXECUTION_TIMEOUT remains latched. |

All seven cases verified independent absence afterward. Syntax and whitespace
checks passed. The harness does not conceal the injected failures as successful
production runs. Each recovery CLI call is separately bounded to five seconds;
this test recovery is outside the original failed cleanup deadline.

These are controlled orderings, not an exhaustive concurrency proof. Exact-boundary
timing, hung daemon operations, duplicate cancellation, launch/input/output races,
slot accounting, trace publication, and restart recovery remain unfinished. Early
completion is rejected by the expected-loop test; this does not implement a general
normal-completion API. Existing Part 5 gaps remain for the final prototype review.
