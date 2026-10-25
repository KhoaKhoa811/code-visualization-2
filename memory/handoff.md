# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-25 (Asia/Saigon)
- Owner: Claude Code
- Status: Part 21 (flow arrows) on `feat/part-21-arrows`. Stack, none merged: main <- 19 structures <- 20 DP
  <- 21 arrows. Merge in that order. Frontend only.
- Milestone: V1 complete. Public deployment on hold.

## Part 21 (user: "I find the arrow you implemented in dp visualization is very good. Can you implement it for
## all other visualizations. For example, there will be an arrow when there are swap. The rest of use case,
## please review and implement them all"); spec: specs/flow-arrows.md

- [x] `trace/flows.ts`: sources = same-frame reads on the value side of the step's statement (cells, fields,
      `s.charAt(i)` character, `list.get(i)` item, poll/pop/peek collection) + plain variables written there;
      targets: cell, variable, field, collection; calls: each argument into its parameter; `swapOf` (two writes
      exchanging the cells' previous values within 4 steps)
- [x] `views/FlowOverlay.tsx` over the whole state area (anchors from `motion.ts` `elementOf`), swaps also on
      heap-view nodes; same-row arcs over the tops; replaced the Part 18 `FlowLayer` in the Arrays panel
- [x] tests: flows.test.ts (4); browser "(21)" on Bubble sort; the 18 test now counts on the overlay
- [x] spec, memory

## Verification

- Frontend unit 77, build; full Playwright 35 passed (`.tools/pw-21.log`); no run containers left.
- Java unchanged since Part 19's verify.

## Next action

Report Part 21 with the PR link; wait for merges and the next request.
