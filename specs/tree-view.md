# Tree view of user objects (Part 9b)

Status: approved by the user on 2026-10-20 ("ok", starting 9b of the roadmap in
[V1 scope](v1-scope.md)). Requirements: section 6 (Tree), 6.1 (confirmed
interpretations), AC-13, AC-27 (tree part), AC-34 (tree part). Frontend only: the
trace already records objects and fields ([objects](objects.md)).

## Suggest, then confirm

A traced class is *suggested* as a binary tree node when at least two of its fields
have the class's own type and at least one other field exists. The suggestion
names the value field (the first other field) and the children: fields named `left`
and `right` if they exist, otherwise the first two link fields. It reads:

> **Node** has two Node fields: it may be a binary tree node (value key, children left / right).
> [Show as tree] [Choose fields] [Not a tree]

Nothing is drawn as a tree until the user confirms. "Choose fields" lets the user pick
the value, left and right fields (left and right must be different fields of the
class's type). A confirmed mapping can be changed or removed later.

Field names never decide anything on their own: they only order the suggestion. A
class with `left` and `right` fields is not assumed to be a tree.

## Checking against each run

A confirmed mapping lasts for the page session. Before each run's view, it is checked
against that run's recorded field types:
- the class exists;
- the mapped fields exist;
- the children are fields of the class's own type;
- the two children differ.

If it no longer fits, the card says why ("Node has no field lo") and no tree is drawn
until the user changes or removes it. Object ids are never carried between runs.
Full mapping revalidation and persistence are part 10b.

## The projection

- The nodes are the mapped class's objects in the reconstructed state at the cursor.
- The roots are the nodes no other node links to as a left or right child. Roots
  that variables refer to come first.
- A node just created and not yet linked is its own one-node tree until a recorded
  write links it.
- Positions: the in-order index across trees, and the depth. For a binary search tree,
  left to right is therefore sorted order.
- Edges are the recorded left/right references.
- Every node is drawn once. A node linked from a second parent, or through a cycle,
  is not drawn again. The extra link is a dashed edge, listed as "Not a tree here:
  o3.left -> o2". It is never repaired or hidden (AC-34).
- Unknown values and children (after uncaptured code) show as `?`, never as null.
  A child that refers to another class is shown as a small marker.
- The node a step touches is highlighted:
  - a field read in green;
  - a field write in orange, with the field name;
  - a new object outlined.
- Variables naming a node are shown beside it, once per name.
- Nodes drag like other diagram elements, and edges follow (AC-23).

## Not covered here

Non-binary trees (child lists or arrays), mapping a list's or stack's fields
(10b), view rankings (10c), and saving mappings after the page closes.
