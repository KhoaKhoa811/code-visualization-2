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

## Last approved task: Part 7a trace-engine spike (complete)

Approved 2026-10-18 ("1. I approve 2. I approve 3. I approve"): Core V1 / V1.1
tiers, engine spike with ASM, skip 6g and shrink 6f.

- [x] 1. Decisions recorded (requirements tiers, roadmap) — 9289e6c
- [x] 2. ASM bytecode tracer prototype `runner/tracer-spike/` — ddebca4
- [x] 3. Differential + event tests: AC-01..05, AC-07, AC-10, recursion, broad
- [x] 4. Source-rewrite comparison on AC-07 (hand-written naive vs correct rewrite)
- [x] 5. [SPIKE_REPORT.md](../runner/tracer-spike/SPIKE_REPORT.md),
      [ADR 0004](../specs/decisions/0004-bytecode-instrumentation.md) (Proposed), memory

## Waiting on the user

1. Accept or reject ADR 0004 (bytecode instrumentation + javac tree API mapping).
2. Merge 6e, then 7a. Approve 7b (first trace-v1 producer) with its checklist.

## Git

- Branch `feat/part-7a-engine-spike`, stacked on `feat/part-6e-cancellation-recovery`
  (5f52d8f, not merged; main fe68059). Pushed. After 6e merges, rebase onto main.
- PR (manual, `gh` unavailable), base = 6e branch until it merges:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-6e-cancellation-recovery...feat/part-7a-engine-spike?expand=1

## Verification (2026-10-18, Claude)

- `runner/tracer-spike`: `mvn test` (Maven 3.9.16 at `.tools/apache-maven-3.9.16`,
  JDK 21.0.12.1, `-Dmaven.repo.local=<repo>/.tools/m2`): **20 tests, 0 failures**.
  Log `.tools/part-7a-spike.log` (local). Host JVM processes on trusted fixtures,
  not the isolated runner.
- Backend unchanged since 6e verify (49 tests).

## Resources

- No commands running; no containers. Scratch traces only in the session scratchpad.

## Next action

Wait for the user's ADR 0004 decision and 7b approval. Proposed 7b checklist is in
the spike report's "Gaps and risks" and next-steps.md.
