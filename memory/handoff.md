# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-24 (Asia/Saigon)
- Owner: Claude Code
- Status: Part 19 (structure detection for complicated code) on `feat/part-19-structures`, from main 1508259
  (Parts 14-18 merged). Awaiting the user's review and merge.
- Milestone: V1 complete. Public deployment on hold (user: "deployment can hold on").

## Part 19 roadmap (approved 2026-10-24: "1. it's right 2. show them automatically"); spec: specs/structure-detection.md

- [x] 19a fix: arrays of traced objects (`Node[]`) used primitive helpers in Instrumenter (ALOAD/ASTORE) ->
      JVM VerifyError; now every non-primitive element uses the reference helpers (commit af2338c)
- [x] 19b traced classes may have covered collection fields of traced objects (`List<Node>`, `Map<K, Node>`)
      and field initializers `= new ArrayList<>()` / `new Node(0, 0)` (Analyzer tracedClasses, simpleInitializer)
- [x] 19c `frontend/src/trace/detect.ts` rank-2 over rank-1: mirror pairs (doubly linked list), parent
      pointers, children fields (`children.ts`), chains (`chains.ts`), heap in a class and heap order from
      swap values, graphs of index lists; `reach.ts` (reachable objects, held-by)
- [x] 19d views: list view prev arrows and reachable nodes only, `NestedPanel` (trees with children, tables
      of chains), heap view with `owner`
- [x] 19e held-by tags (`map[3]`, `buckets[1]`, `cache.head`) in VarTags
- [x] 19f `StructuresCard` replaces SuggestedViews: hide/show, "Choose fields yourself" holds the old editors;
      App draws auto shapes after confirmed mappings; deque modes from detection
- [x] tests: TracerTest 19a/19b/fixture programs; detect.test.ts (4); browser 10c rewritten, 9b hide then
      confirm, 9a hides the auto views, new "(19)" mixed-structures test; example "Mixed structures"
- [x] spec, memory

## Verification

- Root clean verify with live Docker: tracer 151, backend 102 (`.tools/verify-19.log`). The first two runs
  failed on Docker infrastructure timeouts right after Docker Desktop started (different live tests each
  time); the leftover test containers were removed by name; the third run passed.
- Frontend 70 unit, build; full Playwright 33 passed (`.tools/pw-19.log`). Earlier runs: 32/33 (the 9a drag test,
  fixed by hiding the auto views there), then 24 failed after one run container hung in Docker and held the
  single run slot (removed by name; the mixed test then passed 3 times in a row). No run containers left.

## Next action

Report Part 19 with the PR link; wait for the merge and the next request.
