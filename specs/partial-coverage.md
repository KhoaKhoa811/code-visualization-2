# Graceful partial coverage (Part 8a)

Status: approved by the user on 2026-10-18 ("ok, continue part 8a"), as step 8a of
the [coverage roadmap](coverage-roadmap.md). Extends the
[bytecode tracer](bytecode-tracing.md) and [trace-v1](trace-format.md) to
contract version **1.1.0**.

## Problem

Before 8a, one unsupported construct anywhere in the file made the whole program
output-only. Most real programs contain at least one such construct, so most
programs showed nothing.

## Rule

Inside `main`, every statement is either **covered** (traced operation by
operation, as before) or runs as one **uncaptured region**: it executes exactly as
compiled, without instrumentation, and appears as a single `UNCAPTURED` step that
highlights the statement. Nothing about the inside of a region is recorded or
guessed. Its effects on visible state are handled factually:

1. **Bindings.** Every covered local that the region's source may assign
   (assignment, compound assignment, `++`/`--`) becomes `unknown`. It becomes known
   again at its next covered store. Reads of locals are not recorded, so an
   unknown binding is never refilled from a guess.
2. **Arrays.** If a region may write array elements (an element write, a call to
   anything other than a known side-effect-free library method, an object
   creation, or string concatenation of non-primitive operands), the recorder
   re-reads every array it knows at the next recorder call after the region. That
   point is after the region finished, so the re-read contents are what the region
   left. The `UNCAPTURED` step carries the arrays whose contents changed. If the run
   stops before any re-read (timeout, kill or uncaught failure inside the region),
   those arrays become `unknown`.
3. **New arrays.** An `int[]` first seen by covered code after being created by
   uncaptured code is captured with its current contents as `OBJECT_CAPTURE`
   bookkeeping (no click, no claimed allocation), like first-seen strings.

Side-effect-free calls (not dirtying arrays): `System.out`/`System.err`
`print`/`println`, and static or `String` methods of `java.lang.Math`,
`StrictMath`, `String`, `Integer`, `Long`, `Double`, `Float`, `Short`, `Byte`,
`Character` and `Boolean` whose declared parameters are primitives or `String`.

## Granularity

- Statements inside blocks, `if` branches and loop bodies degrade one by one.
- An unsupported `if`/`while` condition, or an unsupported `for` initializer,
  condition or update, makes the whole compound statement one region.
- A covered statement that names a local declared by uncaptured code is itself
  uncaptured (its variable is not visible).
- Helper methods, fields and other classes no longer block tracing; calls to them
  from `main` run inside regions. Their internals are not traced (8d).
- Still output-only for the whole program: package declarations, no
  `Main.main(String[])`, a `Main` that is not a plain class, and any program the
  bytecode check cannot map.
- A declaration without code (for example `int y;`) is silent: no step. Uses of
  `y` are uncaptured by the rule above.

## Bytecode mapping

The analyzer compiles with javac's `-Xjcov` option, which adds a
`CharacterRangeTable` attribute mapping each statement's source range to its
bytecode range. A region is the table entry whose statement range equals the
uncovered statement's tree range exactly (line and tab-expanded column, as javac
encodes it). The instrumenter skips that bytecode when matching the plan, and
inserts one recorder marker at the region's first instruction, after its label,
so every entry into the region, including jumps, is recorded once. The table is
removed from the instrumented class. A missing or ambiguous entry, or a column
beyond 1023, fails mapping and the program runs output-only (as before).
`-Xjcov` is a JDK-internal javac option; the mapping check is the safety net.

## Contract 1.1.0 (exact version)

Additive changes; 1.0.0 is retired because no traces are stored.

- Value kind `unknown`: allowed as a binding value, array element, `ASSIGN`/
  `UPDATE`/`ARRAY_WRITE` `previous`; never an operand, result or read value. A
  covered read of an unknown array element is an inconsistency.
- Observable `UNCAPTURED` event: `source` is the statement; `data` is
  `unknownBindings` (binding IDs), `arrays` (re-observed contents of changed
  arrays) and `unknownArrays` (object IDs whose elements all become `unknown`).
- `OBJECT_CAPTURE` may register an array object.
- `UPDATE` after an unknown binding records `previous: unknown`; its `result` is
  still exact (`value ∓ 1` for postfix).

Capture status keeps its meaning (whether recording reached termination). Gaps
are explicit as `UNCAPTURED` steps plus `UNSUPPORTED_CONSTRUCT` capture
diagnostics with source ranges.

## Verification

- Host tests: each degraded program's output and exit match the original; the
  trace passes the shared checker; unknown and re-observed state is correct;
  covered programs produce the same trace as before 8a.
- Live container and browser tests with mixed programs.
