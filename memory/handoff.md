# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-22 (Asia/Saigon)
- Owner: Claude Code
- Status: 10d started; suite draft awaits the user's review. No code changed yet.
- Milestone: V1.1 (Part 10). 10a, 10b, 10c done and awaiting merge. Full isolation (6g) deferred.

## Approved roadmap (2026-10-20, "I approve")

9.0-9d done; V1.1: 10a test inputs, 10b custom mappings, 10c view rankings (all done, awaiting review),
10d broader problem suite. User 2026-10-22: "please continue doing part 10d" -> 10d started.
Requirements 6.5 require the concrete operations and representative programs to be agreed before
implementation, so 10d.0 is a draft: [specs/problem-suite.md](../specs/problem-suite.md).

## Current task: 10d broader problem suite (AC-36)

- [x] 10d.0 probe of 32 candidate programs through the real tracer; draft suite + gaps G1-G5 (this commit)
- [ ] user review of the draft (3 decisions listed at its end)
- [ ] 10d.1 G1 println(Object) for library values, G3 toCharArray/split/Arrays.toString/equals,
      G4 Arrays.sort/Collections.sort as one step, G5 PriorityQueue(Collections.reverseOrder())
- [ ] 10d.2 G2 enhanced for over List/Set/Deque, keySet/values/entrySet (trace contract 1.9.0)
- [ ] 10d.3 ProblemSuiteTest (zero uncaptured statements), two frontend examples, browser test, AC-36 Done

Probe tool: `runner/tracer/src/test/java/codeviz/tracer/ProbeTest.java` is a local, **uncommitted**
scratch test (prints a report). Run with `-pl runner/tracer -Dtest=ProbeTest`; output in `.tools/probe.log`.
It becomes the basis of ProblemSuiteTest in 10d.3; do not commit it as is.

## Git

- Branch `feat/part-10d-problem-suite` stacked on `feat/part-10c-view-rankings` (10a -> 10b -> 10c -> 10d).
- PRs (manual, `gh` unavailable), merge in order:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-10a-test-inputs?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-10a-test-inputs...feat/part-10b-custom-mappings?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-10b-custom-mappings...feat/part-10c-view-rankings?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-10c-view-rankings...feat/part-10d-problem-suite?expand=1

## Verification

- Last full Java live verify: 2026-10-21 (10a), 175 tests. Frontend after 10c: 41 unit, build, 16 Playwright.
- 10d.0: the probe ran on the host (tracer module only): all 32 programs compiled, matched the
  original stdout and gave capture complete; 18 have uncaptured statements. No product code changed.

## Next action

Wait for the user's review of specs/problem-suite.md; then implement 10d.1.
