# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-23 (Asia/Saigon)
- Owner: Claude Code
- Status: 11a step motion on `feat/part-11a-motion` (on main aacf98c, which has 6g). Frontend only.
- Milestone: V1 complete (36/36 ACs, V1 support matrix agreed 2026-10-23). Public deployment on hold
  (user: "Hold this step, run local first"). Now UI improvements (Part 11).

## Approved roadmap (2026-10-23)

User asked for a visualizer "with action, not just blink color". Proposed 11a value flights,
write fades, comparison badges, Play with speed, reduced motion; 11b index pointer arrows; 11c call
and return motion; 11d structure/tree/heap motion. User: "ok", then "implement 11a and I accept
framer-motion if you think it's necessary". Decision: not necessary; Web Animations API, no new
dependency. Ask before starting 11b.

## Task 11a (specs/motion.md)

- [x] `views/motion.ts`: pure plan `motionFor(event, before, after, forward)` + DOM runner
      (`playMotion`, `clearMotion`); data attributes on variable rows and array cells
- [x] Controls: Speed (0.5-4x, step every 900 ms / speed), Motion toggle (default from
      prefers-reduced-motion, remembered in localStorage)
- [x] Fix found by screenshot: fast stepping left values hidden by unfinished landing fades;
      clearMotion now cancels page animations too
- [x] tests: motion.test.ts (plans), browser test "steps move ..." (badge, chip, motion off, speed)
- [x] verified 2026-10-23: frontend 46 unit, build, 18 Playwright passed (`.tools/pw-11a.log`); Java unchanged
- [x] committed and pushed; local app restarted for the user

## Task 11b index arrows (user: "it's better. please continue")

- [x] `trace/pointers.ts`: arrow only after a recorded access whose code is literally `…[name]`, accesses
      up to the cursor only; measured placement under cells, slide with Motion on; outside values as notes
- [x] tests: pointers.test.ts, browser test "an index variable becomes an arrow ..."; frontend 47 unit,
      build, 19 Playwright passed (`.tools/pw-11b.log`); Java unchanged. Branch `feat/part-11b-pointers`
      stacked on 11a.

## Task 11c call and return motion + user-reported fixes (user: "ok, and fix the bug ...")

- [x] CALL: frame card slides in, arguments fly into parameters; RETURN: result flies to the caller frame
- [x] fix: "Load example" picker shows the chosen example (was hard-wired to "")
- [x] fix: playback icons are one SVG set (|◀◀ |◀ ▶ ▶| ▶▶|) instead of mixed glyphs
- [x] tests: motion.test.ts (11c), browser test "the chosen example stays selected ..."; frontend 48 unit,
      build, 20 Playwright passed (`.tools/pw-11c.log`). Branch `feat/part-11c-calls` stacked on 11b.

## Notes

- The local app (backend jar + Vite dev server) was stopped to run Playwright (both use 8080/5173);
  restart it after verification: `java -jar backend/target/backend-0.0.1-SNAPSHOT.jar` and
  `npm run dev -- --host 127.0.0.1 --port 5173` in frontend.

## Next action

11c reported. User asked to continue with 11d; not started (session limit). Start 11d from
`feat/part-11c-calls` (or main once 11a-11c are merged). Plan: in `views/motion.ts` add plans for
LIBRARY steps from their own `after` (push/pop/offer/poll: element enters/leaves the collection box;
map put: entry appears or value pulses; tree node appears; heap swap = its two recorded writes along
the edge in HeapPanel); add data attributes to LibraryPanel, TreePanel and HeapPanel items; unit tests
in motion.test.ts (library fixture); one browser test; run Playwright in the background (stop the
local app first: it uses 8080/5173), then restart the local app for the user.
