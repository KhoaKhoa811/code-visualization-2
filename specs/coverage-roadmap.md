# Coverage roadmap: from the first slice to general Java

Status: planning document, 2026-10-18. Approved by the user on 2026-10-18 ("ok,
please write this up and then starting 7e"). Each part below still starts with its
own checklist; dependency installs and contract versions are decided in that part.

## Goal

The product visualizes the Java a user actually writes, as much of it and as
correctly as possible, from captured execution facts
([requirements](../requirements/PROJECT_REQUIREMENTS.md), confirmed 2026-10-06).
It never recognizes algorithms, identifiers or example text. Coverage grows by
language capability (types, expressions, statements, calls, objects, library
operations), and every combination of covered capabilities must work.

## Is the current path consistent with the goal?

Yes in architecture; not yet in breadth.

- **Facts, not templates.** The tracer (ADR 0004) instruments compiled bytecode
  and records each executed store, read, comparison, arithmetic result and
  failure. Any differently written program using covered constructs is traced
  the same way. The backend never inspects names or algorithm shapes.
- **Generic views.** The frontend reconstructs state from the trace and renders
  it by data shape (bindings, arrays, frames, later object graphs and logical
  collections), never by algorithm.
- **Narrow coverage.** Part 7b covers only `Main.main` with `int`, `boolean`,
  `String` and `int[]`, basic control flow and printing.
- **All-or-nothing capture.** One unsupported construct anywhere makes the
  whole program output-only. This is the main obstacle to "as much code as
  possible" and is addressed first after the visible slice.
- **No user objects in trace-v1.** The contract describes arrays and strings
  only. Linked structures and custom classes need a new contract version.

## Ordering principles

1. Prove the whole pipeline once with a visible slice (7e), then widen.
2. Prefer capabilities that unlock many programs (graceful degradation, all
   primitive types, methods, objects) over single acceptance programs.
3. Factual fallbacks before specialized views: the object graph shows any
   captured structure; logical list/tree/heap views refine it.
4. Each capability ships with differently written test programs, the shared
   checker, original-versus-traced behavior comparison and live container runs.

## Revised roadmap

| Part | Capability | Unlocks |
| --- | --- | --- |
| 7e | Frontend slice: editor, Run, polling, playback reducer, source highlight, variable and array views, console, statuses; browser check | First end-to-end visible result |
| 8a | **Graceful partial coverage.** Trace covered code and keep running through uncovered statements; mark them "not captured" instead of switching the whole program to output-only. Needs a safe-boundary rule: state touched by uncovered code becomes unknown, never guessed | Most real programs show something |
| 8b | All primitive types and operators: `long`, `double`, `char`, `byte`, `short`, `float`; casts, compound assignment, bitwise/shift, ternary, uninitialized declarations | Everyday arithmetic code |
| 8c | Statements: `do`, `switch`, for-each over arrays, labeled break/continue, multi-dimensional and other element-type arrays | Common loop and table code |
| 8d | Static methods and recursion (trace-v1 CALL/RETURN exists), call-stack view | Helpers, recursion, sorting with helpers |
| 8e | **Objects and fields**: new trace contract version with object allocation, field read/write, references; object-graph view promoted into Core V1 | Linked lists, trees, user classes |
| 8f | Strings and library collections as logical operations (`String`, `StringBuilder`, `ArrayList`, `ArrayDeque`, `HashMap`, `HashSet`, `PriorityQueue`) with list/stack/queue/map/set views | Library-based programs |
| 8g | Live `Scanner` console (WebSocket, ADR 0001 to be written) | Interactive programs |
| 8h | Exceptions: `throw`, `try/catch/finally` | Defensive code |
| 8i | Hardening: program-only stack traces, `BufferedReader` input ([live console](live-console.md)), editor scrolls to each step | Common input style, faithful errors |
| 9a–9d | Core V1 completion: grid and dragging, tree view (BST sort), heap view (heap sort), Deque stack/queue mode ([V1 scope](v1-scope.md)) | AC-12, 13, 14, 18, 23, 26, 31 |
| 10a–10d | V1.1: separate test inputs, custom mappings, view rankings, broader problem suite ([V1 scope](v1-scope.md)) | Remaining V1 |

The 6g isolation-gap review stays deferred until before any public deployment.

## Changes from the earlier plan

- **Object-graph fallback moves from V1.1 into Core V1 (8e).** It is the general
  view that lets any user-defined structure display without per-structure code.
- **Graceful partial coverage is new (8a)** and comes before broadening types.
- **Trace contract evolution:** objects and fields need a new `schemaVersion`
  (exact-version dispatch, [ADR 0003](decisions/0003-versioned-trace-contract.md)).
  The version number and migration are decided in 8e.

## Risks

- Graceful degradation must stay factual: once uncovered code may have changed
  a value, later views must show it as unknown until a covered operation
  observes it again. This needs a precise rule and tests before implementation.
- Library collections are captured as logical operations at known calls, never
  by reading Java internals; each operation needs an exact contract.
- Bytecode-to-source matching becomes harder with richer constructs; keep the
  plan/bytecode mismatch check as a safety net that degrades only the affected
  statement once 8a exists.
