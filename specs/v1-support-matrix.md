# V1 support matrix

Status: **draft for the user's agreement** (2026-10-23). [Java support](java-support.md)
section 7 requires this matrix to be agreed before V1 is declared finished. It records what
V1 visualizes step by step, what runs without step-by-step capture, and what never runs.
Nothing here is recognized by algorithm or by name: every row is a language or library
capability, tested with differently written programs ([problem suite](problem-suite.md)).

Program shape: one file `Main.java`, class `Main` without a package, with
`public static void main(String[])`, compiled and run with Java 21 in the restricted
container ([isolation review](isolation-review.md)).

## Outcomes

| Outcome | Meaning |
| --- | --- |
| **Traced** | Every operation is a recorded step, checked by the trusted builder, with backward replay. |
| **Uncaptured statement** | The statement runs as compiled; the step says what it may have changed. The rest of the program is traced. |
| **Output only** | The program runs and its output is shown; no playback is offered. |
| **Not run** | Compile errors; nothing executes. |

## Traced

| Area | Covered | Spec |
| --- | --- | --- |
| Primitive types | `byte`, `short`, `char`, `int`, `long`, `float`, `double`, `boolean`; exact values (IEEE-754 bits), overflow, all operators including bitwise and shifts, casts, conditional operator, `++`/`--`, compound assignment | [primitive types](primitive-types.md) |
| Statements | blocks, `if`, `while`, `do`, `for`, for-each over arrays and (10d) lists, sets, deques and map views, `switch` statements and expressions on int-like selectors, labels, `break`, `continue`, `return` | [statements](statements.md), [library](library.md) |
| Arrays | every primitive element type and `String`, nested arrays, aliasing, bounds failures | [statements](statements.md) |
| Methods | static methods of `Main`, recursion (depth limit 64), parameters, returns, unwinding | [methods](methods.md) |
| Objects | traced classes (top-level or static nested, no inheritance or interfaces) with fields, constructors, instance methods; object graphs with cycles | [objects](objects.md) |
| Strings and library | `String` methods, `StringBuilder`, concatenation (also with library values), `ArrayList`, `LinkedList`, `ArrayDeque`, `Stack`, `HashMap`/`TreeMap`/`LinkedHashMap`, `HashSet`/`TreeSet`/`LinkedHashSet`, `PriorityQueue` (natural or reverse order), map entries, library sorts, `Arrays.toString`/`equals`, `toCharArray`, `split`, printing library values | [library](library.md) |
| Lambdas and streams | lambdas and method references run by the covered library calls (`forEach`, `removeIf`, `sort`, `merge`, `computeIfAbsent`, `Comparator.comparing`, ...), stored functions, sequential streams with the listed intermediate and terminal operations and collectors | [library](library.md) |
| Exceptions | `throw`, `try`/`catch` (multi-catch), `finally` on every path, exception objects and messages, program-only stack traces | [exceptions](exceptions.md) |
| Input | live console with `Scanner` (documented subset) or `BufferedReader` over `System.in`; separate test inputs; input bounds, EOF, input-wait limit | [live console](live-console.md), [test inputs](test-inputs.md) |
| Views | variables, arrays, collections, object graph; confirmed tree, heap, list, stack and queue mappings; deque as stack or queue; suggested views (rank-1); synchronized grid of draggable panels | [frontend design](frontend-design.md), [custom mappings](custom-mappings.md), [view rankings](visualization-rules.md) |

## Uncaptured statement (the program is still traced)

- Calls to library methods and classes not listed in [library](library.md), for example
  `String.format`, `Math.pow` and `Arrays.fill`.
- Collection-held user comparators (`new PriorityQueue<>((a, b) -> b - a)`), `Stream.of`,
  parallel streams, JDK method references returning new objects (`String::toUpperCase`),
  user functional interfaces, iterators used directly, `for` over a `PriorityQueue`.
- User `toString` in output or concatenation; `==` on boxed values or functions.
- Lambdas outside static code of `Main`; a lambda whose expression body is not covered.
- A second `Scanner`/`BufferedReader`, and other ways of reading `System.in`.

## Output only

- Programs using APIs that could interfere with recording: reflection, `java.lang.invoke`,
  threads and `java.util.concurrent`, processes, files, networking, class loaders, `sun.`/`jdk.`
  packages ([coverage](partial-coverage.md)).
- A `Main` that is not a plain class, or a missing `public static void main(String[])`.
- Programs whose compiled code does not match the plan (reported as `TRACE_MAPPING`).

## Bounds every run keeps

| Bound | Value |
| --- | --- |
| Source | 256 KiB UTF-8 |
| Compile / runtime / interactive deadline | 10 s / 5 s / 120 s |
| Blocked input wait | 30 s of verified waiting |
| Output | 1 MiB shared |
| Raw records / plan | 16 MiB / 4 MiB |
| Call depth | 64 frames |
| Input | line 4 KiB, total 64 KiB, pending 16 KiB |
| Memory / tasks | 512 MiB container, 128 tasks |

## Not traced in V1 (these run, but as uncaptured statements)

- User classes with inheritance, interfaces or type parameters, records and enums: their
  objects are not traced, so statements using them run uncaptured.
- `switch` on `String` or enum selectors.

## Not in V1

- More than one source file, and packages.
- Public multi-user deployment (see the [isolation review](isolation-review.md)).

Agreeing this matrix declares V1 functionally complete. Changing a row later is a new part
with its own approval.
