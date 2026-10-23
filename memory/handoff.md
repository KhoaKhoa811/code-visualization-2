# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-23 (Asia/Saigon)
- Owner: Claude Code
- Status: Part 12 done on `feat/part-12-library-visual` (from main 651d37f, which has 11a-11d).
  Pushed; awaiting the user's review and merge.
- Milestone: V1 complete (36/36 ACs, V1 support matrix agreed). Public deployment on hold
  (user: "deployment can hold on").

## Part 12 roadmap (approved 2026-10-23: "I only need you to implement more library calls and Visual. after that update memory")

- [x] 12a `Arrays.fill` (whole or range; primitive arrays, String arrays with a string or null), `Math.pow`
- [x] 12b varargs with written-out arguments: `String.format` (boxed values, strings, null), `Stream.of`;
      plan format 11 (`varargs`); recorder `libVa`; builder repacks; format replayed with `Locale.US` and
      `%n` = "\n"; the traced JVM now runs with `-Duser.language=en -Duser.country=US`
- [x] 12c iterators used directly: `iterator()` of a list/set/deque, `hasNext`/`next`/`remove`;
      trace 1.11.0 `iterator` value (label, objectId); twins over mirrors; `mayAdvanceIterators` regions
      drop the twins; contract example `valid/iterators.json`
- [x] 12d grid index arrows (`i ▶` beside rows, `▲ j` under the row last indexed; writes are now
      matched by their target), returning-frame ghost (`frame-leaving`), array/iterator library motion;
      new example "Iterator and format"
- [x] specs: library.md, motion.md, v1-support-matrix.md, trace-format.md, contracts/trace/README.md
- [x] memory: current-state, next-steps, archive/history-current

## Verification

- Root `clean verify` with live Docker: tracer 139, backend 102 passed (`.tools/verify-12.log`).
- Contract `npm test`: 66 passed. Frontend: 53 unit, build OK.
- Playwright: first full run 23 passed, 1 failed (the new grid test: writes were not matched,
  fixed in pointers.ts); final full rerun 24 passed (`.tools/pw-12.log`); no run containers left.

## Notes

- Old tests that used `Arrays.fill`/`String.format`/`Stream.of` as uncaptured examples now use
  `System.arraycopy`, the `Locale` overload, or a whole array passed to varargs.
- The local app was stopped for verify/Playwright; restart with
  `java -jar backend/target/backend-0.0.1-SNAPSHOT.jar` and `npm run dev -- --host 127.0.0.1 --port 5173`.

## Next action

Report Part 12 with the PR link; wait for the user's merge and next request.
