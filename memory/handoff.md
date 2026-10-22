# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-23 (Asia/Saigon)
- Owner: Claude Code
- Status: 10d in progress. 10d.0-10d.2 merged (PRs #101-#104 include 10a-10c and 10d.0-10d.2).
  10d.3 lambdas done on `feat/part-10d3-lambdas`, awaiting review. Next: 10d.4 streams.
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
- [ ] 10d.4 G7 sequential streams (reuse callbacks; stream values; terminal ops run the pipeline)
- [ ] 10d.5 ProblemSuiteTest (zero uncaptured statements), three frontend examples, browser test, AC-36 Done

Probe tool: `runner/tracer/src/test/java/codeviz/tracer/ProbeTest.java` is a local, **uncommitted**
scratch test (prints a report). Run with `-pl runner/tracer -Dtest=ProbeTest`; output in `.tools/probe.log`.
It becomes the basis of ProblemSuiteTest in 10d.5; do not commit it as is.

## Git

- Branch `feat/part-10d3-lambdas` on main efb198d.
- PR (manual, `gh` unavailable):
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-10d3-lambdas?expand=1

## Verification (10d.3, 2026-10-23, Claude)

- Root `clean verify` live Docker: tracer 84 (83 committed + ProbeTest), backend 97, 0 failures
  (`.tools/part-10d3-verify.log`). Contracts 64; frontend 44 unit, build, 16 Playwright passed.
- Probe: M5, F1, F3 now have no uncaptured statement; streams (F2, F4, F6, F9, F11) remain.

## Next action

Wait for review/merge of 10d.3, or continue with 10d.4 streams if the user asks.
