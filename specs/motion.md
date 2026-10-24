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

## Structures, objects, trees and heaps (Part 11d, 2026-10-23)

| Event | Forward | Backward |
| --- | --- | --- |
| LIBRARY with `after`: one item more | the new item pops into place where the shown contents first differ, and its value flies in from the code (`push`, `add`, `offer`, `put` of a new key, `append`) | the value flies from the box back to the code |
| LIBRARY with `after`: one item fewer | the removed value flies out of the box to the code (`pop`, `poll`, `remove`) | the item pops back in |
| LIBRARY with `after`: same size | each changed item pulses (`set`, `put` of an existing key); more than six changes pulse the whole box (a sort) | the same |
| LIBRARY without `after` (a read) | nothing | nothing |
| ALLOCATE | the new array, collection or object pops into place, and so does its tree node | nothing |
| FIELD_WRITE | the value flies into the field in the object graph, and the object's tree node pulses | the value flies back to the code |
| ARRAY_WRITE | also lands on the matching node of a heap view of that array | the same, reversed |

Items are compared by their shown text before and after the call, in the order the panel
draws them by recorded index. This is a difference of the two recorded states; nothing is
inferred about the operation. A Stack, drawn top first, still uses each element's recorded
index.

Elements carry `data-collection`, `data-item`, `data-box` (arrays, collections, objects) and
`data-heap`; tree nodes already had `data-tree-node`, and heap nodes `data-heap-index`.

Part 11 (motion) is complete.

## Grids and returning frames (Part 12d, 2026-10-23, user: "implement ... Visual")

- **Grid arrows.** The same rule as 11b: an access written literally `…[name]`, up to the cursor.
  `g[i][j]` records a read of `g` written `g[i]` and a read of row `g[i]` written `g[i][j]`.
  - `i` is drawn as `i ▶` to the left of row `i`, measured from the rendered rows; it slides
    between rows while Motion is on. A value outside the rows (including the row count, after
    the loop) is a note by the grid's name.
  - `j` is drawn as `▲ j` under the cells of the one row the program indexed with `j` most
    recently (by recorded step), not under every row it ever indexed.
  - This works at every depth of nesting; a row shared by two indices shows its arrows once.
- **Returning frames.** One step forward that ends frames (a return, or an exception unwinding
  them) draws each ended frame, with the values it ended with, at the top of the call stack,
  marked `returned`; it slides away and fades in about half a second. It is drawn only while
  Motion is on and only for that one step; stepping back, or jumping, draws nothing leaving.
- **Library changes (12a, 12c).** A static call that changes an array (`Arrays.fill`,
  `Arrays.sort`) pulses its changed cells, and an iterator's `remove()` moves like a list's
  `remove`: the removed value flies out of the list.

## Arrays, DP tables, strings and node tags (Part 18, 2026-10-24, user: "ok")

Presentation only, from recorded facts at the cursor; nothing is inferred about the algorithm.

- **18a, computed from.** A step that writes an array cell draws arrows from the cells it read to
  the cell it wrote: the recorded reads of the same frame inside the write's own code
  (`dp[i][j] = dp[i - 1][j] + dp[i][j - 1]`), going back until the frame ran other code. Reads in
  calls inside the statement run in other frames and are passed over; fetching a row of a grid
  (`dp[i - 1]`) is not a source, and neither is the written cell itself. Arrows are drawn when
  source and target are in the same array box (`trace/dataflow.ts`, `FlowLayer`).
- **18a, heat colours.** A number grid can be switched (per variable name, "heat colours") to
  colour each cell by its value on one scale for the whole grid at this step.
- **18b, bars.** A number array can be switched ("bars") to bars whose height is the value
  (negative values below in red); bars change height as values are written. Index arrows, motion
  and highlights work as for cells.
- **18c, a range.** When exactly two index arrows point at cells of one array (`left` and `right`,
  `lo` and `hi` when the code indexes with them), the cells between them are shaded.
- **18d, variable tags.** Variables (not field paths) referring to an object, tree node or list
  node are tags on it (`cur`, `head`); a tag pulses on the step that declared or assigned it.
- **18e, strings.** A string named by a variable (up to 60 characters) is drawn as character boxes
  with indices; `s.charAt(i)`, written literally with a variable, makes `i` an arrow under them,
  by the 11b rule. Longer strings stay text.