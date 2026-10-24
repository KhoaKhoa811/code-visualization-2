# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-25 (Asia/Saigon)
- Owner: Claude Code
- Status: Part 23 (three-column layout) on `feat/part-23-layout`. Stack, none merged: main <- 19 structures
  <- 20 DP <- 21 arrows <- 22 actions <- 23 layout. Merge in that order. Frontend only.
- Milestone: V1 complete. Public deployment on hold.

## Part 23 (user: "Screen will split into 3 part ... center is a bit bigger than other 2", and the bug
## "object visualization should show all the objecft"); spec: specs/three-column-layout.md

- [x] columns side 1fr | center 1.4fr | editor+console 1.15fr; StatePanel `part: 'side' | 'center'`;
      controls and step text sticky at the top of the center
- [x] ActionOverlay moved to `main.layout`, redrawn on scroll/resize, ends hidden by a scrolled column skipped
- [x] Objects bug: Part 14's `.graph { display: block }` (graph view svg) overrode the Objects panel; now
      `svg.graph`; objects in top-down depth layers that wrap
- [x] tests: browser "(23)"; Playwright viewport 1600x900 (config); 9a drag test drops on the Call tree;
      22 test uses a tall viewport
- [x] spec, memory

## Verification

- Frontend unit 81, build; full Playwright 37 passed (`.tools/pw-23.log`); no run containers left.

## Next action

Report Part 23 with the PR link; wait for merges and the next request.
