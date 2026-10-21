# Broader problem suite (Part 10d)

Status: **draft for review** (2026-10-22). Nothing below is implemented yet.
Requirements: section 6.5, AC-36 ("broader string/set/array/map/tree/heap problems,
including differently written sorts; agreed operations work through shared Java
capabilities and adapters; no exact algorithm-template dependency; library internals
remain factual").

## Purpose

10d agrees a fixed suite of typical student programs, runs it through the real tracer,
and closes the coverage gaps it shows. After 10d, every suite program must trace with
**complete capture and no uncaptured statement**, and must match the original program
(stdout, exit status, uncaught exception and stack trace). Programs are written in
different styles on purpose (loops of different kinds, helpers or inline code, recursion
or iteration, different names). The tracer never recognizes an algorithm; every program
uses the same shared capabilities.

## Probe of the candidate suite (2026-10-22, before any change)

Each program ran through `Tracer.prepare`, the host run and the trace builder
(the same path as `TracerTest`). All 32 compiled, all matched the original output, and
all gave `capture: complete`. "Gaps" are statements that ran **without capture**
(an `UNCAPTURED` event and an `UNSUPPORTED_CONSTRUCT` diagnostic).

| ID | Program | Style notes | Gaps today |
| --- | --- | --- | --- |
| A1 | insertion sort | `while` shift inside `for` | `Arrays.toString` (G3) |
| A2 | selection sort | `swap(int[], i, j)` helper | none |
| A3 | quicksort (Lomuto) | recursive `sort` + `partition` | none |
| A4 | merge sort | recursive, temp arrays, `a[k++] = c ? l[i++] : r[j++]` | none |
| A5 | binary search | `mid = lo + (hi - lo) / 2`, `break` | none |
| A6 | matrix rotate | `int[][]`, nested `for` | none |
| A7 | library sort | `Arrays.sort(int[])` | `Arrays.sort` (G4) |
| S1 | palindrome | two indices, `charAt` | none |
| S2 | reverse words | `split(" ")`, `StringBuilder` | `split` (G3) |
| S3 | letter counts | `for (char c : s.toCharArray()) count[c - 'a']++` | `toCharArray` (G3) |
| S3b | letter counts | `charAt` in an index loop | none |
| S4 | anagram check | `toCharArray`, `Arrays.sort`, `Arrays.equals` | G3, G4 |
| S5 | run-length compress | `StringBuilder.append(char).append(int)`, `println(sb)` | `println(Object)` (G1) |
| M1 | word count | `HashMap.getOrDefault` | none |
| M1b | word count | `TreeMap.containsKey`/`get`, `println(map)` | G1 |
| M2 | two sum | `HashMap<Integer, Integer>`, early `return` | none |
| M3 | char counts printed | `for (Map.Entry e : m.entrySet())`, `getKey`/`getValue` | for-each over `entrySet` (G2) |
| M4 | sum of values | `for (String k : m.keySet())` | for-each over `keySet` (G2) |
| M5 | counting with `merge` | `m.merge(k, 1, Integer::sum)` | method reference (out of scope, see below) |
| T1 | dedup keeping order | `if (seen.add(x)) out.add(x)`, `println(list)` | G1 |
| T2 | set intersection | `for (int x : treeSet)`, `println(set)` | G2, G1 |
| T3 | first repeated char | `!seen.add(c)`, `break` | none |
| L1 | sum of a list | `for (int x : list)` | G2 |
| L2 | library list sort | `Collections.sort(list)`, `println(list.get(0))` | G4, G1 |
| R1 | BST sort | recursive `insert` returning the node, recursive in-order into a `StringBuilder` | G1 |
| R2 | BST iterative + height | `while (true)` descent, recursive `height` with `Math.max` | none |
| R3 | level order | `ArrayDeque<Node>` queue, `println(sb)` | G1 |
| H1 | heap sort | iterative `sift` helper | none |
| H2 | min-heap class | `int[] data` + `size` field, `push`/`pop` methods | none |
| H3 | top-k | `PriorityQueue.offer`/`poll`/`size`, `println(pq.peek())` | G1 |
| H4 | max priority queue | `new PriorityQueue<>(Collections.reverseOrder())` | comparator (G5) |
| K1 | balanced brackets | `ArrayDeque<Character>` stack, boolean expression | none |

14 of 32 have no gap today; 18 have at least one.

## Proposed gap fixes

Each fix is a recorded fact of a real call. Library internals stay hidden: a library
sort is one step with its before and after contents, never invented comparisons or swaps
(section 4 of [Java support](java-support.md)).

| Gap | Fix | Shown as |
| --- | --- | --- |
| **G1** `print`/`println(Object)` | Cover when the argument's static type is a boxed primitive, `String`, `StringBuilder`, or a covered collection or map. The printed text is checked by replaying `String.valueOf` on the trusted mirror. User objects stay uncaptured (their `toString` is user code). | the existing output step |
| **G2** enhanced `for` over collections | Cover `for (T x : c)` where `c` is a covered `List`, `Set`, `Deque`/`Queue`, or a map's `keySet()`, `values()` or `entrySet()`. Each iteration is one step, "next element of c", recorded as a LIBRARY event with the element, followed by the binding of `x`. `hasNext` is loop bookkeeping, not a step. A `ConcurrentModificationException` is the failure of that step. A map entry is a value with its key and value; `getKey()` and `getValue()` are covered, `setValue` is not. Iterating a `PriorityQueue` stays uncaptured (its order is the private heap layout). | LIBRARY step per element |
| **G3** arrays from and to strings | Cover `String.toCharArray()` and `String.split(String)` (the new array is an ALLOCATE with its contents, the regex is not interpreted by the tracer, the mirror replays the call), plus pure `Arrays.toString` and `Arrays.equals` for one-dimensional primitive and `String` arrays. | LIBRARY step |
| **G4** library sorts | Cover `Arrays.sort(a)` for one-dimensional primitive and `String` arrays and `Collections.sort(list)` with natural order: one LIBRARY step whose `after` gives the new contents. No comparison or swap events. | one step, before/after |
| **G5** `Collections.reverseOrder()` priority queue | Cover `new PriorityQueue<>(Collections.reverseOrder())` only: a JDK comparator with no user code. Contents are shown in poll order, as today. | the existing collection |

**Out of scope (stay uncaptured, with their diagnostic):** lambdas, method references and
user comparators (`merge(k, 1, Integer::sum)`, `new PriorityQueue<>((a, b) -> b - a)`),
streams, user `toString`, `String.format`, iterators used directly (`it.next()`), and
`PriorityQueue` iteration. These run user callbacks or hidden state that the recorder
cannot show faithfully without a callback design.

## Contract impact (to confirm while implementing)

- Trace contract **1.9.0** (minor, additive): new LIBRARY method names; a value kind or
  object kind for a map entry (key and value) for G2; the iteration step names the
  collection's `objectId`.
- No change to the run API.

## Increments, tests and acceptance

1. **10d.1** G1, G3, G4, G5 (all single-call adapters on the existing mirror).
2. **10d.2** G2 (iteration steps and map entries; the only new trace shape).
3. **10d.3** the suite as a permanent tracer test (`ProblemSuiteTest`): every program
   above, plus one renamed and reformatted variant of A1, R1 and M1, must give complete
   capture, **zero uncaptured statements**, an original-matching run, and the expected
   final values. M5 and the out-of-scope forms stay as tests that they run uncaptured
   with their diagnostic. The frontend gets two new examples (word count with
   `entrySet`, anagram check) and a browser test that steps through one of them.

AC-36 becomes **Done** when 10d.3 passes in the full live Docker `clean verify`, the
frontend tests and the browser tests.

## Decisions for review

1. The 32-program suite and its out-of-scope list above.
2. G2 includes `entrySet()` with a map-entry value (common in word-count problems).
   Alternative: only `keySet()` and `values()`, which needs no new value shape.
3. G5 is limited to `Collections.reverseOrder()`. User comparators wait for a callback
   design (not in V1).
