# Cancellation, run slot and recovery (Part 6e)

Part 6e adds user cancellation, a single shared run slot with a short queue, and
explicit recovery of an environment whose cleanup could not be confirmed. It
builds on the [fixed-fixture lifecycle](FIXTURE_LIFECYCLE.md) and still runs only
trusted repository fixtures. There is no HTTP route, submitted source or tracing.
Behavior is specified in [fixture-cancellation-recovery.md](../specs/fixture-cancellation-recovery.md).

## Small review pieces

| Part | What changed | Verification |
| --- | --- | --- |
| 6e.0 | Handoff-efficiency docs for Codex/Claude (memory, AGENTS.md, `.gitattributes`) | Link/path/whitespace checks |
| 6e.1 `4649fa3` | `RunCancellation`, `CANCELLED` cause, lifecycle cancellation and known-ID recovery in `FixedFixtureLifecycle` and `DockerControlAdapter`; spec | Builds alone; 33 unit tests passed |
| 6e.2 `94bb1ac` | `FixtureRunSupervisor`: one slot, two queued, 30 s expiry, startup inventory | Supervisor unit tests, live cancellation/recovery tests; full verify below |
| 6e.3 | This guide and memory | Paths checked |

Cancellation and the lifecycle change share one commit because the new gate
cause alone would leave the old lifecycle `switch` uncompilable.

## How it works

- `RunCancellation` targets one lifecycle. It latches on the same `PhaseGate` that
  accepts output, so an earlier timeout, overflow, failure or final exit wins.
  Duplicate requests return `false` and repeat nothing. A successful compile is
  not final: cancelling between phases still prevents runtime.
- Cancellation never interrupts the run thread. Interrupting a Docker control
  call leaves its daemon outcome unknown, quarantines the container and would hold
  the only slot. Preparation (at most 10 s) finishes; then the cancellation check
  stops the run before compilation. Running phases stop within one 10 ms poll.
- Every cancelled run still goes through whole-container stop and removal.
- `FixtureRunSupervisor` owns one slot and at most two FIFO queued requests.
  Queued requests expire 30 s after acceptance and never launch once cancelled
  or expired. A run whose termination or cleanup was not confirmed stays `HELD`
  and blocks the queue.
- `recover(handle)` is explicit. It requires settled control and phase workers,
  a known full container ID and matching name/owner. It force-removes that exact
  ID and verifies absence. Original outcome, output and failures are retained;
  a failed attempt adds `RECOVERY_FAILED`. Unknown create outcomes stay blocked.
- `FixtureRunSupervisor.open` refuses to start while any container with the
  `codeviz.owner` label exists. It never deletes them automatically.

## Reproduce verification

From `backend/` with Docker Desktop running (same setup as [README](README.md)):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
$env:MAVEN_USER_HOME = Join-Path (Split-Path $PWD) '.tools\maven-home'
$dockerPath = (Get-Command docker.exe -CommandType Application).Source
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=../.tools/m2' '-Dcodeviz.docker.live=true' "-Dcodeviz.docker.executable=$dockerPath" clean verify
```

On 2026-10-18 this passed **49 tests, zero failures/errors/skips**, and packaged
the JAR. Live cases include cancelling a real blocked `javac` and a real infinite
Java loop with verified removal, known-ID recovery after a deliberate quarantine,
and the startup inventory refusing admission while an owned container exists.
No owned containers remained afterwards.

## Limits

- The supervisor is in memory only, not a crash journal. A daemon create that
  completes after a crash cannot be fully fenced by a CLI inventory.
- Spring wiring, a single application-wide supervisor and HTTP are Part 6f.
- Exact cancellation timing under daemon delays is not measured; latency during
  preparation is bounded by the 10 s preparation budget.
- Full isolation and V1 remain incomplete.
