# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-24 (Asia/Saigon)
- Owner: Claude Code
- Status: Part 20 (DP tables) on `feat/part-20-dp`, stacked on `feat/part-19-structures` (not merged yet).
  Merge 19 first. Frontend only (plus a tracer test that makes DP fixtures).
- Milestone: V1 complete. Public deployment on hold.

## Part 20 (user: "did our app handle dynamic programming ... I want its visualization to have a full matrix
## of how it store the value and reuse it", then "yes, please implement it"); spec: specs/dp-view.md

- [x] 20a bottom-up: `trace/dataflow.ts` `originOf` (reads and calls of a write's statement), `trace/dp.ts`
      `dpLog` / `dpTables`, `views/DpPanel.tsx` (panel `dp`, full width): computed / not yet (default or fill
      shown dimmed), #order, reuse badges, the step's explanation (other arrays named), click a cell for
      came-from and used-by arrows
- [x] 20b top-down: writes after recursion in the writing call; memo arrays and integer-keyed maps
      (put/get); one reuse per call between table writes
- [x] 20c examples "DP: Fibonacci with a memo", "DP: 0/1 knapsack" (grid paths is in "DP and two pointers");
      TracerTest `dynamicProgrammingProgramsAreTraced` (fixtures dp-grid, dp-memo, dp-knapsack); dp.test.ts (3);
      browser test "(20)"
- [x] spec, memory

## Verification

- Frontend unit 73 (with layout test update), build; full Playwright 34 passed (`.tools/pw-20.log`); no run containers left.
- Java main code unchanged since Part 19's verify (tracer 151, backend 102); the new tracer test passed alone.

## Next action

Report Part 20 with the PR link; wait. Known limit: static fields of Main are not traced.
