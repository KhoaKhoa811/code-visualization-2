# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-18 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: trace engine and first visible slice (Part 7); Milestone 0, full isolation and V1 incomplete

## Last approved task: Part 7d minimal run API (complete)

User (2026-10-18), after merging 7b (PR #84) and 7c (PR #85): "Last time, you
stopped at proposal 7d, please review and continue implement." Guide:
[backend/RUN_API.md](../backend/RUN_API.md).

- [x] 1. Traced lifecycle: run-v1 failure codes, reserved deadline, recovery — c0e2988
- [x] 2. `RunSupervisor`: 1 slot, 2 FIFO queued, snapshots, retention — 0dc80a1
- [x] 3. HTTP routes, strict request parser, `RunnerAccess`, server limits — 8cd5e4a
- [x] 4. `contracts/run/check.cjs`, live API tests — 593c6ce
- [x] 5. Docs, spec section, memory (this commit)

## Git

- Branch `feat/part-7d-run-api` from main 3d8fd53. Pushed with this commit.
- PR (manual, `gh` unavailable):
  https://github.com/KhoaKhoa811/code-visualization-2/pull/new/feat/part-7d-run-api

## Verification (2026-10-18, Claude)

- Root reactor `clean verify` with live Docker (command in RUN_API.md):
  **118 tests, 0 failures/errors/skips** (tracer 33; backend 85 incl. 2 live API
  tests). Log `.tools/part-7d-verify.log`. `contracts/` `npm test`: 52 passed.
- Every live snapshot with a trace passed `contracts/run/check.cjs`; no owned container left.

## Decisions in this task

- Lifecycle failure enum now uses the run-v1 codes; `TRACE_TRANSFER_FAILED` and
  `SETUP_OR_TRANSPORT_FAILED` are gone (fixture lifecycle unchanged).
- New `RunSupervisor` instead of generalizing the 6e `FixtureRunSupervisor`; the
  fixture supervisor remains test-only and is never wired into Spring.
- Queued cancellation returns a `cancelled` trace-v1 envelope (unavailable
  capture, no Java ran); queue expiry is `410 QUEUE_EXPIRED` with no snapshot.
- Supervisor opens lazily on the first valid submission after the label inventory.
- Request parsing uses Jackson 3 streaming (already on the classpath through the
  web starter; no dependency added): duplicates, depth 32, 65,536 tokens.
- Output is a separate endpoint after confirmed termination, outside trace-v1.
- Retention: at most 4 finished/expired runs, 10 minutes each.

## Resources

- No commands running; no containers.

## Next action

Wait for review/merge of 7d. Propose 7e (frontend slice: Vite/React/TypeScript,
Monaco, playback reducer, array/variable views, bubble sort demo with a browser
check) for approval. Installing frontend tooling needs explicit approval.
