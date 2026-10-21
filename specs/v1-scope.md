# V1 scope and completion checklist

Status: approved by the user on 2026-10-20 ("I approve", for the Part 9/10 roadmap
proposed after Part 8i). This file maps every acceptance case of the
[requirements](../requirements/PROJECT_REQUIREMENTS.md) (section 14) to its status and
to the part that delivers it. V1 is complete only when every row is **Done** and
verified. The [coverage roadmap](coverage-roadmap.md) has the part descriptions.

Status words: **Done** (implemented and verified by the named tests), **Partial**
(the rest is assigned to a part), **Planned** (no implementation yet).

## Roadmap after Part 8

| Part | Tier | Capability | Acceptance cases |
| --- | --- | --- | --- |
| 9.0 | Core V1 | This checklist | — |
| 9a | Core V1 | Grid of panels on one cursor; drag panels and diagram nodes (layout only) | AC-18, AC-23, AC-26 |
| 9b | Core V1 | Tree view of user node objects through a confirmed value/left/right mapping; BST sort | AC-13 |
| 9c | Core V1 | Heap view: one array as array and tree, confirmed indexing and recorded boundary, sorted suffix | AC-14, AC-31 |
| 9d | Core V1 | `Deque` shown as a stack or a queue by explicit choice | AC-12 |
| 10a | V1.1 | Separate test inputs next to `Main.java`, replay without resubmission | AC-35 |
| 10b | V1.1 | General custom mappings (list, tree, stack, queue conventions), revalidation after edits | AC-27, AC-30 |
| 10c | V1.1 | Evidence-based view rankings (Strong / Possible / Weak), rules specified first | AC-32, AC-33, AC-34 |
| 10d | V1.1 | Broader problem suite across arrays, strings, maps, sets, trees, heaps | AC-36 |

The mapping model of 9b and 9c is the first piece of 10b. The input-wait limit of
Part 4r (part of AC-20) is deferred to the 6g isolation review before any public
deployment.

## Acceptance cases

| Case | Status | Delivered by | Evidence or remaining work |
| --- | --- | --- | --- |
| AC-01 scalar assignment | Done | 7b, 7e | tracer `ac01`; browser slice |
| AC-02 one element changes | Done | 7b | tracer `ac02` |
| AC-03 array loop | Done | 7b | tracer `ac03` |
| AC-04 bubble sort | Done | 7b, 7e | tracer `ac04`; browser bubble sort |
| AC-05 shared array | Done | 7b | tracer `ac05` |
| AC-06 same name, other scope/frame | Done | 7b, 8d | tracer `j03`, recursion tests |
| AC-07 side effects once | Done | 7b | tracer `j02`, original-versus-traced comparison |
| AC-08 forward/backward/restart | Done | 7e | reducer tests; browser step back |
| AC-09 compile error / limitation | Done | 7c, 8a | compile-error and output-only tests |
| AC-10 runtime error, no false write | Done | 7b, 8h | tracer `j06`, `j07` |
| AC-11 limits | Done | 6d, 7c | timeout, output and trace-limit live tests |
| AC-12 list/stack/queue/map | Done | 8f, 9d | library tests; ArrayDeque/LinkedList shown as a stack or queue by explicit choice (browser collections test) |
| AC-13 BST sort tree | Done | 9b | browser tree-sort test: confirmed mapping, in-order positions, link write highlighted ([tree view](tree-view.md)) |
| AC-14 heap sort | Done | 9c | browser heap-sort test: confirmed heap, synchronized strip and tree, boundary from end ([heap view](heap-view.md)) |
| AC-15 edit after run | Done | 7e | browser source-edit check |
| AC-16 calls and recursion | Done | 8d | tracer and browser recursion tests |
| AC-17 several steps on one line | Done | 7b | tracer `ac17` |
| AC-18 drag a node | Done | 9a | browser layout test: box moves, arrows follow, values and cursor unchanged |
| AC-19 Scanner prompt and input | Done | 8g | browser AC-19 test |
| AC-20 wait, cancel, disconnect, EOF | Partial | 8g, 6g | cancel, disconnect, EOF and invalid tokens done; input-wait limit deferred to 6g |
| AC-21 replay without resubmission | Done | 8g | browser AC-19 test (replay step) |
| AC-22 only present structures | Done | 7e, 8e, 8f | views render from reconstructed state only |
| AC-23 drag arrays and nodes | Done | 9a, 9b, 9c | arrays as groups, graph and collection boxes (9a), tree nodes (9b), heap nodes (9c) |
| AC-24 output-only fallback | Done | 7c | output-only tests |
| AC-25 safe boundary | Done | 7b, 8a | partial-capture tests |
| AC-26 grid of synchronized panels | Done | 9a | panel grid on one cursor, reorderable ([frontend design](frontend-design.md)) |
| AC-27 custom field mappings | Done | 9b, 10b | tree (9b), list, array stack and circular queue (10b) mappings suggested from types and confirmed ([custom mappings](custom-mappings.md)) |
| AC-28 object-graph fallback | Done | 8e | tracer objects tests; browser object graph |
| AC-29 missing or truncated capture | Done | 8a, 8e | unknown values, traversal limit |
| AC-30 mapping revalidation | Done | 10b | renamed field: the view stops, the card says why, a new revision is confirmed (browser test) |
| AC-31 array used as heap | Done | 9c | heap strip equals the Arrays panel's array; confirmed indexing and recorded boundary |
| AC-32 view rankings | Done | 10c | rank-1 Strong/Possible/Weak with reasons, ties, Unknown and Incompatible ([rules](visualization-rules.md)) |
| AC-33 rankings on rewind | Done | 10c | determinism and cut-trace (no leakage) unit tests; browser rewind restores the same text |
| AC-34 invariant vs fit vs correctness | Done | 9b, 9c, 10c | conflicting tree links dashed (9b); heap order observed, not repaired (9c); rankings keep fit, observations and correctness apart (10c) |
| AC-35 separate test inputs | Done | 10a | provided input through the API and the browser; snapshot input hash; replay without resubmission ([test inputs](test-inputs.md)) |
| AC-36 broader problems | Partial | 8b–8i, 10d | capabilities exist; agreed problem suite in 10d |

Update this table in the same commit that changes a case's status.
