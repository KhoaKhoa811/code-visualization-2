# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-20 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: Part 8 (coverage) complete through 8i; Milestone 0, full isolation and V1 incomplete

## Last approved task: Part 8i hardening (complete)

User (2026-10-20): "ok, I merged all the branches into main. please continue implement", after Claude
recommended 8i (stack traces, BufferedReader, editor scroll) and deferring input-wait detection to 6g.

- [x] 1. Stack traces show only the program's frames — 23212c6
- [x] 2. BufferedReader input object, trace contract 1.8.0 — 8b62621
- [x] 3. Editor scrolls each step into view from its line start — 5ab7451
- [x] 4. Guides and memory (this commit)

## Git

- Branch `feat/part-8i-hardening` on main d5265bb (8g, 8h merged as PRs #94, #95).
- PR (manual, `gh` unavailable):
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-8i-hardening?expand=1

## Verification (2026-10-20, Claude)

- Root reactor `clean verify` with live Docker: **171 tests, 0 failures/skips** (tracer 78, backend 93).
  Log `.tools/part-8i-verify.log`. No owned container left.
- Contracts 62; frontend 21 reducer tests, build, 10 Playwright tests (real backend + Docker).
  The new scroll assertion was confirmed to fail without the fix (left -78px).

## Decisions in this task

- Recorder helpers drop their own leading stack frames (and JDK frames above them) from JDK
  exceptions they raise; TracerTest now compares every uncaught stack trace with the original.
- BufferedReader over System.in reuses the 8g input object (contract 1.8.0 className enum);
  readLine/read/close covered, ready() left out (timing-dependent). One stdin reader per run.
- Input-wait detection (Part 4r 30 s) stays deferred to the 6g isolation review.

## Resources

- No commands running; no containers. Docker Desktop is running.

## Next action

Wait for review/merge of 8i. Propose the next step (V1.1: custom mappings, view rankings, separate
test inputs) for approval.
