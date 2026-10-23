# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-23 (Asia/Saigon)
- Owner: Claude Code
- Status: 11d structure motion on `feat/part-11d-structures` (on main 77ca139, which has 11a-11c).
  Frontend only. Part 11 (motion) complete with 11d.
- Milestone: V1 complete (36/36 ACs, V1 support matrix agreed). Public deployment on hold
  (user: "Hold this step, run local first").

## Part 11 roadmap (approved 2026-10-23)

11a value flights, write fades, comparison badges, Play speed, Motion toggle (merged, PR #109);
11b index arrows (merged, #110); 11c call/return motion + example picker and icon fixes (merged, #111);
11d structure, object, tree and heap motion (this branch). No new dependency (Web Animations API).

## Task 11d (user: "Please review and continue part 11d"); spec: specs/motion.md

- [x] `views/motion.ts`: anchors item, box, field, treeNode, heapNode; kinds appear and pulse;
      LIBRARY plans from the recorded before/after contents (one more: pop in + value flies in; one
      fewer: value flies out of the box; same size: changed items pulse); ALLOCATE appear; FIELD_WRITE
      flight to the field + tree node pulse; ARRAY_WRITE also lands on the heap node
- [x] data attributes: data-collection, data-item (recorded index, also for a Stack drawn top first),
      data-box (arrays, collections, objects), data-heap
- [x] tests: motion.test.ts (library and objects fixtures), browser test "collections move ...";
      frontend 50 unit
- [x] verified: frontend 50 unit, build, 21 Playwright passed (`.tools/pw-11d.log`); Java unchanged
- [x] committed, pushed; local app restarted

## Notes

- Docker Desktop was not running at session start; started it.
- The local app must be stopped while Playwright runs (both use 8080/5173); restart after:
  `java -jar backend/target/backend-0.0.1-SNAPSHOT.jar` and `npm run dev -- --host 127.0.0.1 --port 5173`.

## Next action

Report 11d; ask what comes next (public deployment items stay on hold until the user asks).
