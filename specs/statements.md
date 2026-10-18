# Statements and arrays (Part 8c)

Status: approved by the user on 2026-10-18 ("Please continue implementing part
8c"), step 8c of the [coverage roadmap](coverage-roadmap.md). Extends the
[bytecode tracer](bytecode-tracing.md), [partial coverage](partial-coverage.md) and
[primitive types](primitive-types.md); trace contract **1.3.0**.

## Newly traced in `main`

- `do { ... } while (c);` loops, including constant conditions.
- Labeled statements and labeled `break`/`continue` (jumps only; no steps).
- `switch` statements on `int`, `char`, `short` and `byte` selectors, in both
  forms (`case 1:` groups with fall-through and `break`, and `case 1, 2 ->` rules),
  with or without `default`.
- `switch` expressions on the same selector types when they are the whole
  initializer of a local declaration or the whole right side of a local
  assignment (javac spills a non-empty operand stack elsewhere). Rule bodies may be
  expressions or blocks ending in `yield`.
- Enhanced `for` over arrays: `for (T v : array)`.
- Arrays of every primitive type and of `String`, and arrays of those arrays at any
  depth (`int[][]`, `char[][]`, `String[][]`, ...): creation with a length, with
  2 or 3 dimension lengths, or with an initializer (whose elements may now be any
  covered expressions, including nested initializers); element read and write;
  `length`; compound assignment and `++`/`--` on numeric elements; `boolean[]`
  elements as conditions; `==`/`!=` and `null` tests on array references.

Still uncaptured: `switch` on `String`, enums or boxed values, pattern and `null`
cases, an empty `switch`, a `switch` expression in other positions, a variable
declared in one `switch` case group and used in a later group, enhanced `for`
over `Iterable` collections, arrays of other element types, more than three
dimension lengths in one creation, and everything listed for 8d and later.

## Steps

| Source | Steps |
| --- | --- |
| `do body while (c);` | the body's steps, then the condition's steps, per iteration |
| `break label;`, `continue label;` | none (scope changes are bookkeeping) |
| `switch (e) { ... }` | the selector's steps, then one SWITCH step highlighting the chosen label, then the chosen statements |
| `int k = switch (e) { case 1 -> a; ... };` | the selector's steps, SWITCH, the chosen body's steps, then DECLARE or ASSIGN |
| `for (T v : a) body` | `a`'s steps and one ARRAY_LENGTH of the array, then per element ARRAY_READ and DECLARE `v`, then the body's steps |
| `new int[2][3]` | one ALLOCATE of the outer array; the inner arrays are captured as bookkeeping in the same step |
| `{e1, e2}` | the element expressions' steps in order, then one ALLOCATE with the final contents |
| `g[i][j] = x` | ARRAY_READ of `g[i]` (a reference), then ARRAY_WRITE on the row |

The for-each loop's hidden copy, length and index variables (javac
`Lower.visitArrayForeachLoop`) are matched in bytecode but are never shown; its
hidden index test and increment are not steps. The ARRAY_READ of each element
highlights the iterated expression, and the following DECLARE highlights the loop
variable.

## SWITCH event (contract 1.3.0)

Observable. `data`: `value` (the selector's typed value) and `matched`: `case`,
`default` or `none`. The source range is the chosen `case`/`default` label (from
`case` to the end of its last constant); with no match and no default it is the
selector expression. No state change. The trusted builder computes `matched` from
the recorded selector value and the folded case constants (JLS 14.11.3); a
recorded value is the only runtime fact.

## Arrays (contract 1.3.0)

`elementType` and binding `javaType` admit array types of any depth
(`int[]`, `java.lang.String[][]`, ...). An element of a reference-element array is
a `ref` or `null`. All other array rules are unchanged: identities come from
recorded allocations or first observations, rows can be shared (aliases), and
uncaptured statements that may write arrays cause every known array, of any
element type, to be re-read or become unknown.

Raw records gain a typed array form: `A<id> <class> <len> <values...>` where
`<class>` is the JVM class name (`[I`, `[[D`, `[Ljava.lang.String;`); values use
the 8b tokens and `r<id>`/`n` references. Element arrays of a newly seen array are
recorded first, so every reference resolves to an earlier capture.

## Mapping rules

- `switch`: `tableswitch`/`lookupswitch` (one SWITCH plan item) after the
  selector; case bodies in source order. A recorder call before the switch
  instruction records the selector and returns it unchanged.
- Allocation: `newarray`, `anewarray`, `multianewarray` are matched per type and
  dimension count and replaced by recorder helpers that allocate exactly once with
  the JVM's exception behavior.
- Element access: `Xaload`/`Xastore` per element type (`baload`/`bastore` serve
  both `byte[]` and `boolean[]`; the helper is chosen from the static type);
  reference loads are followed by a `checkcast` to the static element type.
- Initializers: the allocation, then per element its value and its `Xastore`;
  the ALLOCATE record is written after the last store.

## Results

Pending.
