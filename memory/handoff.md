# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-22 (Asia/Saigon)
- Owner: Claude Code
- Status: 10d in progress. Revision 2 approved 2026-10-22 ("1. I approve 2. do as your recommendation
  3. do what you want 4. I approve"). 10d.1 implemented; next 10d.2.
- Milestone: V1.1 (Part 10). 10a, 10b, 10c done and awaiting merge. Full isolation (6g) deferred.

## Approved roadmap (2026-10-20, "I approve")

9.0-9d done; V1.1: 10a test inputs, 10b custom mappings, 10c view rankings (all done, awaiting review),
10d broader problem suite. User 2026-10-22: "please continue doing part 10d" -> 10d started.
Requirements 6.5 require the concrete operations and representative programs to be agreed before
implementation, so 10d.0 is a draft: [specs/problem-suite.md](../specs/problem-suite.md).

## Current task: 10d broader problem suite (AC-36)

- [x] 10d.0 probe of 32 candidate programs through the real tracer; draft suite + gaps G1-G5 (this commit)
- [x] user review of revision 1 (2026-10-22): include lambdas and streams; entrySet yes; G5 only reverseOrder
- [x] revision 2: G6 lambdas/method references, G7 sequential streams, F1-F11 programs, 5 increments;
      probe found bug F1 (List.forEach with a lambda makes the whole program output-only, TRACE_MAPPING)
- [x] user review of revision 2: approved (lambda frames via the library call; sort comparators in,
      collection-held comparators out)
- [x] 10d.1 G1 println(Object) for library values, G3 toCharArray/split/Arrays.toString/equals,
      G4 Arrays.sort/Collections.sort as one step, G5 reverse order (PQ/TreeSet/TreeMap), bug F1.
      Recorder marks sorted collections' comparator ("/r", "/c"); args recorded before the call;
      static call's changed argument in `after` (schema already allowed it, no version bump);
      3 tracer tests, 1 frontend test, fixtures array-utilities.json and reverse-order.json.
- [ ] 10d.2 G2 enhanced for over List/Set/Deque, keySet/values/entrySet (trace contract 1.9.0)
- [ ] 10d.3 G6 lambdas (callback frames via the library call, replay with recorded callback results)
- [ ] 10d.4 G7 sequential streams (contract 1.10.0 with 10d.3)
- [ ] 10d.5 ProblemSuiteTest (zero uncaptured statements), three frontend examples, browser test, AC-36 Done

Probe tool: `runner/tracer/src/test/java/codeviz/tracer/ProbeTest.java` is a local, **uncommitted**
scratch test (prints a report). Run with `-pl runner/tracer -Dtest=ProbeTest`; output in `.tools/probe.log`.
It becomes the basis of ProblemSuiteTest in 10d.5; do not commit it as is.

## Git

- Branch `feat/part-10d-problem-suite` stacked on `feat/part-10c-view-rankings` (10a -> 10b -> 10c -> 10d).
- PRs (manual, `gh` unavailable), merge in order:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-10a-test-inputs?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-10a-test-inputs...feat/part-10b-custom-mappings?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-10b-custom-mappings...feat/part-10c-view-rankings?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-10c-view-rankings...feat/part-10d-problem-suite?expand=1

## Verification

- Last full Java live verify: 2026-10-21 (10a), 175 tests. Frontend after 10c: 41 unit, build, 16 Playwright.
- 10d.1 (2026-10-22, Claude): root `clean verify` with live Docker: tracer 82 (incl. the uncommitted
  ProbeTest: 81 committed), backend 97, 0 failures. Log `.tools/part-10d1-verify.log`. First attempt
  failed one live test that used Arrays.sort as its uncaptured example (now Arrays.fill). Contracts 62;
  frontend 42 unit, build, 16 Playwright passed. "Helper and library calls" example now uses Arrays.fill.
- 10d.0: the probe ran on the host (tracer module only): all 32 programs compiled, matched the
  original stdout and gave capture complete; 18 have uncaptured statements. No product code changed.

## Next action

Wait for the user's review of specs/problem-suite.md revision 2; then implement 10d.1.
