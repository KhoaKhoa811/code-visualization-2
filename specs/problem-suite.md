# Broader problem suite (Part 10d)

Status: **draft for review**, revision 2 (2026-10-22). Nothing below is implemented yet.
Revision 1 decisions by the user: the suite should include lambdas and streams; G2 includes
`entrySet()`; G5 stays limited to `Collections.reverseOrder()`.
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
| M5 | counting with `merge` | `m.merge(k, 1, Integer::sum)` | method reference (G6) |
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

### Lambdas and streams (G6, G7), added in revision 2

Probe of 4 lambda and stream programs on 2026-10-22:

| ID | Program | Result today |
| --- | --- | --- |
| F1 | `xs.forEach(x -> total[0] += x)` | **bug**: the whole program falls back to output only (`TRACE_MAPPING`, "no bytecode range for an uncaptured statement") |
| F2 | `xs.stream().filter(x -> x % 2 == 0).mapToInt(x -> x * x).sum()` | the statement runs uncaptured, and so does its variable |
| F3 | `ws.sort((p, q) -> p.length() - q.length())` | runs uncaptured |
| F4 | `Arrays.stream(a).map(v -> sq(v)).max().getAsInt()` | runs uncaptured; the helper `sq` runs silently |

Programs added to the suite for G6 and G7 (written in 10d, probed before the fix):

| ID | Program | Uses |
| --- | --- | --- |
| F1 | sum with `forEach` | `List.forEach` with a lambda that writes a captured array |
| F2 | sum of squares of evens | `stream`, `filter`, `mapToInt`, `sum` |
| F3 | sort words by length | `List.sort((p, q) -> ...)`, and a variant with `Comparator.comparing(String::length)` |
| F4 | maximum of mapped values | `Arrays.stream(int[])`, `map` calling a helper, `max`, `getAsInt` |
| F5 | word count with `merge` | `Map.merge(w, 1, Integer::sum)` (M5) |
| F6 | group words by length | `collect(Collectors.groupingBy(String::length))` |
| F7 | adjacency lists | `Map.computeIfAbsent(u, k -> new ArrayList<>()).add(v)` |
| F8 | remove negatives | `List.removeIf(x -> x < 0)` |
| F9 | primes up to n | `IntStream.rangeClosed(2, n).filter(Main::isPrime).boxed().collect(Collectors.toList())` |
| F10 | stored functions | `Function<Integer, Integer> f = x -> x + 1; f.apply(3)`, a `Predicate`, `andThen` |
| F11 | text pipeline | `Arrays.stream(words).map(String::toUpperCase).distinct().sorted().collect(Collectors.joining(","))` |

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
| **G6** lambdas and method references | See below. | callback frames inside a library call |
| **G7** sequential streams | See below. | pipeline steps, callbacks per element |

### G6 lambdas and method references

- **A lambda is user code.** Its body is traced like a helper method of `Main`. When a
  covered library call runs a lambda, the lambda gets its own frame, "lambda at line:col",
  whose caller is the frame that made the library call, marked *via* that library method
  (for example via `java.util.List.forEach`). Its parameters are bindings; captured
  variables are shown as read-only bindings with their source names. Steps inside the
  body are ordinary steps. Its return value ends the frame, like a helper return.
- **The library call stays one fact.** Its LIBRARY step comes after its callbacks, with
  the arguments, result and new contents, as today. The callback frames between show what
  the JDK actually called, in the order it called them; nothing is inferred.
- **Replay without running user code.** The trusted builder replays the call on its
  mirror with stand-in functions that return the recorded callback results in order, and
  checks that each stand-in is called with the recorded arguments. Any difference stops
  capture at a safe boundary (partial), never a guess.
- **Method references:** to a method of `Main` (`Main::isPrime`) it is a helper call
  through the library, with a frame; to a covered JDK method (`Integer::sum`,
  `String::length`, `String::toUpperCase`) it is a recorded callback result with no
  frame (its internals are library code).
- **Lambda values:** a lambda or method reference stored in a variable is a value of a
  new kind, *function*, shown as "lambda at line:col" or "Integer::sum". Calling it
  directly (`apply`, `test`, `accept`, `get`, `applyAsInt`, `compare`, `run`) enters its
  frame like a helper call. `==` on functions and their identity stay uncaptured (the JVM
  may share non-capturing lambdas).
