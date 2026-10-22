# Step motion (Part 11a)

Status: approved by the user on 2026-10-23 ("ok" to 11a, plain CSS or `framer-motion`, either
accepted). Implemented with the browser's Web Animations API: no new dependency was needed.

## Rule

A step's motion comes only from that step's own recorded event: the value it stored, read or
computed. It never comes from guesses about the program, such as detecting a swap or an
algorithm. A swap is shown as its two recorded writes, one step after the other. Motion is
presentation only: it never changes the state shown when it ends, and it never delays the
playback cursor.

## What moves

| Event | Forward | Backward (one step back) |
| --- | --- | --- |
| DECLARE, CATCH | the value flies from the highlighted code into the new variable | it flies back to the code |
| ASSIGN, UPDATE | the new value flies into the variable; the old value drops away | the value flies back to the code |
| ARRAY_WRITE | the value flies into the cell; the old value drops away | the value flies back to the code |
| ARRAY_READ | the value rises from its cell to the code | nothing (a read changes nothing) |
| EXPRESSION | a badge at the code: `4 > 2 ✓`, `3 + 4 = 7` | nothing |

- The destination's value fades in as the chip lands.
- A motion runs only when both its start and its end are visible on the page. If a cell is
  scrolled away or a variable is gone, nothing is animated.
- Jumps of more than one step (restart, end, slider, a new run) show the new state at once.
- A new step cancels everything still running, so fast stepping never leaves a value hidden.

## Controls

- **Speed** (0.5×, 1×, 2×, 4×): Play advances one step every 900 ms ÷ speed. Each motion
  lasts at most 650 ms, or 70% of a step, whichever is shorter.
- **Motion** on or off. The default follows the system's reduced-motion setting, and an
  explicit choice is remembered in this browser. Off shows no chips or badges.

## Code

- `frontend/src/views/motion.ts`: `motionFor(event, before, after, forward)` is a pure plan
  (unit-tested in `motion.test.ts`); `playMotion` measures the page and animates.
- `frontend/src/App.tsx`: one step forward or back plays that step's plan.
- Elements carry `data-binding` (variable rows) and `data-object`/`data-index` (array cells)
  for measuring.

## Later parts (approved as a roadmap, not yet built)

- **11b:** index pointer arrows under arrays.
- **11c:** call and return motion (frames slide, return values fly back to the caller).
- **11d:** structure, tree and heap motion.
