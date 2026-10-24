# Flow arrows (Part 21)

Status: approved by the user on 2026-10-25 ("I find the arrow you implemented in dp visualization is
very good. Can you implement it for all other visualizations. For example, there will be an arrow
when there are swap. The rest of use case, please review and implement them all"). Frontend only;
trace contract unchanged. Replaces the per-array "computed from" arrows of Part 18a; the DP table
(Part 20) keeps its own arrows.

## Rule

At each step one overlay over all panels draws where the value the step stored came from. Only
recorded facts are used:

- **Reads:** the recorded reads of the step's own frame inside the value side of its statement:
  array cells (`a[j]`), object fields (`cur.next`), and values taken out of a string or a
  collection (`s.charAt(i)` from that character box, `list.get(i)` from that item, `poll`, `pop`,
  `peek` from the collection). Reads by calls inside the statement run in other frames and are
  passed over.
- **Variables:** variables written literally on the value side (`t` in `a[j + 1] = t`). Not
  sources: receivers (`cur` in `cur.next`), array names and anything inside `[ ]` (indexes),
  method names, literals and the variable being assigned.
- **Value side:** after the assignment operator (`=`, `+=`, ...), or inside the parentheses of
  the last call (`list.add(x)`). Reads before it are the target's own receiver or index
  (`n.prev` in `n.prev.next = n.next`) and are not sources.

| Step | Arrow to |
| --- | --- |
| `a[i] = ...` | the cell |
| `int x = ...`, `x = ...` | the variable |
| `p.f = ...` | the field in the object graph |
| `list.add(x)`, `stack.push(x)`, `map.put(k, v)`, `list.set(i, x)` | the collection |
| a call `f(a, b)` | each parameter of the new frame, from its argument's reads and variables |

## Swaps

A write completes a **swap** when an earlier write to the same array, within the 4 steps before,
at another index (with no write to either cell between), stored this cell's old value, and this
write stores the other cell's old value (the two values differ). It is read from the recorded
`previous` and `value` of the two writes, so a swap through a temporary (`t = a[i]; a[i] = a[j];
a[j] = t`) and one inside a helper both count. It is drawn as a double arrow labelled **swap**
between the two cells, and between the two nodes of a heap view of that array.

## Drawing

- Arrows are placed from the measured elements after layout, over every panel, and follow
  dragging. An end that is not drawn (a hidden view, a cell outside the panel) draws no arrow.
- Cells in one row are joined by an arc over their tops; other arrows are gently curved.
- Value arrows are amber, swaps pink with heads at both ends.

## Code and tests

- `frontend/src/trace/flows.ts` (`flowsAt`, `swapOf`, `plainVariables`), `views/FlowOverlay.tsx`,
  `views/motion.ts` (`elementOf`, shared anchors).
- Unit: `flows.test.ts` (variables, the swap through a temporary in bubble sort, a field write
  that reads only its value side, a value polled from a queue, arguments into parameters).
- Browser: "flow arrows: a swap is a double arrow ... (21)" on Bubble sort; the Part 18 test counts
  the DP write's two arrows on the overlay.
