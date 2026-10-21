# Frontend design

Status: written in Part 9a (approved by the user on 2026-10-20 with the Part 9/10
roadmap, see [V1 scope](v1-scope.md)). It records the confirmed layout of the
[requirements](../requirements/PROJECT_REQUIREMENTS.md) (section 3, 6.3) and the
interaction rules implemented so far. Implementation guide: [frontend](../frontend/README.md).

## Page layout

Dark theme. Toolbar on top (example picker, Run, Cancel, run status). Below it:

- **Left: visualization.** Playback controls (restart, back, play/pause, forward,
  end, slider, step counter), the run summary (execution and capture status,
  diagnostics), the current step's description, then the panel grid.
- **Upper right: Java editor** (Monaco). The current step's source range is
  highlighted. The editor scrolls it into view, from its line start when the whole
  range fits (Part 8i).
- **Lower right: console.** Live output and the input line while a run executes
  (Part 8g); the recorded output after it finishes. "Use test input" (Part 10a) gives the
  next run a fixed standard input instead of the live input line.

## Panel grid (Part 9a)

Panels appear only for structures present at the playback position (AC-22). All
of them read the same cursor, as does the source highlight; moving the cursor never
reruns Java.

| Panel | Shows |
| --- | --- |
| Variables / Call stack | one block per active frame, innermost first, with its bindings |
| Collections | library lists, stacks, deques, queues, sets, priority queues, maps, string builders |
| Objects | the factual object graph: boxes and reference arrows, by reference distance |
| Arrays | named or reachable arrays; arrays of arrays as rows |
| Strings | strings that a variable refers to |
| Trees | confirmed binary-tree views of user objects ([tree view](tree-view.md), 9b) |
| Heaps | arrays confirmed as heaps: strip and tree of one array ([heap view](heap-view.md), 9c) |

The grid fills columns at least 280 px wide. The object graph and the arrays panel span
the full width. Above the grid, cards suggest tree views and list confirmed tree and heap
views (Show as tree, Choose fields, Not a tree, Show an array as a heap…, Change, Remove).

## Layout interactions

Layout is presentation only. It never changes values, references, identities or the
playback cursor (AC-18, AC-23).

- **Reorder panels:** drag a panel's title onto another panel; the dragged panel takes
  its place.
- **Move diagram elements:** drag an object box, an array (as one group, rows
  included) or a collection. Reference arrows follow the moved box.
- **Deque view (Part 9d):** an `ArrayDeque` or `LinkedList` has a Deque (or List) / Stack / Queue
  switch. Stack draws the first element as the top, vertically (push, pop and peek work
  there). Queue marks the first element as the front and the last as the back. The choice
  belongs to the variable name, so it lasts across steps and runs. It is never made
  automatically.
- **Reset layout** restores the default panel order, positions and view choices.
- **Persistence:** panel order lasts for the page session, across runs. Element
  positions last for one run, because element identities belong to one run and are
  never reused (requirements 6.1). Nothing is stored after the page closes. Saving
  layouts is not required for V1.

Tree nodes (9b) and heap nodes (9c) drag too.