- **Covered calls that take functions:** `Iterable.forEach`, `Map.forEach`,
  `List.removeIf`, `List.replaceAll`, `List.sort(cmp)`, `Collections.sort(list, cmp)`,
  `Arrays.sort(T[], cmp)`, `Map.merge`, `computeIfAbsent`, `computeIfPresent`, `compute`,
  `Comparator.comparing`, `comparingInt`, `thenComparing`, `reversed`,
  `Function.andThen`/`compose`, `Predicate.and`/`or`/`negate`.
- **Exceptions** thrown inside a lambda unwind its frame and fail the library call, as
  in Part 8h.

### G7 sequential streams

- **Sources:** `Collection.stream()`, `Arrays.stream(array)`, `Stream.of(...)`,
  `IntStream.range`/`rangeClosed`, `String.chars()`.
- **Intermediate operations are lazy.** `filter`, `map`, `mapToInt`, `mapToObj`, `boxed`,
  `sorted()`, `sorted(cmp)`, `distinct`, `limit`, `skip` and `peek` are each one step that
  returns a *stream* value ("stream of xs: filter, mapToInt"). A stream has no contents
  to show until a terminal operation runs.
- **Terminal operations run the pipeline:** `sum`, `count`, `min`, `max`, `average`,
  `reduce`, `anyMatch`, `allMatch`, `noneMatch`, `findFirst`, `forEach`, `toList()`,
  `collect` with `Collectors.toList`, `toSet`, `joining`, `groupingBy` (with `counting`
  or `toList`) and `toMap`. The lambda frames appear in the order the JDK runs them
  (element by element, so `filter` then `map` for each element), then the terminal step
  with its result. This shows the real lazy order.
- **Optionals:** `OptionalInt`/`Optional` results with `getAsInt`, `get`, `orElse`,
  `isPresent`.
- A stream used twice fails as in Java (`IllegalStateException`).

**Out of scope (stay uncaptured, with their diagnostic):**
- parallel streams;
- comparators kept *inside* a collection (`new PriorityQueue<>((a, b) -> b - a)`, a
  `TreeMap` or `TreeSet` with a comparator). Showing their contents in order needs
  comparisons the program never made (decision 3). Comparators passed to a sort are
  covered, because the sort makes all its comparisons itself;
- user `toString` in output, `String.format`, iterators used directly (`it.next()`),
  `PriorityQueue` iteration, functional interfaces declared by the user, and stream
  operations not listed above.

## Contract impact (to confirm while implementing)

- Trace contract **1.9.0** (G2): new LIBRARY method names; a map-entry value (key and
  value); the iteration step names the collection's `objectId`.
- Trace contract **1.10.0** (G6, G7): value kinds *function* and *stream*; a frame may
  have `via` (the library method that called it) and a lambda method id with its source
  range; callback results recorded for replay.
- No change to the run API.

## Increments, tests and acceptance

1. **10d.1** G1, G3, G4, G5 (single-call adapters on the existing mirror), plus the F1
   bug: a lambda anywhere must not stop mapping; until G6 lands, that statement runs
   uncaptured like any other.
2. **10d.2** G2 (iteration steps and map entries).
3. **10d.3** G6 lambdas, method references and calls that take functions; frontend shows
   function values, callback frames with *via*, and their step texts.
4. **10d.4** G7 streams; frontend shows stream values and pipeline steps.
5. **10d.5** the suite as a permanent tracer test (`ProblemSuiteTest`): every program
   above, plus one renamed and reformatted variant of A1, R1, M1 and F2, must give
   complete capture, **zero uncaptured statements**, an original-matching run, and the
   expected final values. The out-of-scope forms stay as tests that they run uncaptured
   with their diagnostic. The frontend gets three new examples (word count with
   `entrySet`, anagram check, a stream pipeline) and a browser test that steps through a
   stream pipeline into a lambda frame and back.

Each increment ends with its own verification (live Docker `clean verify` for Java
changes, frontend unit, build and browser tests). AC-36 becomes **Done** when 10d.5
passes all of them.

## Decisions for review (revision 2)

1. The added F1–F11 programs and the G6/G7 operation lists above.
2. Lambda frames: a lambda gets its own frame, marked *via* the library method that
   called it (recommended), rather than hiding the call and showing only results.
3. Comparators: covered when passed to a sort; still not covered when kept inside a
   `PriorityQueue`, `TreeMap` or `TreeSet` (keeps decision 3 of revision 1).
4. Size: 10d grows from 3 to 5 increments; G6 and G7 are the largest pieces.
