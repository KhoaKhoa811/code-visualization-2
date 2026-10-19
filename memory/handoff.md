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

## Last approved task: Part 8d static methods and recursion (complete)

User (2026-10-19): "ok" to the 8d proposal. Spec: [specs/methods.md](../specs/methods.md).

- [x] 1. Spec and tracer (calls, returns, frames, silent helpers, unwind, depth limit) — e8ac8a2
- [x] 2. Frontend call stack, call/return descriptions, recursion example — 0f5baec
- [x] 3. Browser check in Docker — eb5fb0d
- [x] 4. Guides and memory (this commit)

## Git

- Branch `feat/part-8d-methods`, stacked on unmerged `feat/part-8c-statements` (d22f1f9),
  which sits on main 234a586.
- PRs (manual, `gh` unavailable), in order 8c, then 8d:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-8c-statements?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-8c-statements...feat/part-8d-methods?expand=1

## Verification (2026-10-19, Claude)

- Root reactor `clean verify` with `-Dcodeviz.docker.live=true` and
  `-Dcodeviz.docker.executable=<docker path>`: **145 tests, 0 failures/skips**
  (tracer 59, backend 86). Log `.tools/part-8d-verify.log`. No owned container left.
- Contracts 56; frontend 15 reducer tests, build, 6 Playwright tests (real backend + Docker).

## Decisions in this task

- Traced helpers: static, non-generic, fixed-arity methods of Main with traced parameter
  and result types (or void). Instance methods, constructors and other classes stay uncaptured.
- Contract unchanged (1.3.0 already has CALL, RETURN, FRAME_END unwind); plan format 5.
- Silence is decided at entry: no announced call site means silent (and everything below it).
- Returns inside uncaptured statements are recorded at that statement; implicit void returns
  at the closing brace (`implicit: true`).
- Depth limit 64 active traced frames including main (Part 4k budget): raw `D` record,
  halt with exit 86, execution limited, capture partial, CALL_DEPTH_LIMIT.

## Resources

- No commands running; no containers.

## Next action

Wait for review/merge of 8c and 8d. Propose 8e (objects and fields, new trace contract
version, object-graph view) for approval.
