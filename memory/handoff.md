# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-21 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: V1.1 (Part 10). Core V1 parts 9a-9d merged (PRs #97-#100). Full isolation (6g) deferred.

## Approved roadmap (2026-10-20, "I approve")

9.0-9d done; V1.1: 10a separate test inputs (done), 10b custom mappings (done), 10c view rankings,
10d broader problem suite. Order and AC mapping: [specs/v1-scope.md](../specs/v1-scope.md).
User (2026-10-21): "continue implement next part" -> 10a; "ok" -> 10b; "ok" -> 10c. 10c rules spec
(specs/visualization-rules.md, rank-1) is a DRAFT awaiting the user's review; implement only after approval.

## Last tasks: 10a separate test inputs, 10b custom mappings (complete)

- [x] Run request 1.2.0 `inputMode: provided` + `input {text, sha256}`; snapshot 1.1.0 `input {mode, sha256}`
- [x] Backend RunStdin (none/live/provided), RunInput.provided, admission codes, live + API tests
- [x] Frontend "Use test input", run summary association, input-changed warning, AC-35 browser test
- [x] specs/test-inputs.md, RUN_API.md, run-lifecycle.md, v1-scope AC-35 Done — 794bfc4
- [x] 10b list / array stack / circular queue mappings, revisions, AC-27 and AC-30 — b70de5c
- [x] memory (this commit)

## Git

- Branch `feat/part-10a-test-inputs` on main 9309231; `feat/part-10b-custom-mappings` stacked on it.
- PR (manual, `gh` unavailable):
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-10a-test-inputs?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-10a-test-inputs...feat/part-10b-custom-mappings?expand=1

## Verification (2026-10-21, Claude)

- Root reactor `clean verify` with live Docker: **175 tests, 0 failures/skips** (tracer 78, backend 97).
  Log `.tools/part-10a-verify.log`. First attempt failed only because Docker Desktop was stopped.
  No owned container left.
- Contracts 62; frontend 31 unit tests, build, 14 Playwright tests. One run had a single flaky
  "Monaco not visible" in the 8b test; the full rerun passed all 14.

## Decisions in this task

- Provided input uses the normal 5 s deadline (nobody waits); stdin attached only when the source
  refers to System.in. Bytes kept exactly (CR, no final LF, long lines chunked at 4 KiB).
- Snapshot live input hash is the delivered transcript, published only once finished.
- Editing test input after a run warns; playback stays valid (trace belongs to its input).
- 10b (frontend only, 35 unit + 15 browser tests): suggestions from field types only; queue empty/full
  without a size field is stated, not guessed; mapping revisions tied to source sha. The editor bundle
  has no find/replace, so the browser test pastes edited source via the clipboard.

## Next action

Wait for the user's review of specs/visualization-rules.md (branch feat/part-10c-view-rankings, stacked on 10b);
implement 10c only after approval. Also awaiting merge: 10a, 10b.
