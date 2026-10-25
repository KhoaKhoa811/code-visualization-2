# Sliding window (Part 25)

Status: approved by the user on 2026-10-25 ("I want you to visualization for sliding window").
Frontend only (plus one tracer test program for the fixture); trace contract unchanged.

## Rule

Only recorded steps up to the cursor are used.

- **Enter:** a step that adds one cell of an array or a string into an aggregate:
  `sum += a[r]`, `sum = sum + a[r]`, `freq[s.charAt(r) - 'a']++`, `seen.add(c)`,
  `deque.offer(a[r])`, `count.put(c, count.get(c) + 1)`, `count.merge(c, 1, Integer::sum)`.
- **Leave:** a step that takes one back out: `sum -= a[l]`, `freq[..]--`, `seen.remove(s.charAt(l))`,
  `poll`/`pop`/`remove…`, `put`/`merge` with `- 1` or `-1`.
- The cell is the one recorded read of the statement (an array cell or `s.charAt(i)`), not the
  aggregate's own cell. With no read there, a variable on the value side (or in a counter's index)
  that was last set from exactly one cell stands for it (`char c = s.charAt(hi); seen.add(c)`).
- Aggregates are told apart by the variable or object they change and the array they read.
- **Window:** the cells that entered and have not left. It is shown only after a cell has left
  (a total that only grows is not a window) and only while its cells are contiguous. Taking out a
  cell that never entered means it is not a window; taking out one that already left is ignored.

## Drawing

- A green box around cells lo..hi of the array or string, labelled with the aggregate and the
  size: `sum = 9 · size 3` (a variable with its value), `seen (2) · size 2` (a collection with its
  size), `count (3 keys)` (a map), `freq` (a counter array).
- The cell that entered at this step has a green + on its corner; the one that left a red −.
- The box slides with motion on. Index arrows, value arrows and swaps work as before.

## Code and tests

- `frontend/src/trace/window.ts` (`windowsAt`), `views/StatePanel.tsx` (`WindowLayer` in arrays
  and strings), `App.tsx`, `styles.css`; example "Sliding window".
- Fixture `sliding-window.json` from `TracerTest.slidingWindowProgramsAreTraced` (fixed size, grow
  and shrink, a set over a string, a counter array over a string).
- Unit: `window.test.ts` (the four windows, the + and − marks, no window in any other fixture).
- Browser: "a sliding window is a box over its cells ... (25)".
