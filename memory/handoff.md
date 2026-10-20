# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-20 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: Part 8 (coverage); Milestone 0, full isolation and V1 incomplete

## Last approved task: Part 8h exceptions (complete)

User (2026-10-20): "ok, implement part 8h". Spec: [specs/exceptions.md](../specs/exceptions.md).

- [x] 1. Contract 1.7.0, tracer (throw, try/catch/finally, unwinding), spec — 46df1b6
- [x] 2. Frontend playback, example, container and browser checks — 751d0f5
- [x] 3. Guides and memory (this commit)

## Git

- Branch `feat/part-8h-exceptions`, stacked on unmerged 8g (`feat/part-8g-live-console`, f97103a), on main e24112a.
- PRs (manual, `gh` unavailable), merge 8g first:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-8g-live-console?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-8g-live-console...feat/part-8h-exceptions?expand=1

## Verification (2026-10-20, Claude)

- Root reactor `clean verify` with live Docker: **170 tests, 0 failures/skips** (tracer 77, backend 93).
  Log `.tools/part-8h-verify.log`. No owned container left.
- Contracts 62; frontend 21 reducer tests, build, 10 Playwright tests (real backend + Docker).

## Decisions in this task

- Contract 1.7.0 (1.6.0 retired): `exception` object (binary className, message or null), CATCH step
  (binding, DECLARE effect), EXCEPTION `objectId`, `|` allowed in javaType for multi-catch.
- A failure no longer ends the recording. The builder tracks the exception in flight; `w` records
  end helper frames mid-trace; catch parameter and finally-handler stores record the exception.
  CATCH is checked against the clause's classes (source hierarchy, then JDK).
- Finally blocks are planned once and copied into javac's layout (normal exits per JLS 14.22 with
  javac's constant-if rule, jumps that leave the try, the finally handler). Finally blocks must be
  fully captured and jump-free, else the whole try runs uncaptured.
- Source exception classes are creatable only when "simple" (constructors calling super(params)).
  The recorder never runs user getMessage().
- Library failure handlers moved inline after the call and first in the exception table
  (otherwise a user catch never saw the libFail record/rethrow).
- Fixed a pre-existing bug: uncaptured try-with-resources made the whole program output-only;
  its region now starts at the first resource's range entry.

## Resources

- No commands running; no containers. Docker Desktop is running.

## Next action

Wait for review/merge of 8g and 8h. Part 8 roadmap is complete; propose the next step (V1.1 items or
the 8g input-wait follow-up) for approval.
