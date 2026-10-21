# View-ranking rules (Part 10c)

Status: **draft for review**. The user approved starting 10c on 2026-10-21 ("ok"); the
rules below must be reviewed before implementation (requirements 6.4: "Define and
test exact rules before implementing them"). Rule set version: **`rank-1`**.
Requirements: section 6.4, AC-32, AC-33, AC-34.

## What a ranking is, and is not

A ranking says how well the **recorded facts so far** fit a way of drawing a subject.
It is not a claim about the program's intent, the algorithm, or its correctness.

| Separate notion | Meaning | Shown as |
| --- | --- | --- |
| Capture coverage | whether the facts exist (capture complete, partial; unknown values) | "missing facts" in a ranking |
| View fit | how the recorded structure matches a view | **Strong / Possible / Weak**, or **Incompatible**, or **Unknown** |
| Invariant observation | whether an order property holds now (heap order, search-tree order) | "observed: holds / fails at …", never part of the level |
| Algorithm correctness | whether the program does what it should | never assessed |

There are no percentages or scores. Confirming a view never raises its level, and a
level never confirms, changes or switches the view the user chose.

## Inputs and determinism (AC-33)

An assessment is a pure function of:
- the run's trace;
- the cursor `k`;
- the candidate (subject, view kind, field mapping);
- the rule-set version.

It reads only the reconstructed state at `k` and the observable events up to and
including step `k`: `events[0 .. stepEnds[k-1])`, or none at cursor 0. Moving backward
to `k` gives the same assessment as first arriving there. A trace cut after step `k`
gives the same assessment at `k` as the full trace (no future leakage). Each ranking
shows "rank-1, steps 1–k".

## Subjects and candidates

Candidates come from recorded types only, as in Parts 9b, 9c and 10b. The field roles
are those of the suggestion, or of the user's confirmed mapping if there is one.

| Subject | Candidate views |
| --- | --- |
| a traced class with ≥ 2 fields of its own type | binary tree (value, left, right) |
| a traced class with exactly 1 field of its own type | linked list (value, next) |
| a traced class with one array field and ≥ 1 int field | array stack (storage, top); with ≥ 2 int fields also array queue (storage, front, rear, size) |
| a one-dimensional numeric array variable | heap (0-based, max and min assessed separately) |
| an `ArrayDeque` or `LinkedList` variable | stack, queue |

A candidate whose recorded field types do not fit (for example a link field that is
not of the class's type) is **Incompatible**, with the reason. A subject with no
objects or no array yet at `k` gives **Unknown: nothing recorded yet**.

## Common rules

- **Counts state what they count**, for example "4 nodes at step 37", "2 parent-child
  swaps in steps 1–37".
- **Missing facts:** values or fields that are `unknown` at `k` (after uncaptured
  code), or a partial capture that ends before `k`. If a fact needed for a rule is
  missing, that rule does not count as met. The level is then at most **Possible**,
  and the missing facts are listed.
- **Contradictions** are facts against the view (a cycle in a list, a node with two
  parents in a tree). They are listed by object id. A candidate with a contradiction
  is at most **Weak**.
- **Ties** are allowed. Candidates are listed by level, then in the fixed table order
  above. Two views at the same level are both shown as fitting.

## Per-view rules

N = objects of the class present at `k`. A *link* is a link field (left, right or next)
that currently refers to another object of the class.

**Binary tree**
- Contradictions: a node that is the child of two parents; a cycle; a node that is
  its own child.
- **Strong:** N ≥ 3, at least 2 links, no contradiction, and some node at depth ≥ 2
  or with two children.
- **Possible:** N ≥ 2 and at least 1 link, no contradiction.
- **Weak:** otherwise (for example one node, or nodes not linked yet).
- Invariant observation (separate): search-tree order on the value field (every left
  subtree smaller, every right subtree not smaller) holds, fails at the listed nodes,
  or cannot be told (values not comparable or unknown).

**Linked list**
- Contradictions: a node that is the next of two nodes; a cycle.
- **Strong:** a chain of ≥ 3 nodes, no contradiction.
- **Possible:** a chain of 2 nodes, no contradiction.
- **Weak:** otherwise.

**Array stack (storage, top)**
- *Push-like op:* a write to `top` that changes it by +1. *Pop-like op:* a change by −1.
  Counted in steps 1–k.
- Contradictions: `top` outside 0..length of the storage at `k`.
- **Strong:** ≥ 3 such ops, including both push-like and pop-like ones.
- **Possible:** ≥ 1 such op.
- **Weak:** none.

**Array queue (storage, front, rear)**
- *Enqueue-like op:* a write to `rear` that changes it by +1 modulo the storage length.
  *Dequeue-like op:* the same for `front`.
- Contradictions: a write that moves `front` or `rear` backwards (not by +1 modulo the
  length).
- **Strong:** ≥ 2 enqueue-like and ≥ 1 dequeue-like ops.
- **Possible:** ≥ 1 enqueue-like op.
- **Weak:** none.

When a class is both a stack and a queue candidate, both are assessed, and the counts
show why one fits better.

**Heap (array variable, 0-based; max and min separately)**
- *Heap-shaped swap:* two writes to the array, within at most 3 consecutive steps,
  whose indices are parent and child (`i` and `2i+1` or `2i+2`). Counted in steps 1–k.
- **Strong:** ≥ 3 heap-shaped swaps.
- **Possible:** ≥ 1.
- **Weak:** none, so the array is drawn only as an array.
- Invariant observation (separate): the chosen order holds in the whole array at `k`,
  or fails at the listed parent-child pairs. A heap under repair after a swap is
  allowed to fail (AC-34); a failure never changes the level.
- The boundary is not ranked. It comes only from a confirmed mapping (Part 9c).

**Deque as stack or queue**
- Library calls on the variable in steps 1–k:
  - *Stack calls:* `push`, `pop`, `peek`, `addFirst`, `removeFirst`, `pollFirst`,
    `peekFirst`.
  - *Queue calls:* `offer`, `add`, `addLast`, `poll`, `remove()`, `peek`.

  `peek` counts for both.
- **Strong:** ≥ 3 calls of the kind and none of the other kind (other than `peek`).
- **Possible:** ≥ 1 call of the kind.
- **Weak:** no call of the kind.

## Presentation

- A "Suggested views" card lists, per subject, each candidate with its level, the
  counts behind it, missing facts and contradictions, plus "rank-1, steps 1–k".
- A view is still drawn only after confirmation (Parts 9b, 9c, 9d, 10b). A ranking only
  orders suggestions; stepping never switches the user's view.
- Invariant observations stay with their view (tree, heap). They are labeled
  "observed", never "correct".

## Tests required before release

1. Every rule threshold, below and at the boundary, on handwritten states.
2. **Determinism:** every cursor of a real trace assessed twice (forward, then
   backward) gives identical results.
3. **No leakage:** the assessment at `k` on the full trace equals the assessment on
   the trace cut after step `k`.
4. **Separation:** a heap mid-swap fails heap order while its fit level is unchanged.
   A tree with a two-parent node is Weak and keeps the edge visible.
5. **Browser:** the tree-sort, heap-sort and custom-structure examples show their
   expected levels at the end. A level changes while stepping, the view stays as
   confirmed, and stepping back restores the earlier level.
