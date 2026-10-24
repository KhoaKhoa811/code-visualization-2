# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-24 (Asia/Saigon)
- Owner: Claude Code
- Status: Part 17 (backtracking fixes) done on `feat/part-17-backtracking`. Branch stack, none merged yet:
  main <- 14 graph <- 15 complexity <- 16 template <- 17 backtracking. Merge in that order.
- Milestone: V1 complete. Public deployment on hold (user: "deployment can hold on").

## Part 17 (user: "please review the code to see if we handle backtracking yet?", then "please fix both")

Review (a probe of four programs): permutations, N-queens and parentheses traced completely; subsets
with `res.add(new ArrayList<>(path))` went partial (copy constructors uncovered); the complexity of two
separate recursing `if`s showed O(n²) instead of O(2ⁿ).

- [x] copy constructors of ArrayList, LinkedList, ArrayDeque, HashSet, LinkedHashSet, TreeSet (from a
      covered collection) and HashMap, LinkedHashMap, TreeMap (from a covered map): Analyzer `COPYABLE`,
      site operator "copy", Instrumenter spills the source, Recorder `libCopy`, TraceBuilder `checkCopy`
      against the JDK's copy of the source mirror. PriorityQueue copies stay uncaptured.
- [x] Complexity: self-calls counted per statement (`count`): sequences add, if/else, ?: and switch cases
      are alternatives
- [x] tests: TracerTest `backtrackingProgramsAreTracedIncludingCopiesOfThePath`, ComplexityTest (gen, pick),
      ProblemSuiteTest B1-B4
- [x] specs: library.md, complexity.md, problem-suite.md; memory

## Earlier on the stack

- Part 15 (complexity estimates, specs/complexity.md): verified tracer 143, backend 102, Playwright 29.
  15c (growth check by re-running with bigger n) was proposed and is not approved.
- Part 16 ("New program (template)" in the picker): verified 64 unit, build, Playwright tests 16 and 11c.

## Verification (Part 17)

- Root clean verify with live Docker: tracer 148, backend 102 (`.tools/verify-17.log`).
- Frontend 64 unit, build, Playwright 30 passed (`.tools/pw-17.log`); no run containers left.

## Next action

Report Part 17 with the PR link; wait for merges and the next request.
