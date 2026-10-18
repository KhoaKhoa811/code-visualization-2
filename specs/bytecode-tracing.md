# Bytecode tracing — Part 7b producer

Implements [ADR 0004](decisions/0004-bytecode-instrumentation.md) for the
[first increment](java-support.md) inside `Main.main`, producing
[trace-v1](trace-format.md). Module: `runner/tracer/`. Part 7b runs on the host for
trusted test programs only; isolated container execution is Part 7c.

## Pipeline

1. **Analyze.** One javac task (`-g --release 21 -proc:none`) parses, attributes and
   generates `Main.class` from the exact submitted text. Compile errors give
   `compile_error` and unavailable capture. Then a walker visits the attributed tree.
   Any construct outside the supported list gives a `capture` diagnostic
   (`UNSUPPORTED_CONSTRUCT`, with its source range): the original program may run
   output-only, and nothing is instrumented.
2. **Plan.** For supported code the walker emits a plan: the relevant bytecode
   instructions javac will generate, in javac's code-layout order (basic `for`:
   init, condition, body, update; `while` and `if`: condition first). Each plan entry
   carries a static **site**: kind, source range, operator, declaration identity,
   static scope chain, and for conditions the condition tree. Compile-time constant
   expressions (JLS 15.29) produce no entries because javac folds them.
3. **Match and instrument.** ASM reads `Main.main` and lists its relevant
   instructions (stores, `iinc`, int-array loads/stores, `arraylength`, `newarray`,
   int arithmetic, conditional jumps, `getstatic`, invokes, and anything else that is
   not purely structural). The list must equal the plan entry by entry, or capture is
   unavailable (`TRACE_MAPPING`). Matched instructions are rewritten to call
   `codeviz.rt.Recorder` helpers that perform the instruction once and write
   `site + values`. User bytecode referencing `codeviz/` is rejected.
4. **Run.** The instrumented class runs with the recorder on the classpath and a
   raw record file outside stdout/stderr.
5. **Build.** A trusted builder combines the site table, the raw records and the
   process outcome into trace-v1. Source ranges, operators, declaration identity,
   binding lifetimes and previous values come from the site table and the builder's
   own reconstructed state, never from the user JVM.

## Supported in 7b

Locals of type `int`, `boolean`, `String` (literal, null or copy) and `int[]`
declared **with** an initializer; assignment to locals and `int[]` elements;
`int[]` from a constant-element literal or `new int[n]`; element read, `length`;
`+ - * / %` and unary `-` on int; `< <= > >= == !=` on int, boolean and references;
`! && ||`; prefix/postfix `++`/`--` on int locals (statement or value);
blocks, `if`/`else`, `while`, basic `for`, unlabeled `break`/`continue`, `return;`;
`System.out`/`System.err` `print`/`println` with no argument or one covered
`int`, `boolean` or `String` value. No other methods, classes, fields or calls.

Not yet: helper methods/recursion, uninitialized declarations, compound
assignment, string concatenation, other types, `switch`, `do`, for-each,
exceptions handlers/`throw`, constant `false` conditions, and constant leaves
mixed into non-constant conditions.

## Event mapping

| Site | Raw record | trace-v1 |
| --- | --- | --- |
| Declaration store | stored value | DECLARE (fresh bindingId, scope of its block) |
| Assignment store | stored value | ASSIGN, previous from builder state |
| `iinc` | new value | UPDATE; previous from state, result by position |
| `newarray` / literal | id, contents | ALLOCATE (literal elements: one Step) |
| `iaload` / `arraylength` | id, index, value / length | ARRAY_READ / ARRAY_LENGTH, checked against state |
| `iastore` | id, index, value | ARRAY_WRITE, previous from state |
| int arithmetic | operands, result | EXPRESSION |
| condition leaf jump | jump taken, operand values | comparison EXPRESSION; a lone boolean variable condition is `condition`; a variable inside `!`/`&&`/`||` is no Step |
| condition operators | derived from leaf results | `!`, `&&`, `||` EXPRESSION when determined, right operand `skipped` when short-circuited |
| any helper failure | exception class, message | EXCEPTION at the operation, then FRAME_END `unwind` |

A leaf's truth value comes from the recorded jump: if the jump opcode tests the
source operator it means true, if it tests the negation it means false (javac
never swaps operands). A single-operand jump (`ifle`, `ifnull`) compares with the
constant `0`/`null` operand stated in the source.

## Scopes

Each block and `for` header is a static scope. Before each recorded operation the
builder exits and enters scope instances so the active chain equals the site's
static chain. A declaration that executes while its binding is still live in the
same scope instance proves the block was re-entered (a new loop iteration), so
that scope is exited and entered again. This derives SCOPE_ENTER/SCOPE_EXIT only
from recorded operations and static structure; blocks with no recorded operation
leave no trace, which is unobservable because they hold no bindings.

## Termination and limits

Normal end records `R`: FRAME_END `main_return`, `completed`. A helper exception
records `x`: EXCEPTION, FRAME_END `unwind`, `failed`. Either gives complete
capture. A missing terminal record, malformed or inconsistent record (for example
a read that disagrees with reconstructed state) ends capture at the last safe
observable event (`partial`). The recorder stops at 16 MiB of raw records, writes
`L` and halts the JVM; the builder enforces the 8 MiB trace and 256 KiB record
limits from [execution isolation](execution-isolation.md) and reports `limited`
with `TRACE_LIMIT`. Trace integrity relies on the rejected recorder package and
on the first increment admitting no other calls (no reflection or file access).

## Container execution (Part 7c)

The producer runs inside the owned restricted container through the 6d/6e
phases; see [backend/TRACED_RUNS.md](../backend/TRACED_RUNS.md). Source is
admitted first (well-formed, at most 256 KiB UTF-8). Preparation stages the
shaded tracer runtime and the exact source through `docker exec -i` stdin. The
compile phase runs `codeviz.tracer.Prepare`, which writes the classes to run and
`plan.json` (sites, conditions, diagnostics, source SHA-256). The backend reads
the plan before any submitted code runs and rejects a plan for other text.

After the runtime phase, whatever stopped it, all processes of the run user are
killed and the raw records are read while the container still exists; only then
is the container stopped and removed. The recorder flushes at least every
100 ms, so a kill keeps a recent prefix; a torn final line is discarded. The
backend builds trace-v1 only after termination is confirmed. A latched stop cause
(timeout, output limit, cancellation) always yields partial capture with its
execution status and diagnostic; a compile-phase stop yields unavailable capture.

## Partial coverage (Part 8a)

Since Part 8a an unsupported statement in `main` no longer makes the whole program
output-only: it runs uncaptured as one `UNCAPTURED` step and the rest is traced.
Helper methods and other classes run untraced. Rules, bytecode mapping (javac
`-Xjcov` statement ranges) and contract 1.1.0 are in
[partial-coverage.md](partial-coverage.md). Programs using reflection, files,
processes, networking or threads stay output-only (`TRACE_INTEGRITY`), because
uncaptured code shares the recorder's JVM.

## Primitive types (Part 8b)

All primitive locals, declarations without initializer, bitwise and shift
operators, casts, long/float/double comparisons, compound assignment and the
conditional operator are traced; see [primitive-types.md](primitive-types.md).
