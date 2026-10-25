# Structure detection, rule set rank-2 (Part 19)

Status: approved by the user on 2026-10-24 ("our task today is figuring how to improve data
structure detection ... complicated code (including different data structures)", then
"1. it's right 2. show them automatically"). Builds on [view rankings](visualization-rules.md)
(rank-1, Part 10c), [custom mappings](custom-mappings.md), [tree view](tree-view.md),
[heap view](heap-view.md) and [graph view](graph-view.md). Recording changes 19a-19b are in
[objects](objects.md). Rule-set version: **`rank-2`**.

## Why (a probe of mixed programs, 2026-10-24)

| Program | Before Part 19 |
| --- | --- |
| trie (`TrieNode[] kids`), hash table with chaining (`Entry[] buckets`) | the instrumented program failed JVM verification and did not run (19a) |
| LRU cache (`HashMap<Integer, Node>` + doubly linked list), n-ary tree (`List<Node> children`) | the classes were not traced, most statements ran uncaptured (19b) |
| a heap inside a class (`int[] a; int size`) | suggested as a stack or queue only |
| `Node { prev, next }` | suggested as a binary tree |

Detection looked only at declared field types, one class at a time, and every view needed a
separate confirmation card.

## Principles (unchanged from rank-1)

A detection is a pure function of the trace prefix up to cursor `k`, the candidate and the rule
set; it never reads later events, never scores, and counts say what they count. Levels are
Strong / Possible / Weak / Incompatible / Unknown; a missing fact caps at Possible, a
contradiction at Weak. Invariant observations (search-tree, heap order) stay apart from levels.

## Roles from the recorded objects (19c)

Link roles are read from **reachable** objects (from the frames' variables, through fields,
elements and entries): an object unlinked from every structure keeps its old links and would
otherwise contradict the structure it left.

- **Mirror pair:** two links `a`, `b` of a class where every recorded `x.a = y` has `y.b = x` and
  every `x.b = y` has `y.a = x` (at least one of each): a **doubly linked list** (`next` is the
  field named `next`, else the first declared). It is not offered as a binary tree.
- **Parent pointer:** a link whose every value `y` has the node among `y`'s other links; with two
  more links, the class is a binary tree with a parent pointer.
- **Children in a field:** an array `C[]`, a list or set of `C`, or a map with values `C`: a **tree
  with children** (in order for lists, keyed for arrays and maps); keyed and with a `boolean`
  field it is called a **trie**.
- **Slots of chains:** an array field of a node class with one link (`Entry[] buckets`): a **hash
  table with chaining**.
- **Heap inside a class:** one numeric array field and an int field (the size).
- **Graph of index lists:** a `List<List<Integer>>` variable whose entries are all node indices.

## Rules per view

| View | Strong | Possible | Contradictions |
| --- | --- | --- | --- |
| doubly linked list | a chain of ≥ 3 reachable nodes | a chain of 2 | a node that is the next of two, a cycle |
| tree with children / trie | ≥ 3 nodes, ≥ 2 links, depth ≥ 2 or a node with ≥ 2 children | ≥ 2 nodes, ≥ 1 link | a node held twice, a cycle |
| hash table with chaining | ≥ 2 slots used and a chain of ≥ 2 | ≥ 1 entry | a chain that reaches a node again |
| heap (variable or in a class) | ≥ 3 parent-child swaps over ≥ 2 different pairs, one below the root, and the swaps show one order | ≥ 1 swap | — |
| graph (index lists) | ≥ 3 nodes and ≥ 3 edges | ≥ 1 edge | an entry that is not a node index (Incompatible) |

- A **parent-child swap** is two writes to the array within 3 steps at indices `p` and `2p+1` or
  `2p+2`. The **order** it restores is read from the values written: the parent receiving the
  larger value is a max-heap swap, the smaller a min-heap swap; the majority decides. (A sorted
  array satisfies min-heap order trivially, so the current order is only an observation.) A bubble
  sort swapping `a[0]` and `a[1]` repeatedly is not Strong.
- When a class's array is a Strong heap, its rank-1 array stack and queue readings are dropped
  (parent-child swaps are not what a stack or queue on an array does).
- Binary trees, singly linked lists, array stacks and queues, and deques as stacks or queues keep
  their rank-1 rules.

## Shown automatically (19f)

A candidate is **shown automatically** when it is Strong and it is the only Strong candidate of
its subject. It is shown from the step where it becomes Strong; stepping back before that step
takes it away again. The **Structures** card lists every candidate with its level and evidence and
a button: *shown automatically · hide*, or *show* for a weaker candidate. A mapping the user
confirmed by hand ("Choose fields yourself", the Part 9b/9c/10b/14 editors) comes first: the
automatic view of the same subject is not drawn twice. A deque detected as a stack or queue is
drawn so unless the user chose its view.

## Views (19d, 19e)

- **Doubly linked list:** the list view with `prev` links drawn back (dashed) where they mirror
  `next`; nodes the program no longer reaches are not drawn, and their number is said.
- **Trees with children / tries:** a layered tree; each edge carries its slot (array index, list
  position or map key).
- **Hash table with chaining:** one row per slot with its chain in link order; `∅` for an empty
  slot.
- **Heap in a class:** the heap view on the object's array, bounded by its size field.
- **Held by (19e):** a node shows where else it is held, as small tags: a map entry (`map[3]`), an
  array slot (`buckets[1]`) or a field of an object of another class (`cache.head`). Links between
  the class's own objects and the containers they keep children in are drawn as arrows instead.
  This is how structures inside structures (a map into a list, an array of chains) read together.

## Tests

`detect.test.ts` (fixtures from tracer tests: LRU, n-ary tree, trie, chaining, heap class, mixed
program, bubble sort): which views are shown automatically, the mirror pair with an evicted node,
no stack reading for a heap class, a view appearing at its Strong step and leaving on rewind,
children slots, chain slots and held-by tags. Browser: the rewritten 10c test (tree and heap shown
automatically, rewind), the 9b test (hide, then confirm by hand), and "Mixed structures".
