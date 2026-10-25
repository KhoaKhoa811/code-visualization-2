# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-25 (Asia/Saigon)
- Owner: Claude Code
- Status: Part 24 (panel toggles) on `feat/part-24-toggles`. Stack, none merged: main <- 19 structures <- 20 DP
  <- 21 arrows <- 22 actions <- 23 layout <- 24 toggles. Merge in that order. Frontend only.
- Milestone: V1 complete. Public deployment on hold.

## Part 24 (user: "add a toggle option for every component so if I don't want to see them, I can close
## them"); spec: specs/panel-toggles.md

- [x] `layout.ts` `closed` + `toggleClosed`; `Panel` header button ▾/▸ (`toggle-<id>`), closed = title only
- [x] kept across steps and runs; Reset layout opens all
- [x] tests: layout.test.ts (24); browser "(24)"
- [x] spec, memory
- Declined by the user the same day: auto-detecting adjacency matrices as graphs.

## Verification

- Frontend unit 82, build; full Playwright 38 passed (`.tools/pw-24.log`); no run containers left.

## Next action

Report Part 24 with the PR link; wait for merges and the next request.
