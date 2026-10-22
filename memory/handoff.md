# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-23 (Asia/Saigon)
- Owner: Claude Code
- Status: 10d in progress. 10d.0-10d.2 merged (PRs #101-#104 include 10a-10c and 10d.0-10d.2).
  10d.3 lambdas (`feat/part-10d3-lambdas`) and 10d.4 streams (`feat/part-10d4-streams`, stacked on it)
  done, awaiting review. 10d.5 (`feat/part-10d5-suite`, stacked on 10d.4) done: AC-36 Done. Part 10d complete.
- Milestone: V1.1 (Part 10). Full isolation (6g) deferred.

## Approved scope

[specs/problem-suite.md](../specs/problem-suite.md) revision 2, approved 2026-10-22 ("1. I approve
2. do as your recommendation 3. do what you want 4. I approve"): suite with lambdas and streams,
lambda frames via the library call, sort comparators covered, collection-held comparators not,
five increments. User 2026-10-23: "continue implement" after merging.

## Current task: 10d broader problem suite (AC-36)

- [x] 10d.0 probe + suite draft, revisions 1 and 2 approved
- [x] 10d.1 printing library values, toCharArray/split, library sorts, reverse order, F1 bug (merged)
- [x] 10d.2 for-each over collections and map views, entry objects, trace 1.9.0 (merged)
- [x] 10d.3 lambdas and method references (trace 1.10.0, plan format 10):
      LAMBDA sites; instrumenter learns lambda$main$N from the invokedynamic and orders captured
      params by the LVT; recorder f<site>/g<n> tokens, cbEnter/cbExit, callback entry `c<m> v<site>`;
      builder callback logs, Proxy stand-ins replaying recorded results; frontend `function` values,
      frames `via`. JDK method refs only with value results (String::toUpperCase not covered).
- [x] 10d.4 G7 sequential streams: s<n> tokens for streams/optionals, g<n> for collectors, twins replayed;
      terminal ops run callbacks via their site (lazy order shown); collected results adopt the twin
      after a contents check (nested lists too); Library.Str is a CharSequence (joining); Stream.toList.
      Value kinds `stream`, `optional` added to 1.10.0 (not yet merged). Stream.of (varargs) left out.
- [x] 10d.5 ProblemSuiteTest: 51 programs (complete capture, zero uncaptured, original-matching, expected
      output, schema check, plan round trip) + out-of-scope check; "text" + list concatenation (user "ok");
      examples Word count (map), Anagram check, Stream pipeline; browser test steps into a lambda via sum.

The probe became `ProblemSuiteTest` (committed); ProbeTest.java was deleted.

## Git

- Branch `feat/part-10d3-lambdas` on main efb198d; `feat/part-10d4-streams` and `feat/part-10d5-suite` stacked.
- PRs (manual, `gh` unavailable), merge in order:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-10d3-lambdas?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-10d3-lambdas...feat/part-10d4-streams?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-10d4-streams...feat/part-10d5-suite?expand=1

## Verification (10d.3, 2026-10-23, Claude)

- Root `clean verify` live Docker: tracer 84 (83 committed + ProbeTest), backend 97, 0 failures
  (`.tools/part-10d3-verify.log`). Contracts 64; frontend 44 unit, build, 16 Playwright passed.
- Probe: M5, F1, F3 now have no uncaptured statement; streams (F2, F4, F6, F9, F11) remain.

- 10d.4 (2026-10-23): live `clean verify` tracer 85 (84 committed + ProbeTest), backend 97, 0 failures
  (`.tools/part-10d4-verify.log`). Contracts 65; frontend 45 unit, build, 16 Playwright passed.
  Probe: every suite program, including all stream programs, has no uncaptured statement.

- 10d.5 (2026-10-23): live `clean verify` tracer 136 (incl. 52 ProblemSuiteTest), backend 97, 0 failures
  (`.tools/part-10d5-verify.log`). Frontend 45 unit, build, 17 Playwright passed (`.tools/pw-10d5.log`).
  Mistake: a Playwright run started inside a 600 s foreground command timed out; killing it orphaned a
  `codeviz-run-*` container that held run capacity, so the next suite runs failed (no run summary). Fixed by
  `docker rm -f` of that container; then 17 passed. Run Playwright with run_in_background and a long timeout.

## Next action

All V1 acceptance cases are Done except AC-20's input-wait limit, deferred to the 6g isolation review. Wait for review/merge of
10d.3, 10d.4, 10d.5 (in order); then ask what comes next.
