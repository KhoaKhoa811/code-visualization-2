# Trace producer (Part 7b)

Produces [trace-v1](../../specs/trace-format.md) traces for the first Java increment
inside `Main.main` by instrumenting compiled bytecode, as decided in
[ADR 0004](../../specs/decisions/0004-bytecode-instrumentation.md). Behavior and
coverage are specified in [bytecode-tracing.md](../../specs/bytecode-tracing.md).

This part runs trusted test programs on the host. Running submitted code inside
the isolated container lifecycle is Part 7c; until then nothing here executes
untrusted input.

## Review pieces

| Part | Commit | Change | Verification |
| --- | --- | --- | --- |
| 7b.1 | `7de00b5` | ADR 0004 accepted; bytecode-tracing spec | Paths checked |
| 7b.2 | `27722f4` | `contracts/trace/check.cjs` shared schema + metadata checks with a CLI | Contract suite: 51 passed |
| 7b.3 | `42bc5f6` | This module: analyzer, instrumenter, recorder, trace builder, tests | 27 tests passed |
| 7b.4 | docs commit | This guide and memory | Paths checked |

## How it works

| Class | Role | Trusted? |
| --- | --- | --- |
| `Analyzer` | One javac task on the exact text (JDK-only classpath); walks the attributed tree in javac code-layout order and plans operations, or reports unsupported constructs with ranges | Yes, never executes code |
| `Instrumenter` | Matches the plan one-to-one with `Main.main` bytecode; rewrites matched instructions into recorder helpers; refuses on any mismatch | Yes |
| `codeviz.rt.Recorder` | Runs inside the user JVM; performs each instruction once and writes `site + values` to a raw file | No |
| `TraceBuilder` | Rebuilds trace-v1 from the site table, raw records and exit code; checks records against its own state | Yes |
| `Tracer` | Facade: `prepare(source)` then `trace(prepared, runId, raw, exitCode)` | Yes |

Since Part 8a ([partial coverage](../../specs/partial-coverage.md)) an unsupported statement in
`main` runs uncaptured as one `UNCAPTURED` step with an `UNSUPPORTED_CONSTRUCT`
diagnostic, and the rest of the program is traced. Only program-wide limits (no
`Main.main`, a package, reflection/files/processes/networking/threads) still give
`capture: unavailable`. Compile errors give `compile_error`.

## Run the tests

From `runner/tracer` (Node 22 and `contracts/node_modules` installed, see
[contracts README](../../contracts/trace/README.md)):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
..\..\.tools\apache-maven-3.9.16\bin\mvn.cmd -B -ntp '-Dmaven.repo.local=../../.tools/m2' test
```

On 2026-10-18: **27 tests, 0 failures.** Generated traces are kept in
`target/traces/` for inspection. Each trace passes `contracts/trace/check.cjs`,
and each traced program's stdout, exit status and uncaught-exception line match
the uninstrumented original.

## Limits and known gaps

- Coverage is the java-support first increment in `main` only. Helper methods and
  recursion, uninitialized declarations, compound assignment, string
  concatenation, collections and other types run as uncaptured statements (8a);
  their internals are not traced. Locals they assign stay unknown until the next
  covered store; arrays are re-read after statements that may write them.
- 8a host tests: **40 tests, 0 failures** on 2026-10-18 (13 new or changed cases).
- Since 8b ([primitive types](../../specs/primitive-types.md)) all primitive locals, casts, bitwise and
  shift operators, compound assignment, ++/-- on all numeric locals and int[] elements,
  the conditional operator and declarations without initializer are traced.
  **46 tests, 0 failures** on 2026-10-18.
- Since 8c ([statements and arrays](../../specs/statements.md)) `do`, labeled jumps,
  switch statements and top-level switch expressions on int-like selectors,
  for-each over arrays, and arrays of every primitive type, String and arrays of
  those are traced (trace 1.3.0). Every test also checks a plan round trip and
  that references resolve. **55 tests, 0 failures** on 2026-10-19.
- Since 8d ([methods](../../specs/methods.md)) static methods of Main with traced types are traced:
  CALL and RETURN steps, distinct frames for recursion, silent helpers under uncaptured code,
  unwinding, a 64-frame depth limit. **59 tests, 0 failures** on 2026-10-19.
- Scope entry/exit is derived from recorded operations and static block structure
  (see the spec); blocks with no recorded operation leave no trace.
- On the host, a builder-side trace limit marks the run `limited` even though the
  host process finished; in the container (7c) the orchestrator stops the run.
- The recorder halts the JVM at 16 MiB of raw records (exit 86).
- Recorder and tracer classes share one artifact here; 7c should ship the recorder
  separately on the program's classpath and keep the site table outside the
  program's writable area.
