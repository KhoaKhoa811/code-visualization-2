# Custom mappings: lists, stacks and queues of user classes (Part 10b)

Status: approved by the user on 2026-10-21 ("ok", step 10b of the roadmap in
[V1 scope](v1-scope.md)). Requirements: section 6.1 (confirmed interpretations,
revalidation), AC-27, AC-30. Extends the tree mapping of Part 9b
([tree view](tree-view.md)); the array heap mapping is Part 9c ([heap view](heap-view.md)).
Frontend only.

## Suggest, then confirm

Suggestions come from recorded field types only, never from names or algorithms:

| A class with | Suggested as | Fields |
| --- | --- | --- |
| exactly one field of its own type and another field | linked list node | value (first other field), next (the link) |
| two or more fields of its own type | binary tree node (9b) | value, left, right |
| one array field and one int field | stack built on an array | storage, top |
| one array field and two or more int fields | queue built on an array (circular) | storage, front, rear, optional size |

Nothing is drawn until the user confirms:
- "Show as list";
- "Show as stack" or "Show as queue";
- "Choose fields": pick every role, including what a number means. A stack's top is
  either the element count or the top index. A queue's rear is either the next free
  slot or the last element.

"Not a list" or "Neither" dismisses the suggestion for the session.

## Views

- **List:** chains of nodes from the nodes no other node links to (variables' first),
  with recorded next links as arrows.
  - A cycle ends in a dashed arrow back.
  - A node with two predecessors is reported ("o3 is next of o1 and o2").
  - Every node is drawn once, as recorded.
  - Nodes drag and arrows follow (AC-23).
- **Stack:** the storage array, vertical with the top first. The occupied slots come
  from the top field, and the top is marked. A top outside the array is reported,
  not clamped into a guess.
- **Queue:** the storage array as it sits in memory, marked with front and rear.
  - The occupied slots run from front to rear, wrapping around the end of the array.
  - The logical order is listed ("front → rear: 3, 4, 5").
  - Without a size field, front meeting rear could mean empty or full. The view says
    so instead of choosing.
- A step's field write or storage write is highlighted on the node or slot.

## Revalidation and revisions (AC-30)

- **Revisions:** every confirmation is a new **revision** of the class's mapping, and
  records the source it was confirmed for.
- **Checking each run:** the card shows "revision N, confirmed for this source" (or "an
  earlier source; it still fits"). Before each run's view, the mapping is checked
  against the run's recorded fields: the fields exist and have the needed types (the
  class's own type for links, an array for storage, ints for indices).
- **When a field is renamed, removed or retyped:** the card says why ("Main.Cell has
  no field after"), no view is drawn, and the user chooses the fields again (a new
  revision) or removes the mapping.
- **Ids:** object ids are never carried between runs.
- **Old playback:** an earlier run's playback is already invalidated when the source
  changes (AC-15). A mapping is a presentation of the run in view, never a change to
  its facts.

## Not covered here

Doubly linked lists as one view (they are a graph with two links), lists of library
collections, queues that keep elements in a linked list, saving mappings after the
page closes, and view rankings (10c).
