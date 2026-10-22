# Strings and library collections (Part 8f)

Status: approved by the user on 2026-10-19 ("ok", after the 8f proposal), step 8f
of the [coverage roadmap](coverage-roadmap.md). Builds on [objects](objects.md)
(Part 8e). Trace contract **1.5.0**. Library internals are never read or inferred:
every library operation is one recorded call with its arguments and result.

## Covered library operations

Calls in traced code to these methods are steps (a LIBRARY event):

| Receiver | Methods |
| --- | --- |
| `String` | `length`, `charAt`, `substring`, `indexOf`, `lastIndexOf`, `equals`, `compareTo`, `contains`, `startsWith`, `endsWith`, `isEmpty`, `toUpperCase`, `toLowerCase`, `trim`; since 10d `toCharArray`, `split` |
| `StringBuilder` | `new`, `append`, `insert`, `reverse`, `toString`, `length`, `charAt`, `setCharAt`, `deleteCharAt`, `setLength` |
| `List` (`ArrayList`, `LinkedList`) | `new`, `add`, `add(i, e)`, `get`, `set`, `remove(int)`, `remove(Object)`, `size`, `isEmpty`, `contains`, `indexOf`, `clear` |
| `Deque`/`Queue` (`ArrayDeque`, `LinkedList`), `Stack` | `new`, `push`, `pop`, `peek`, `offer`, `poll`, `add`, `remove()`, `addFirst`, `addLast`, `offerFirst`, `offerLast`, `pollFirst`, `pollLast`, `peekFirst`, `peekLast`, `removeFirst`, `removeLast`, `size`, `isEmpty`, `empty`, `clear` |
| `Map` (`HashMap`, `TreeMap`, `LinkedHashMap`) | `new`, `put`, `get`, `getOrDefault`, `containsKey`, `remove(key)`, `size`, `isEmpty`, `clear` |
| `Set` (`HashSet`, `TreeSet`, `LinkedHashSet`) | `new`, `add`, `contains`, `remove`, `size`, `isEmpty`, `clear` |
| `PriorityQueue` (natural order, or since 10d the JDK's reverse order) | `new`, `add`, `offer`, `poll`, `peek`, `remove()`, `size`, `isEmpty`, `clear` |
| static | `Math.max`, `Math.min`, `Math.abs`, `Math.sqrt`, `Integer.parseInt`, `String.valueOf` (primitives), `Character.isDigit`, `isLetter`, `isUpperCase`, `isLowerCase`, `toUpperCase`, `toLowerCase` |
| static, since 10d | `Arrays.sort` (whole array or a range), `Arrays.toString`, `Arrays.equals` on one-dimensional primitive or `String` arrays; `Collections.sort(list)` of boxed values or strings (natural order) |
| output, since 10d | `print`/`println(Object)` of a boxed value, `String`, `StringBuilder`, or a covered collection or map holding only those |

Part 10d ([problem suite](problem-suite.md)):
- A library sort is one LIBRARY step. For a static call that changes its argument,
  `objectId` is null and `after` is the changed array or collection. No comparison or swap
  is recorded or invented. The builder replays the call on a copy of the array's state.
- `toCharArray` and `split` return new arrays, captured with their contents and checked
  against the replayed call.
- `new PriorityQueue<>(Collections.reverseOrder())`, and the same for `TreeSet` and
  `TreeMap` (`Comparator.reverseOrder()` is the same JDK comparator), build a mirror in
  reverse order. The recorder reads a sorted collection's comparator without calling it.
  A collection ordered by any other comparator is user code: capture stops before it is
  shown, so it is never shown in a guessed order.
- Library-call arguments are recorded before the call, like receivers, so a collection or
  array first seen by a sort shows its contents from before the sort.
- 10d.2, trace 1.9.0: `for (T x : c)` over a covered list, set or deque, or over a map's
  `keySet()`, `values()` or `entrySet()`. Each `next()` is one LIBRARY step
  (`java.util.Iterator.next()` on the collection, with `view` for a map view), checked
  against the builder's own iteration of its mirror, then the loop variable's declaration.
  `hasNext` and the iterator are not shown. A `ConcurrentModificationException` is the
  failure of that step, as in Java. Map entries are `entry` objects with their key and
  value; `getKey()` and `getValue()` are covered, `setValue` is not, and a covered call that
  changes a shown entry (`put` of an iterated key) stops capture instead of showing a stale
  value. Iterating a `PriorityQueue` stays uncaptured.
- 10d.2: a compound assignment may unbox its right side (`total += map.get(k)`).
- 10d.3, trace 1.10.0, lambdas and method references:
  - Calls that run functions: `forEach` (lists, sets, deques, maps), `removeIf`,
    `replaceAll`, `List.sort(cmp)`, `Collections.sort(list, cmp)`, `Arrays.sort(T[], cmp)`,
    `Map.merge`, `computeIfAbsent`, `computeIfPresent`, `compute`, `Comparator.comparing`,
    `comparingInt`, `naturalOrder`, `reverseOrder`, and on a function value `apply`, `test`,
    `accept`, `get`, `applyAsInt`, `compare`, `run`, `andThen`, `compose`, `negate`, `and`,
    `or`, `reversed`, `thenComparing`.
  - A lambda body is traced like a helper of Main. When a covered call runs it, it gets
    its own frame (`via` the call), with its captured locals and parameters as bindings.
    The call's LIBRARY step follows its callbacks.
  - A method reference to a static helper of Main (`Main::even`) is a helper frame the
    same way. A reference to a covered pure JDK method (`Integer::sum`, `String::length`,
    ...) or to `System.out::println` has no frame.
  - Replay: the builder replays the call on its mirror with stand-in functions. A stand-in
    for a lambda or helper returns the recorded result after checking that it is the next
    recorded callback, of the same function, with the same arguments. A stand-in for a JDK
    reference runs that pure method; printing does nothing, since its output was already
    written. Any difference stops capture. Functions a library call makes
    (`Comparator.comparing`) are replayed too, so their own calls are checked the same way.
  - Function values have no identity (`==` on them is not covered). Lambdas are covered
    only in static code of Main.
  - A lambda whose expression body is not covered leaves its whole statement uncaptured.
    A block body may have uncaptured statements, like a helper.
  - Not covered: comparators kept inside a `PriorityQueue`, `TreeMap` or `TreeSet` (their
    order is user code), user functional interfaces, and JDK method references returning
    new objects (`String::toUpperCase`; a lambda `s -> s.toUpperCase()` is covered).
- 10d.4, sequential streams (trace 1.10.0 value kinds `stream` and `optional`):
  - Sources: `stream()` of a list, set or deque, `Arrays.stream(a)`,
    `IntStream.range`/`rangeClosed`, `String.chars()`.
  - Intermediate operations: `filter`, `map`, `mapToInt`, `mapToObj`, `boxed`, `sorted`,
    `distinct`, `limit`, `skip`, `peek`. Each is one step whose result is a stream value
    labelled by the calls that made it (`ArrayList.stream().filter(lambda at 5:28)`).
    Nothing runs yet.
  - Terminal operations: `sum`, `count`, `min`, `max`, `average`, `reduce`, `anyMatch`,
    `allMatch`, `noneMatch`, `findFirst`, `forEach`, `toList`, and `collect` with
    `Collectors.toList`, `toSet`, `joining`, `groupingBy`, `counting`, `toMap`. Each runs the
    pipeline: its lambdas are frames via the terminal call, in the JDK's real
    element-by-element order, and then comes its step with the result.
  - Optionals (`getAsInt`, `getAsDouble`, `get`, `orElse`, `isPresent`, `isEmpty`) show
    their contents (`OptionalInt[30]`).
  - The recorder gives streams and optionals `s<n>` tokens and collectors `g<n>`. The
    builder replays every call on a twin, so a terminal replay makes the same callbacks in
    the same order. A collected list or map is checked against the twin, nested lists
    included, and the twin becomes its mirror. A stream used twice fails as in Java.
  - `Stream.of(...)` (varargs) is not covered.

Element types: boxed primitives (`Integer`, `Long`, `Double`, `Character`,
`Boolean`, ...), `String`, traced classes that do not declare `equals`,
`hashCode` or `compareTo`, arrays, and these collections (nested). Keys of hashed
and sorted collections and `PriorityQueue` elements must be boxed primitives or
`String`. Constructors take no arguments (or a capacity; `StringBuilder` may take
a `String`). Boxing and unboxing are matched in bytecode but are not steps; boxed
values are shown as values (their identity is not modeled, so `==` between two
boxed values stays uncaptured).

Since Part 9d an `ArrayDeque` or `LinkedList` can be shown as a stack (top = first
element) or a queue (front = first, back = last) by the user's explicit choice
([frontend design](frontend-design.md)). The recorded contents are the same; only the
presentation changes.

String concatenation (`a + b + c`, `s += x`) is one EXPRESSION `+` step with all
operands of the flattened concatenation and the resulting string; operands must
be primitives, boxed primitives, `String` or `null` (no `toString()` of user
objects). The program is compiled with javac's inline concatenation strategy, so
concatenation uses `StringBuilder` calls that the tracer can match.

Still uncaptured: other library methods and classes, iterators used directly,
`for` over a `PriorityQueue`, parallel streams and stream operations not listed below, lambdas outside the calls listed below, comparators other than the JDK's reverse
order, other `Collections`/`Arrays` utilities (`fill`, `reverse`, `addAll`, ...), map views
used other than by a for-each, `format`, `Math.pow` (not exactly specified across
JVMs), and user `toString` in concatenation or output. Part 10d adds iteration, lambdas
and streams in later increments ([problem suite](problem-suite.md)).

## Contract 1.5.0

- Object kinds `collection` (`className`, `elements`), `map` (`className`,
  `entries` of `key` and `value`) and `builder` (`className`, `utf16`).
  `elements`/`entries` order: iteration order for lists, deques, stacks,
  `LinkedHash*` and `Tree*`; ascending key order for `HashSet` and `HashMap`
  (their iteration order is unspecified); poll (priority) order for
  `PriorityQueue`, never its internal array layout.
- LIBRARY step: `objectId` (receiver, or null for static methods), `method`
  (for example `java.util.List.add(java.lang.Object)`), `arguments`, `result`
  (null for void) and `after` (the receiver's new contents when the call changed
  it, else null). A new collection is an ALLOCATE step.
- EXPRESSION `+` may have more than two operands (a flattened concatenation).
- UNCAPTURED gains `collections` (re-read library objects that changed, full
  contents) and `unknownCollections`.
- `javaType` admits generic arguments (`java.util.List<java.lang.Integer>`).

## Recording and verification

The original library call runs in user code. The recorder spills its receiver and
arguments, records them with the result afterwards, and records the exception
(type and message) when the call throws. The trusted builder keeps its own mirror
of every library object built with the same JDK classes and replays each call on
it, so it checks the recorded result and derives the new contents itself.
Objects first seen by traced code and collections re-read after an uncaptured
statement are recorded with their contents.

## Results

Tracer host tests: 67 passed on 2026-10-19, including 4 new 8f cases:

- concatenation (one step per flattened concatenation, `s += x` included),
  `String` methods, pure statics and boxing;
- `StringBuilder` and every collection family, with each result verified by
  replay;
- nested collections, lists of user objects, and a list created by uncaptured
  code captured on first use;
- failures inside library calls, and collections changed by uncaptured
  statements re-read.

Older tests that used `Math.max`, `Math.abs`, concatenation or `String.valueOf` as
uncaptured examples now use still-uncovered calls (`Integer.max`, `StrictMath.abs`,
`Integer.toBinaryString`, `Integer.toString`). The tests found one gap: a receiver first
seen when its call was recorded had already changed. Receivers are now recorded
before the call.

Root reactor `clean verify` with live Docker: 152 Java tests (tracer 67, backend
86). Contract: 60 tests. Frontend: 19 reducer tests, build, and 8 Playwright tests
against the real backend and Docker.
