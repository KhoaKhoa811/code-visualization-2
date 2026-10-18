# Trace-engine spike report (Part 7a)

Date: 2026-10-18. Author: Claude Code. Decision record:
[ADR 0004](../../specs/decisions/0004-bytecode-instrumentation.md).

## Question

How should the tracer capture runtime facts: by rewriting Java **source**
(the earlier proposed baseline, prototype not present in this repository) or by
instrumenting compiled **bytecode** with ASM? The tracer must preserve Java
semantics exactly, never evaluate an expression twice, keep object identity and
distinct recursive frames, and grow toward "visualize any program the user writes".

## What was built

- `codeviz.spike.Instrumenter` (ASM 9.9.1 tree API) rewrites compiled classes.
  Each covered instruction is replaced by a call to a `codeviz.rt.Recorder`
  helper that **performs the original operation once** (with the same exception)
  and then records it. Values come from the operand stack, so nothing is re-evaluated.
- Covered: method enter/normal exit/exceptional exit with distinct frame IDs, named
  local stores and `iinc` (names from `javac -g` local-variable tables), `int[]`
  reads/writes, reference-array writes, array allocation, `int` comparisons.
- `Recorder` writes JSON lines to a separate file, never to stdout/stderr.
- Programs are trusted repository fixtures, run as separate host JVM processes.
  This is not the isolated runner; container execution is 7b work.

## Results

`mvn test` in `runner/tracer-spike`: **20 tests, 0 failures**
(`.tools/part-7a-spike.log`, local).

| Check | Result |
| --- | --- |
| Instrumented vs original behavior (stdout, exit code, uncaught exception line) for ac01-05, ac07, ac10, recursion, broad | Identical for all 9 programs |
| `broad` program: lambdas, streams, `TreeMap.merge`, string `switch`, `try/catch/finally`, nested `Node` class, `long`/`double`, `char[]`, `ArrayDeque` | Runs identically; 230 events; uncovered operations simply not recorded |
| AC-01 scalar | `x=5`, then `x=8` |
| AC-02 array | allocation, three initializer writes, then only `[1]=9` |
| AC-03 loop | each read and write in order |
| AC-04 bubble sort | 43 events: 11 comparisons with operands, 7 writes, final state sorted |
| AC-05 alias | `a` and `b` hold the same object ID; write visible through both |
| AC-07 side effects | `calculate()` entered exactly twice; short-circuited call never runs |
| AC-10 out of bounds | successful `[0]` write recorded, failed `[3]` write never recorded, then `THROW_EXIT` |
| Recursion | frames 1-4 distinct, each `n` and `rest` bound to its own frame, returns 1, 2, 6 |
| Source-rewrite comparison (AC-07) | naive rewrite (re-reads `values[i++]`) **changes program output**; correct rewrite needs every subexpression hoisted into temporaries in evaluation order, compound assignment decomposed and short-circuit turned into control flow |

## Comparison

| Concern | Bytecode (ASM) | Source rewriting |
| --- | --- | --- |
| Semantic fidelity | Structural: javac already fixed evaluation order; helpers do the same instruction once | Must be re-proven per construct; easy to double-evaluate (shown by the naive rewrite) |
| Programs that compile but use unsupported constructs | Still run identically; only those facts are missing | Must be detected and rejected, or risk wrong rewrite |
| Growth toward "any program" | Add helpers per instruction family (fields, collections call sites) | Add a rewrite per expression form and per context |
| Expression contexts (loop conditions, arguments, lambdas, ternaries) | No special handling | Statements cannot be inserted; needs restructuring |
| Source positions | **Lines only**; expression ranges need a mapping step | Exact ranges for free |
| Comparison meaning | Records the JVM branch (`IF_ICMPLE 3 1 jump=false` for `a[j] > a[j+1]`); source operator needs mapping | Source operator directly |
| Compiler artifacts | Visible: array initializers as allocation+writes, synthetic `lambda$main$0` frame | Not visible |
| Dependency | ASM (small, standard, no transitive deps) | JavaParser + SymbolSolver |

## Gaps and risks found (must be handled in 7b or later)

1. **Expression highlighting.** Bytecode has line numbers only. Plan: parse the same
   source with the JDK's own `com.sun.source` tree API (no new dependency) and match
   operations on a line, in evaluation order, to expression ranges. Unverified.
2. **Comparison direction.** javac often emits the negated branch (`i < n` becomes
   `IF_ICMPGE exit`). Until the mapping above exists, show operands and the taken path.
3. **Compiler artifacts.** Group array-initializer writes into one step; hide or
   label synthetic methods (`ACC_SYNTHETIC`); compiler temporaries without
   local-variable entries are already skipped (verified for for-each and `switch`).
4. **Not yet covered:** constructors and static initializers, fields, object-array
   reads (need the component type cast), other primitive arrays, `long`/`double`
   comparisons, strings and collection operations (call-site helpers, same pattern).
5. **Class hierarchy for stack-map frames.** The spike falls back to `Object` for
   unknown user classes; production must resolve them from the compiled class set.
6. **Stack traces** gain `Recorder` frames below the first line. Filter them from
   user-visible stderr or print uncaught exceptions ourselves.
7. **Trace integrity.** The recorder runs in the user's JVM. User code could call
   `codeviz.rt.Recorder` directly and forge events. Reject user bytecode that
   references `codeviz/` before instrumentation (same issue exists for source rewriting).
8. **Volume.** Bubble sort on 3 elements gives 43 events; growth is O(n²). The
   existing trace-size limits in `specs/execution-isolation.md` must apply.

## Recommendation

Use **bytecode instrumentation (ASM) for runtime facts** and the **JDK compiler tree
API for source ranges and expression meaning**. Bytecode gives correct behavior for
every program that compiles, which matches the product goal; source ranges are a
mapping problem that does not risk changing program behavior. 7b should turn this
spike into the first producer: trace-v1 output, items 1-3 and 5-8 above for the
scalar/array/comparison/frame increment, and execution inside the existing container lifecycle.
