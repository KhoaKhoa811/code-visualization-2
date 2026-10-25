# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-25 (Asia/Saigon)
- Owner: Claude Code
- Status: Part 22 (step actions) on `feat/part-22-actions`. Stack, none merged: main <- 19 structures <- 20 DP
  <- 21 arrows <- 22 actions. Merge in that order. Frontend only (plus one tracer test program).
- Milestone: V1 complete. Public deployment on hold.

## Part 22 (user: "very good. please implement all of them. but make sure we don't overload actions in one
## visualization"); spec: specs/step-actions.md

- [x] `trace/actions.ts`: `actionsAt` (swap, rewire, move, compare, return, stack, slot, flow, ghost) with the
      budget (3 by priority + 1 ghost, suppression of duplicate value arrows); `jumpAt` (back / skip)
- [x] `views/ActionOverlay.tsx` replaced `FlowOverlay.tsx`; `motion.ts` anchors `node`, `callNode`
- [x] editor margin jump arrow (`JavaEditor` `jump`), Arrows switch in Controls (`codeviz.arrows`)
- [x] chaining tracer test program indexes `buckets[k % 4]`; fixture regenerated
- [x] tests: actions.test.ts (4); browser 21 test updated, new "(22)"
- [x] spec, memory

## Verification

- Frontend unit 81, build; full Playwright 36 passed (`.tools/pw-22.log`); no run containers left.
- Java unchanged apart from the TracerTest program text.

## Next action

Report Part 22 with the PR link; wait for merges and the next request.
