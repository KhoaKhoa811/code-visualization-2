# Step actions (Part 22)

Status: approved by the user on 2026-10-25 ("very good. please implement all of them. but make
sure we don't overload actions in one visualization"). Frontend only; trace contract unchanged
(one tracer test program changed to keep the hash index on the array access). Extends the flow
arrows of Part 21 ([flow-arrows.md](flow-arrows.md)); `FlowOverlay` became `ActionOverlay`.

## Actions

Each action comes from the step's own recorded event; a code jump also looks at the step just
before it. Nothing about later steps is used.

| Action | When | Drawn |
| --- | --- | --- |
| swap | Part 21 swap rule | pink double arrow "swap" between the cells (and heap nodes) |
| rewire | a field write whose old and new values are objects or null and differ | red crossed arrow to the old target, green arrow labelled with the field to the new one |
| move | a variable assignment from one object to another (`cur = cur.next`) | violet arrow between the two nodes labelled with the variable |
| compare | a `< <= > >= == !=` expression whose sides are two recorded reads, or a read and a variable | bracket between the two places with `x > y ✓` or `✗`, green or red; the motion badge at the code is dropped |
| return | a write whose statement called a method that returned a value | arrow from that call's call-tree node to the target, "returned v" |
| stack | push/offer/add (one argument) or pop/poll/remove (no argument, a result) on a collection | arrow into or out of the box's top, "push 3" or "pop → 3" |
| slot | an array access whose index has `%` (`buckets[k % 4]`) | arrow from the key variable to the cell, "k % 4 = 1" |
| flow | the Part 21 value arrows that are left | amber arrows; all of them count as one action |
| ghost | a write that replaced a different primitive value | the old value struck through beside the target |
| code jump | the previous step was in the same frame, on a later line (loop back) or more than one code line earlier (lines skipped) | arrow in the editor's left margin, "back" or "skip" |

## Budget

- At most 3 actions per step, chosen by priority: swap, rewire, move, compare, return, stack,
  slot, flow. Plus at most one ghost, not shown with a rewire or a move.
- An action that already says where the value went removes the value arrows aimed at the same
  target (rewire: the field; move: the variable; stack: the collection).
- Code jumps are drawn in the editor, apart from the state picture.
- An "Arrows" switch next to Motion turns all actions and jumps off; it is remembered
  (`codeviz.arrows`).

## Code and tests

- `frontend/src/trace/actions.ts` (`actionsAt`, `jumpAt`), `views/ActionOverlay.tsx`,
  `editor/JavaEditor.tsx` (`jump`), `views/motion.ts` (`node` and `callNode` anchors).
- Unit: `actions.test.ts` (the budget on every fixture; compare and swap on bubble sort; rewire,
  move, return, stack, slot; a back jump).
- Browser: the Part 21 test (swap, ghost, a cell comparison bracket without a value arrow) and
  "(22)" (rewire on Mixed structures, back jump, Arrows off, a queue offer on Graph BFS).
