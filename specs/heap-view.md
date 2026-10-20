# Heap view of an array (Part 9c)

Status: approved by the user on 2026-10-20 ("ok", starting 9c of the roadmap in
[V1 scope](v1-scope.md)). Requirements: section 6 (Heap), 6.1 (an array used as a
heap), AC-14, AC-31, AC-34 (heap part). Frontend only.

## Confirming a heap

Nothing suggests a heap automatically. Calling an array a heap is the user's
interpretation, and evidence-based suggestions are part 10c. "Show an array as a
heap…" opens a form:

| Choice | Options |
| --- | --- |
| array | a variable of a one-dimensional numeric array recorded in this run |
| indexing | 0-based (children 2i+1, 2i+2) or 1-based (index 0 unused, children 2i, 2i+1) |
| heap size | the whole array, or an `int` variable recorded in this run |
| the variable is | the number of heap elements, or the last heap index |
| order | max-heap or min-heap (only observed, never enforced) |

The mapping lasts for the page session. It is checked against each run's recorded
variables; if they are missing, the card says why and nothing is drawn. Variables are
looked up by name from the outermost frame inward: main's `end` bounds the heap even
while a helper with its own `n` runs.

## The projection

- **Same array.** The heap is the array object the variable refers to, the same one
  the Arrays panel shows (AC-31).
- **Strip:**
  - heap cells are outlined;
  - cells outside the boundary (the sorted suffix in heap sort) are dashed;
  - an unused index 0 (1-based heaps) is dimmed.
- **Tree:** the heap indices as a binary tree, with the root on top and in-order
  positions. Each node shows its value and index. Nodes drag like other diagram
  elements (AC-23).
- **Highlights:** a step's read or write of an index highlights that index on the
  strip and in the tree.
- **Boundary:** comes only from the confirmed variable's recorded value. When the
  variable is not in scope (before heap sort's extraction loop starts, after it ends)
  or unknown, no boundary is shown and the panel says why. It is never guessed.
- **Heap order** is an observation. Parent-child pairs inside the heap that break the
  chosen order are listed, and their edges are dashed. The panel says that holding
  order is not a proof the algorithm is correct, and that a violation (a heap under
  repair after a swap) is shown as recorded, not repaired (AC-34).

## Not covered here

The internal layout of `PriorityQueue` (never shown; library views stay logical),
k-ary heaps, heaps of objects, and boundary expressions other than one variable
(requirements 6.1 allow "an explicitly supported expression"; none are supported yet).
