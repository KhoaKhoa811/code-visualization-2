# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-24 (Asia/Saigon)
- Owner: Claude Code
- Status: Part 15 (complexity) on `feat/part-15-complexity`, stacked on `feat/part-14-graph`
  (Part 14 pushed, not merged yet). Awaiting the user's review and merge (14 first).
- Milestone: V1 complete. Public deployment on hold (user: "deployment can hold on").

## Part 15 (approved 2026-10-24: "ok" to 15a + 15b); spec: specs/complexity.md

User intent: "I don't need it to be exactly correct. I just want a result so that I can compare
those algos". Time complexity only; badge per method plus a ranking panel.

- [x] 15a `runner/tracer/.../Complexity.java`: estimate per method from javac trees (in the container,
      during prepare); Analysis.complexity; plan format 12; trace 1.12.0 optional `complexity`
      (schema `complexityEstimate`); fixtures bumped to 1.12.0
- [x] 15b frontend: editor badges (`time O(n²)`, hover reasons), Complexity panel (`complexity` panel id)
      ranking methods by growth with measured calls and inclusive steps (`trace/complexity.ts`)
- [x] tests: ComplexityTest (4), complexity.test.ts (2), layout test, browser test on example
      "Compare algorithms"
- [x] spec and memory

## Verification

- Tracer 143 passed (`.tools/tracer-15.log`), contract 66, frontend 64 unit.
- Root clean verify with live Docker: tracer 143, backend 102 (`.tools/verify-15.log`). Playwright 29 passed
  (`.tools/pw-15.log`); the first run failed only the new test: Monaco skips decorations on an empty range
  without `showIfCollapsed`, and renders spaces as non-breaking ones. No run containers left.

## Part 16 (user request 2026-10-24)

- [x] "New program (template)" first in the picker (`frontend/src/examples.ts`); browser test "(16)".
  Verified: 64 unit, build, Playwright tests 16 and 11c (the full suite last ran on Part 15: 29 passed).
  Branch `feat/part-16-template`, stacked on Part 15.

## Next action

Report Part 15 with the PR link (base on Part 14's branch until it merges); wait.
15c (growth check by re-running with bigger n) was proposed and is not approved.
