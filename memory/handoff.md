# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-20 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: Core V1 completion (Part 9); V1.1 (Part 10) follows. Full isolation (6g) deferred.

## Approved roadmap (2026-10-20, "I approve")

9.0 scope checklist, 9a grid + dragging, 9b tree view (BST sort), 9c heap view (heap sort),
9d Deque stack/queue mode; then V1.1: 10a separate test inputs, 10b custom mappings,
10c view rankings, 10d broader problem suite. Order and AC mapping: [specs/v1-scope.md](../specs/v1-scope.md).
The user approved the roadmap and starting 9.0 + 9a. Ask before starting 9b.

## Last task: 9.0 + 9a (complete)

- [x] 9.0 specs/v1-scope.md (every AC with status and part), roadmap rows — c2b1fd0
- [x] 9a grid of panels, panel reorder by title drag, layout-only dragging of object boxes,
      arrays (groups) and collections; specs/frontend-design.md — dd459be
- [x] memory (this commit)

## Git

- Branch `feat/part-9a-grid-layout` on main 78b331e (8i merged as PR #96).
- PR (manual, `gh` unavailable):
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-9a-grid-layout?expand=1

## Verification (2026-10-20, Claude)

- Frontend only: 23 unit tests (reducer + layout), production build, 11 Playwright tests with the
  real backend jar and Docker. Java code unchanged since the 8i live verify (171 tests).

## Decisions in this task

- No new dependency: HTML5 drag-and-drop for panel titles, pointer events for elements.
- Panel order persists for the page session; element offsets last one run (ids never reused);
  nothing saved after the page closes (frontend-design.md).

## Next action

Wait for review/merge; then ask to start 9b (tree view with confirmed value/left/right mapping).
