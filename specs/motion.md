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

## Index arrows (Part 11b, 2026-10-23, user: "it's better. please continue")

- **When an arrow appears:** a variable is drawn as an arrow `▲ j` under an array only after
  the program has indexed that array with it. The code text of a recorded read or write must
  be literally `…[j]`. Accesses with an expression (`a[j + 1]`) or a constant make no arrow.
- **No future facts:** only accesses up to the cursor count (`trace/pointers.ts`, unit-tested).
- **Where it sits:**
  - at the variable's current value, measured from the rendered cells (cells vary in width
    and wrap);
  - just past the last cell when the value equals the length (where `i < a.length` ends);
  - outside the array's range, it is a note next to the array name (`i = -1 (outside)`).
- **Movement:** arrows slide between cells while Motion is on, and jump when it is off.
- **Limits:** only one-dimensional arrays shown at the top level get arrows; rows of a grid
  do not.

## Calls and returns (Part 11c, 2026-10-23, user: "ok")

| Event | Forward | Backward |
| --- | --- | --- |
| CALL (a helper, or a lambda via its library call) | the new frame card slides onto the call stack; each argument flies from the call in the code into its parameter | the arguments fly back to the code |
| RETURN with a result | the result flies from the `return` in the code to the caller's frame card | it flies back to the code |

The next step (for example `int max = findMax(a)`) then carries the value into the variable,
as in 11a. A frame that returns simply leaves the stack; no picture of it is kept.

Fixed with 11c, from the user's testing:
- **Example picker:** "Load example" now shows the chosen example, until the code is edited
  away from it. Before, it always reset to "Load example…".
- **Playback icons:** they are one drawn SVG set instead of mixed text glyphs, following the
  media convention: |◀◀ restart, |◀ one step back, ▶ play, ▶| one step forward, ▶▶| end.

## Later parts (approved as a roadmap, not yet built)

- **11d:** structure, tree and heap motion.
