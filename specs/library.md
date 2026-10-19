# Strings and library collections (Part 8f)

Status: approved by the user on 2026-10-19 ("ok", after the 8f proposal), step 8f
of the [coverage roadmap](coverage-roadmap.md). Builds on [objects](objects.md)
(Part 8e). Trace contract **1.5.0**. Library internals are never read or inferred:
every library operation is one recorded call with its arguments and result.

## Covered library operations

Calls in traced code to these methods are steps (a LIBRARY event):

| Receiver | Methods |
| --- | --- |
| `String` | `length`, `charAt`, `substring`, `indexOf`, `lastIndexOf`, `equals`, `compareTo`, `contains`, `startsWith`, `endsWith`, `isEmpty`, `toUpperCase`, `toLowerCase`, `trim` |
| `StringBuilder` | `new`, `append`, `insert`, `reverse`, `toString`, `length`, `charAt`, `setCharAt`, `deleteCharAt`, `setLength` |
| `List` (`ArrayList`, `LinkedList`) | `new`, `add`, `add(i, e)`, `get`, `set`, `remove(int)`, `remove(Object)`, `size`, `isEmpty`, `contains`, `indexOf`, `clear` |
| `Deque`/`Queue` (`ArrayDeque`, `LinkedList`), `Stack` | `new`, `push`, `pop`, `peek`, `offer`, `poll`, `add`, `remove()`, `addFirst`, `addLast`, `offerFirst`, `offerLast`, `pollFirst`, `pollLast`, `peekFirst`, `peekLast`, `removeFirst`, `removeLast`, `size`, `isEmpty`, `empty`, `clear` |
| `Map` (`HashMap`, `TreeMap`, `LinkedHashMap`) | `new`, `put`, `get`, `getOrDefault`, `containsKey`, `remove(key)`, `size`, `isEmpty`, `clear` |
| `Set` (`HashSet`, `TreeSet`, `LinkedHashSet`) | `new`, `add`, `contains`, `remove`, `size`, `isEmpty`, `clear` |
| `PriorityQueue` (natural order) | `new`, `add`, `offer`, `poll`, `peek`, `remove()`, `size`, `isEmpty`, `clear` |
| static | `Math.max`, `Math.min`, `Math.abs`, `Math.sqrt`, `Integer.parseInt`, `String.valueOf` (primitives), `Character.isDigit`, `isLetter`, `isUpperCase`, `isLowerCase`, `toUpperCase`, `toLowerCase` |

Element types: boxed primitives (`Integer`, `Long`, `Double`, `Character`,
`Boolean`, ...), `String`, traced classes that do not declare `equals`,
`hashCode` or `compareTo`, arrays, and these collections (nested). Keys of hashed
and sorted collections and `PriorityQueue` elements must be boxed primitives or
`String`. Constructors take no arguments (or a capacity; `StringBuilder` may take
a `String`). Boxing and unboxing are matched in bytecode but are not steps; boxed
values are shown as values (their identity is not modeled, so `==` between two
boxed values stays uncaptured).

String concatenation (`a + b + c`, `s += x`) is one EXPRESSION `+` step with all
operands of the flattened concatenation and the resulting string; operands must
be primitives, boxed primitives, `String` or `null` (no `toString()` of user
objects). The program is compiled with javac's inline concatenation strategy, so
concatenation uses `StringBuilder` calls that the tracer can match.

Still uncaptured: other library methods and classes, iterators and enhanced
`for` over collections, streams and lambdas, comparators, `Collections`/`Arrays`
utilities, map views (`keySet`, `values`, `entrySet`), `split`, `format`,
`Math.pow` (not exactly specified across JVMs), and user `toString` in
concatenation.

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

Pending.
