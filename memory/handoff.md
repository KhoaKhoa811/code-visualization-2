# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-19 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: Part 8 (coverage); Milestone 0, full isolation and V1 incomplete

## Last approved task: Part 8c statements and arrays (complete)

User (2026-10-18): "Please continue implementing part 8c". Spec:
[specs/statements.md](../specs/statements.md).

- [x] 1. Contract 1.3.0 (SWITCH, nested array types), specs, tracer — 4dc320a
- [x] 2. Frontend 1.3.0 playback, rows for arrays of arrays, grid-and-switch example — a7fb958
- [x] 3. Browser check in Docker; fix for a declaration before a switch — 0a128aa
- [x] 4. Guides and memory (this commit)

## Git

- Branch `feat/part-8c-statements`, rebased onto main 234a586 (7d-8b merged as PRs #86-#89).
- PR (manual, `gh` unavailable):
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-8c-statements?expand=1

## Verification (2026-10-19, Claude)

- Root reactor `clean verify` with `-Dcodeviz.docker.live=true` and
  `-Dcodeviz.docker.executable=<docker path>`: **141 tests, 0 failures/skips**
  (tracer 55, backend 86). Log `.tools/part-8c-verify.log`. No owned container left.
- Contracts 56; frontend 13 reducer tests, build, 5 Playwright tests (real backend + Docker).

## Decisions in this task

- Contract bumped to exact 1.3.0: SWITCH step (value, matched; source = chosen label);
  elementType/javaType admit array types at any depth. 1.2.0 retired.
- Switch expressions are traced only as a whole initializer or assigned value (elsewhere
  javac spills the operand stack). Not traced: switch on String/enum/boxed, patterns,
  empty switch, a case-group local used in a later group, for-each over Iterable,
  creation with more than three lengths.
- For-each: one ARRAY_LENGTH step, then per element ARRAY_READ and DECLARE; hidden
  index test and increment are not steps.
- `new int[2][3]`: one ALLOCATE; inner arrays are OBJECT_CAPTURE bookkeeping in the same step.
- Known limit kept: NPE helpful messages and stack traces from recorder helpers differ
  from the original's stderr (since 7b).

## Resources

- No commands running; no containers.

## Next action

Wait for review/merge of 8c. Propose 8d (static methods and recursion, call-stack view)
for approval.
