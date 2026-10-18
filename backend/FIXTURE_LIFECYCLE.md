# Fixed-fixture execution (Part 6d)

The backend can now prepare a restricted container, compile a trusted repository
fixture, run it, collect bounded output, and verify stop/removal. This is real
Java execution inside Docker, but it is not yet a submitted-source API or tracing.

## Small review pieces

| Part | Commit | What changed | Verification |
| --- | --- | --- | --- |
| 6d.1 | `3c5b2f8` | Shared raw output quota and serialized deadline/overflow decisions | Boundary, raw-byte, shared-phase and cutoff tests passed |
| 6d.2 | `e3aa793` | Separate stdout/stderr readers and bounded phase processes | EOF, fast overflow, stdin EOF, process failure and timeout tests passed |
| 6d.3 | `4677239` | Bundle/stage only approved fixture files; prepare owned environment | Live fixture preparation passed |
| 6d.4 | `512ef3b` | Compile/run gating and whole-environment cleanup | Unit failure tests and all 12 live cases passed |
| 6d.5 | Documentation commit | This guide and both memory files | Paths, scope and whitespace checked |

All pieces are complete. The branch starts from merged main `d36e5c0`.

## Files and flow

- `RunOutput` shares 1,048,576 raw bytes across both streams and both phases.
- `PhaseGate` preserves the first observed timeout, overflow, failure, or drained
  exit. Output exactly at quota is allowed; one more byte triggers the limit.
- `PhaseProcess` collects stdout and stderr separately. It waits for both EOFs
  before accepting normal completion. Stopping the CLI alone is not cleanup.
- `FixedFixture` selects a closed set of bundled sources and commands. Sources
  live in root `runner/fixtures`; Maven copies only four named resources.
- `FixtureControl` is the container-operation boundary for the coordinator and
  independent failure tests. `DockerControlAdapter` supplies its real implementation.
- `FixedFixtureLifecycle` prepares, compiles, conditionally executes, then stops
  and removes the owned container. Each instance runs once.

Preparation shares a 10-second budget. Compilation gets 10 seconds and execution
gets 5 seconds. Runtime is never requested after failed compilation, compiler
timeout, overflow, or transport failure. Existing stop/cleanup confirmation bounds
remain separate. See the [specification](../specs/fixed-fixture-lifecycle.md).

## Reproduce verification

Use the Java/cache setup in [README](README.md), start Docker Desktop, and ensure
the pinned image is already installed. From backend/:

```powershell
$dockerPath = (Get-Command docker.exe -CommandType Application).Source
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=../.tools/m2' '-Dcodeviz.docker.live=true' "-Dcodeviz.docker.executable=$dockerPath" clean verify
```

On 2026-10-18 this passed **36 tests, zero failures/errors/skips**, and packaged
the executable JAR. Without the opt-in property, Docker tests are skipped.

The 12 lifecycle cases were normal output, real compiler rejection, blocked javac,
an infinite Java loop, output below/exactly/above quota, stderr overflow, combined
streams, partial multibyte output, a quota shared across phases, and compiler-phase
overflow preventing runtime. All verified termination and removal. The existing
6c live idle-container test also passed. No broad Part 5 suite or trace-contract
suite was rerun, since those files and contracts were unchanged.

## Limits of this increment

Results are internal probe results, not the public run/trace schema. `outcome`
describes the phase outcome; always inspect termination/cleanup flags and separate
infrastructure failures. Cleanup failure cannot erase an earlier timeout or
overflow. Nonzero Docker exec exit is not sufficient to classify a Java exception
or compiler error in the future public API; attribution remains unfinished.

There is no source admission, HTTP execution route, tracing, cancellation manager,
global run capacity, or restart reconciliation. Fixtures are acceptance probes,
not algorithm templates for future visualization. Part 6e proposes cancellation,
slot ownership, and recovery. Full isolation and V1 remain incomplete.
