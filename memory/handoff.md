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

## Last approved task: Part 8g live console and Scanner input (complete)

User (2026-10-19): "ok" to the 8g proposal; "Add WebSocket (Recommended)" for
`spring-boot-starter-websocket` (version from the Boot 4.1.1 BOM). Spec:
[specs/live-console.md](../specs/live-console.md), ADR
[0001](../specs/decisions/0001-live-console-websocket.md).

- [x] 1. ADR 0001, spec, contract 1.6.0, tracer Scanner replay — b530c33
- [x] 2. Backend: run request 1.1.0, RunInput, interactive runs, ConsoleSocket — d32776c
- [x] 3. Frontend live console, trace 1.6.0, read-input example, AC-19 browser test — 029e08c
- [x] 4. Guides and memory (this commit)

## Git

- Branch `feat/part-8g-live-console` on main e24112a (8c-8f merged).
- PR (manual, `gh` unavailable):
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-8g-live-console?expand=1

## Verification (2026-10-20, Claude)

- Root reactor `clean verify` with `-Dcodeviz.docker.live=true` and
  `-Dcodeviz.docker.executable=<docker path>`: **161 tests, 0 failures/skips**
  (tracer 69, backend 92). Log `.tools/part-8g-verify.log`. No owned container left.
- An earlier verify failed only because Docker Desktop was not running
  (`RUNNER_UNAVAILABLE`); after starting it every suite passed.
- Contracts 61; frontend 20 reducer tests, build, 9 Playwright tests (real backend + Docker).

## Decisions in this task

- Interactive = request `inputMode: live` AND source refers to `System.in` (analyzer).
  Interactive runs use `docker exec -i` and a 120 s runtime deadline; others close stdin.
- Scanner is a library class: reads are replayed by the trusted builder on a Scanner
  over the delivered transcript, so recorded results are verified.
- Part 4r input bounds enforced in `RunInput`; the 30 s input-wait deadline is not
  enforced (no verified evidence that a read blocks).
- The first WebSocket connection controls input; its loss while executing cancels
  the run (`INPUT_DISCONNECTED`). Origins limited to localhost/127.0.0.1.
- Frontend opens the socket from the submit handler, never an effect or state
  updater, because StrictMode would create a second connection.

## Resources

- No commands running; no containers. Docker Desktop was started by Claude on 2026-10-20.

## Next action

Wait for review/merge of 8g. Propose 8h (exceptions) for approval.
