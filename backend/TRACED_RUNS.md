# Traced runs in the container (Part 7c)

`TracedRunLifecycle` runs one Java source through the
[tracer](../runner/tracer/README.md) inside the same owned, restricted container
used by the [fixed-fixture lifecycle](FIXTURE_LIFECYCLE.md), and returns a
trace-v1 envelope. Behavior is specified in
[bytecode-tracing.md](../specs/bytecode-tracing.md#container-execution-part-7c).
Since Part 7d, the [run API](RUN_API.md) admits runs through `RunSupervisor`, which
owns the single slot and passes a run ID and preparation deadline.

## Review pieces

| Part | Commit | Change | Verification |
| --- | --- | --- | --- |
| 7c.1 | `1a4387c` | Tracer `Prepare` CLI, plan file, stop causes, torn-record handling | 33 tracer tests |
| 7c.2 | `99a83d2` | Shaded `tracer-runtime.jar`, root reactor pom, backend dependency | Reactor package |
| 7c.3 | `d1682ad` | Stdin staging, bounded reads, `TracedRunLifecycle`, unit tests | Backend 47 run, 0 failures |
| 7c.4 | `239a39a` | Live container tests; recorder flushes every 100 ms | Full live verify, 98 tests |
| 7c.5 | docs commit | This guide, spec section, memory | Paths checked |

## Flow

1. **Admission:** exact text, well-formed Unicode, at most 262,144 UTF-8 bytes,
   checked before any container work (`SOURCE_LIMIT` / `SOURCE_INVALID`).
2. **Preparation (10 s):** create and start the idle container, then stage
   `tracer-runtime.jar` and `Main.java` into `/work` through `docker exec -i`
   stdin. No host mounts; no base64 command lines (Windows caps them at 32K).
3. **Compile phase (10 s):** `java -cp /work/tracer-runtime.jar codeviz.tracer.Prepare`
   compiles with javac, plans and instruments. No submitted code runs.
4. **Plan read:** the backend reads `/work/out/plan.json` (at most 4 MiB) before
   any submitted code runs. Compile errors stop here.
5. **Runtime phase (5 s):** instrumented `Main` with the recorder, or the original
   classes for output-only programs. Output shares the 1 MiB quota.
6. **Record read:** for traced runs, whatever ended the phase, `kill -9 -1` as the
   run user stops the program (the container's init survives), then the raw
   records are read (at most 16 MiB + 64 KiB). The container's tmpfs disappears on
   stop, so this must happen first. A failed read gives unavailable capture and
   `TRANSPORT_FAILED` (named `TRACE_TRANSFER_FAILED` before 7d); it never quarantines the container.
7. **Stop and remove** as in 6d/6e. The trace is built only after termination is
   confirmed; otherwise `trace` is null and the failure is reported.

Status mapping follows execution-isolation: timeout is `limited` +
`EXECUTION_TIMEOUT` with partial capture; cancellation is `cancelled` +
`RUN_CANCELLED`; output overflow is `limited` + `OUTPUT_LIMIT`; a compile-phase
timeout is `limited` + `COMPILATION_TIMEOUT` with unavailable capture.

## Reproduce verification

The backend now depends on the tracer, so build from the repository root
(Docker Desktop running, pinned image present, Node 22 with `contracts/node_modules`):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
$env:MAVEN_USER_HOME = "$PWD\.tools\maven-home"
$dockerPath = (Get-Command docker.exe -CommandType Application).Source
.\.tools\apache-maven-3.9.16\bin\mvn.cmd -B -ntp -o '-Dmaven.repo.local=.tools/m2' '-Dcodeviz.docker.live=true' "-Dcodeviz.docker.executable=$dockerPath" clean verify
```

On 2026-10-18 this passed **98 tests (tracer 33, backend 65), zero failures or
skips**. Traced live cases: bubble sort (complete), uncaught array failure
(failed, complete), output-only helper program, compile error, runtime timeout
(limited, partial prefix), raw record limit (limited, recorder halt), and
cancellation during runtime (cancelled). Every trace passed
`contracts/trace/check.cjs`; no owned container remained.

## Limits

- Coverage is the first increment in `main` (see the tracer guide).
- Records written in the instant between the deadline and the kill are kept;
  records still buffered at the kill (at most ~100 ms) are lost. Both are
  honest prefixes of what ran.
- The tracer runtime is on the program's classpath. Traced programs cannot use
  calls or reflection, and javac sees only the JDK, so they cannot reach it.
- No live console yet. Full isolation and V1 remain
  incomplete; this is not public deployment readiness.
