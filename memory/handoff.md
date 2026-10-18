# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-18 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: trace engine (Part 7); Milestone 0, full isolation and V1 incomplete

## Last approved task: Part 7b host trace-v1 producer (complete)

User merged 6e and 7a (main 528d45d) and said "please continue implement part 7b".
Claude treated that as accepting ADR 0004 and used the split proposed with 7a:
7b host producer, 7c container, 7d run API, 7e frontend. Told the user.

- [x] 1. ADR 0004 Accepted; [bytecode-tracing.md](../specs/bytecode-tracing.md) — 7de00b5
- [x] 2. Shared `contracts/trace/check.cjs` — 27722f4
- [x] 3. `runner/tracer/` analyzer, instrumenter, recorder, builder, tests — 42bc5f6
- [x] 4. [runner/tracer/README.md](../runner/tracer/README.md), memory (this commit)

Coverage: first increment inside `Main.main` only. Out of scope (output-only):
helpers/recursion, uninitialized declarations, compound assignment, string
concatenation, collections, other types.

## Git

- Branch `feat/part-7b-trace-producer` from main 528d45d. Pushed with this commit.
- PR (manual, `gh` unavailable):
  https://github.com/KhoaKhoa811/code-visualization-2/pull/new/feat/part-7b-trace-producer

## Verification (2026-10-18, Claude)

- `runner/tracer`: `mvn test` (Maven 3.9.16, JDK 21.0.12.1,
  `-Dmaven.repo.local=../../.tools/m2`): **27 tests, 0 failures**
  (`.tools/part-7b-tests.log`). Traces in `runner/tracer/target/traces/`.
- Every trace passed `node contracts/trace/check.cjs`; every traced program
  matched the original's stdout, exit status and exception line.
- `npm --prefix contracts test`: 51 passed after the check.cjs refactor.
- Host JVM processes on trusted programs only; no container run yet.

## Decisions in this task

- Plan/bytecode one-to-one matching; mismatch means output-only (`TRACE_MAPPING`).
- User JVM records only site + values; builder supplies ranges, operators,
  declarations, previous values; inconsistent records end capture (partial).
- Scopes derived from static block chains plus declaration re-execution.
- javac classpath is empty: submitted code cannot see tracer/recorder classes.

## Resources

- No commands running; no containers.

## Next action

Wait for review/merge. Propose 7c (run the tracer inside the 6d/6e container
lifecycle: stage recorder jar, compile+instrument in the compile phase, copy the
site table out before runtime, fetch raw records, build trace) for approval.
