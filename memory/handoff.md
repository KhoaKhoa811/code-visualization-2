# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-18 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: trace engine (Part 7); Milestone 0, full isolation and V1 incomplete

## Last approved task: Part 7c container tracing (complete)

User: "I approve" (2026-10-18) for 7c: run the tracer inside the 6d/6e container
lifecycle. Guide: [backend/TRACED_RUNS.md](../backend/TRACED_RUNS.md).

- [x] 1. Tracer Prepare CLI, plan file, stop causes, torn records — 1a4387c
- [x] 2. Shaded tracer-runtime.jar, root reactor pom, backend dependency — 99a83d2
- [x] 3. Stdin staging, bounded reads, TracedRunLifecycle, unit tests — d1682ad
- [x] 4. Live container tests; recorder flushes every 100 ms — 239a39a
- [x] 5. Docs, spec section, memory (this commit)

## Git

- Branch `feat/part-7c-container-tracing`, stacked on `feat/part-7b-trace-producer`
  (681e4e0, PR not merged yet; main 528d45d). Pushed with this commit.
- PRs (manual, `gh` unavailable): open 7b first, then 7c against the 7b branch:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-7b-trace-producer...feat/part-7c-container-tracing?expand=1

## Verification (2026-10-18, Claude)

- Root reactor `clean verify` with live Docker (command in TRACED_RUNS.md):
  **98 tests, 0 failures/errors/skips** (tracer 33; backend 65 incl. all 6c-6e
  live tests and 7 new traced live tests). Log `.tools/part-7c-verify.log`.
- Every traced live trace passed `contracts/trace/check.cjs`; no owned container left.
- Build note: backend now depends on runner/tracer; build from the root pom.

## Decisions in this task

- Stage files via `docker exec -i` stdin (Windows 32K command-line cap).
- Read plan.json before runtime; reject plans for other source (SHA-256).
- After runtime, `kill -9 -1` as the run user, then read raw records, then stop
  (tmpfs is lost on stop). Reads never quarantine.
- Recorder flushes every 100 ms (first live timeout run lost its whole prefix).
- Trace built only after confirmed termination; stop causes always give partial.

## Resources

- No commands running; no containers.

## Next action

Wait for review/merge of 7b and 7c. Propose 7d (minimal run API: submit, poll,
cancel, fetch trace, through the run supervisor) for approval.
