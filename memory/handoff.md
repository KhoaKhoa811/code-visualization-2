# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-18 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: Part 8 (coverage); Milestone 0, full isolation and V1 incomplete

## Last approved task: Part 8a graceful partial coverage (complete)

User (2026-10-18): "ok, continue part 8a". Spec:
[specs/partial-coverage.md](../specs/partial-coverage.md).

- [x] 1. Trace contract 1.1.0 (unknown, UNCAPTURED, array OBJECT_CAPTURE), spec — 0470f06
- [x] 2. Analyzer regions, -Xjcov mapping, recorder re-reads, builder, integrity guard — 433c3b1
- [x] 3. Frontend 1.1.0 playback and display — ec73605
- [x] 4. Live container + browser tests; 10 s startup inventory — af58047
- [x] 5. Guides and memory (this commit)

## Git

- Branch `feat/part-8a-partial-coverage`, stacked on unmerged `feat/part-7e-frontend-slice`
  (0d87a90), which is stacked on unmerged `feat/part-7d-run-api` (bcbedd1); main 3d8fd53.
- PRs (manual, `gh` unavailable), in order 7d, 7e, 8a:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-7e-frontend-slice...feat/part-8a-partial-coverage?expand=1

## Verification (2026-10-18, Claude)

- Root reactor `clean verify`, live Docker: **126 tests, 0 failures/skips**
  (tracer 40, backend 86). Log `.tools/part-8a-verify.log`. No owned container left.
- Contracts `npm test`: 54. Frontend: 9 reducer tests, `npm run build`, 3 Playwright tests.

## Decisions in this task

- Contract bumped to exact 1.1.0; 1.0.0 retired (no stored traces).
- Statement-level degradation in main only; unsupported conditions/for-parts degrade the
  whole compound statement. Bytecode ranges from javac `-Xjcov` CharacterRangeTable
  (JDK-internal option; mapping failure falls back to output-only).
- Locals assigned by uncaptured code become unknown; arrays re-read after statements that
  may write them (calls other than pure java.lang/print, `new`, element writes, object concat).
- Integrity guard: reflection, Class/ClassLoader, files, processes, net, threads,
  concurrency, sun/jdk/com.sun keep a program output-only (`TRACE_INTEGRITY`).
- Traced runs now ship all compiled classes (helpers), with only Main instrumented.
- 7d fix inside this branch: startup inventory timeout 5 s to 10 s (control maximum).

## Resources

- No commands running; no containers.

## Next action

Wait for review/merge of 7d, 7e and 8a. Propose 8b (primitive types and operators:
long/double/char/..., casts, compound assignment, uninitialized declarations) for approval.
