# Java support and capture semantics

Status: Milestone 0, Part 2, 2026-10-06. Proposed implementation baseline for
review. No capability below is implemented or runtime-verified in this repository.
Approval to write this specification does not authorize application implementation.

Read with [requirements](../requirements/PROJECT_REQUIREMENTS.md) and
[observable Step behavior](trace-format.md). Confirmed product scope remains
general user-authored Java visualization, developed through reusable language
capabilities. Source text, algorithm names, formatting, and chosen identifiers
must never act as recognition templates.

## 1. Admission and visualization are separate decisions

Use Java 21 compilation/runtime with the Main.java / Main.main convention.
The proposed first tracing entry is `public static void main(String[] args)` in
class Main, without a package. Imports are allowed; importing a type does not
establish capture coverage for its methods. Other valid single-file constructs
remain subject to execution policy and may use output-only execution.

| Outcome | Required behavior |
| --- | --- |
| Compilation failure | Compiler diagnostic, no successful execution or trace claim |
| Execution-policy rejection | Explain the forbidden capability; do not run it through a fallback |
| Admitted and fully covered | Execute once in isolation and offer validated recorded playback |
| Admitted, known tracing limitation before execution | Execute original code once in isolation with output and a coverage diagnostic |
| Recording becomes incomplete during execution | Retain only a validated safe prefix; never replay beyond missing state-changing facts or automatically rerun original code |
| Failure, cancellation, or resource limit | Explicit terminal status and any safe partial trace; never label it successful completion |

Diagnostics identify the construct, source range, and missing capability where
known. State execution outcome separately from visualization completeness.
Runtime cutoff/termination mechanics require the isolation and trace contracts;
do not implement a partial path until its safe boundary can be proved.

File/network access, reflection, native execution, external dependencies, and
concurrency remain subject to the requirements' proposed execution restrictions.
Output-only execution does not relax isolation, resource bounds, or cleanup.
This document does not establish an enforceable security policy.

## 2. Proposed delivery coverage

Every row is planned, not verified. The initial target is deliberately a first
increment, not a permanent definition of what students may write. Unsupported
combinations must be diagnosed; isolated node support alone does not prove that
all compositions preserve semantics.

| Capability | First scalar/array increment | Later required V1 work or unresolved detail |
| --- | --- | --- |
| Scalar types | int, boolean; String literal/null bindings and captured text/reference identity | byte, short, char, long, float, double with exact typed capture and conversion coverage |
| Array types | One-dimensional int[]: literal-only initializers, new int[length], length, access, write, alias, null | Broader element types, dimensions, initializer expressions and operations require an agreed matrix; remain V1 work |
| Local bindings | Explicitly typed declarations, optional initializer, assignment, final locals, nested block lifetimes | var and additional declaration forms require verified type-resolution contexts |
| Integer expressions | Unary +/-, binary +, -, *, /, %, comparisons; parentheses | Bitwise/shift/cast and mixed-numeric coverage require explicit semantics and tests |
| Boolean expressions | !, &&, \|\|, ==, !=; supported comparisons as operands | Eager Boolean operators and conditional expressions need separate coverage |
| References | Assignment, null, == and != for covered references | Custom objects/fields, casts and richer reference contexts follow object capture |
| Updates | Prefix/postfix ++/-- on int locals, including nested covered expressions | Array-element updates and compound assignment need separate capture/decomposition proof |
| Control flow | Blocks, if/else, while, basic for, unlabeled break/continue, return from main | Enhanced for, do/while, switch, labels, and other forms need explicit coverage decisions |
| Output | Actual System.out/System.err print/println calls with covered scalar/String arguments | Exact overload inventory and console transport contract before implementation |
| User methods | Main frame represented; helper calls are outside the first increment | Static fixed-arity helpers and recursion described in section 5; implement before tree/heap sorting |
| Exceptions | Capture failures of covered operations, preserve earlier effects, report uncaught failure | Explicit throw, handlers, finally and resource cleanup need full tracing coverage before partial playback through them |
| Libraries/input | No inferred internals or arbitrary callback evaluation | Scanner, strings, collections and separate test inputs remain required V1 features with exact operation contracts pending |

Types and constructs outside the first column are not rejected merely for lacking
tracing support. Admission uses section 1. Coverage expands by resolved operation
and context, not by adding program-specific branches.

Java's actual typed values remain authoritative. Initial int coverage must retain
overflow and division behavior; boolean is not a numeric surrogate. Later long
and floating-point coverage cannot be enabled until transport preserves their
values. Uninitialized locals must not appear as zero or null; show an explicit
unassigned state and let the compiler enforce definite assignment. Java type,
value, and initialization rules are defined in [JLS chapter 4](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html).

## 3. Operation and binding capture

Maintain a declaration identity separate from its name, plus an invocation
identity. Repeated execution of a declaration in a loop creates a new binding
lifetime; leaving that block ends it. Reusing a display name does not merge
bindings. Capture source locations before transformation.

### Scalar expressions and updates

Record evaluated operands, typed result, and operation source. Preserve Java's
evaluation order and abrupt completion. Short-circuiting records which operand
was skipped, never a value obtained by evaluating it for tracing. Prefix/postfix
updates record old value, committed new value, and returned expression value in
one update Step. For `int y = x++;`, update x precedes declaration of y. Literals
and simple variable lookup add no separate clicks. See [JLS expression evaluation and updates](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.7).

### Arrays and identity

An array identity belongs to an actual captured allocation, not its variable name
or contents. Null, an empty array, and a missing captured object are different
states. Bindings and panels referring to one array must share its recorded state.
Rebinding a local does not mutate its old array. Leaving scope removes a binding,
not all other references or a speculative garbage-collection event.

For the first increment, successful array creation is one Step with complete
captured initial contents, followed by the receiving declaration/assignment.
Literal-only initializer elements create no additional clicks. Support initializer
expressions with side effects only after their allocation/element boundaries are
specified. Array length access is one read Step with its own expression highlight;
it is not an array-element read. A failed allocation/access is an exception Step,
not a successful creation/read/write. Arrays have fixed length, default-initialized
elements for length-based creation, and runtime bounds checks. See [JLS arrays](https://docs.oracle.com/javase/specs/jls/se21/html/jls-10.html).

For a simple array write, retain the evaluated destination and capture a committed
change only on success. Never reevaluate the index or RHS for metadata. Do not
insert an early element read to obtain its previous value: that can move a null
or bounds failure before RHS effects. Array compound assignment has different
capture requirements and must not reuse an unproved simple-assignment rewrite.
See [JLS assignment evaluation](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.26).

### Control flow and scope

Each evaluated condition contributes the Steps of its actual expressions. A
simple boolean-variable condition gets one condition Step; comparison/operator
conditions do not get an additional duplicate branch click. Braces, loop entry,
iteration bookkeeping, break/continue transfer, and scope bookkeeping add no
standalone clicks. Local updates in a for-loop update clause remain observable.
Preserve basic-for ordering, including continue reaching the update clause;
while-continue returns to its condition. Java determines reachability, declaration
scope, and abrupt completion. See [JLS statements](https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html).

## 4. Capture cannot guess library behavior

Program output is captured separately from trace transport. Do not invoke user
toString, getters, comparison callbacks, or collection iteration merely to
construct a diagram. Source-invoked calls still execute according to Java.

Unknown calls cannot be assumed pure. In the first increment, an unhandled call
causes a pre-execution coverage limitation even if the rest of the method uses
covered syntax. Later logical library adapters must specify captured inputs,
results, mutations, exceptions, alias effects, and callback coverage.

For example, Arrays.sort is not permission to invent comparison/swap events.
User-written sorting can expose those events through its covered operations.
An admitted library sort may use output-only execution until a truthful adapter
is available. Never infer PriorityQueue's private heap from iteration order.

## 5. Early method and recursion semantics

These are proposed rules for the required method increment, not first-increment
implementation. Start with resolved static fixed-arity methods in Main, using
the covered types for parameters/results plus void results. Overloaded calls must
identify the resolved declaration, not a name alone. Instance methods, constructors,
generic/varargs calls and dispatch need additional coverage before enabling them.

- Main has its own frame in the initial playback state. Covered helper entry is
  one observable Step after arguments complete, showing a new invocation ID,
  caller ID, call-site range, resolved method, and parameter bindings.
- Evaluate actual arguments once in Java order. Primitive and reference argument
  values are passed by value; shared references retain the same object identity.
  A throwing argument produces no entered callee frame. See [JLS method invocation](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.4).
- Each recursive invocation has separate locals/parameters, including identical
  names. Method identity, invocation identity, and object identity are distinct.
- Evaluate a return expression in its callee frame. Then record one return/exit
  Step: end that frame's binding lifetimes, resume the caller, and retain the
  recorded result and departed-frame identity on the operation. The caller's
  receiving assignment/declaration is a later Step. An ignored result creates
  no invented binding. A void return has no result value.
- Falling out of a void method is a recorded implicit exit. Locate it at the
  method body boundary and label it implicit; do not fabricate a return statement.
- A failed operation is not a normal return. Uncaught propagation records which
  frames end as ordered bookkeeping, without multiplying the original exception
  into duplicate failures. Catch/finally coverage must be specified before tracing
  through handlers; do not synthesize their behavior.
- Backward replay across a return restores the prior accepted prefix, including
  the callee frame, locals, shared objects, and source highlight. No method is
  called during reconstruction.
- A recorded depth limit is distinct from a Java StackOverflowError. Select a
  concrete enforced bound and boundary semantics in the isolation specification;
  never claim bounded recursion is verified before that enforcement is tested.

Main's final exit follows the terminal-bookkeeping rule in trace-format.md,
without a blank extra completion click. Helper entry/exit Steps are explicit
user-method operations, not internal tracer calls exposed as program behavior.

## 6. Planned semantic acceptance cases

These are expected results for future tests, not executed test reports. Fragments
run within Main.main unless a helper is shown. Compare original/instrumented
behavior and inspect intermediate states, not only the final output.

| ID | Program/variation | Required evidence |
| --- | --- | --- |
| J-01 | `int x = 2; int y = x++;` | x becomes 3 once; update expression returns 2; y then becomes 2 |
| J-02 | `int[] a = {4, 1}; int i = 0; a[i++] = a[1] + 2;` | i becomes 1 once; read a[1], add, then write a[0]; final a = [3, 1] |
| J-03 | `{ int x = 1; } { int x = 2; }` | Separate declaration/binding lifetimes; first x is absent when second exists |
| J-04 | `int[] a = {2}; int[] b = a; b[0] = 8; b = null;` | One array becomes [8]; a still refers to it after b becomes null |
| J-05 | `int[] a = {4}; int i = -1; boolean ok = i >= 0 && a[i] == 4;` | False condition; no array access or bounds exception |
| J-06 | `int[] a = {0}; int x = 0; a[2] = ++x;` | x becomes 1 before bounds failure; a unchanged; no successful array write |
| J-07 | `int x = 1; int y = 0; x = x / y;` | ArithmeticException; x remains 1; no successful assignment |
| J-08 | Sum [3, 1, 2] with a while loop and separately with a basic for loop | Both total 6; events follow each program's actual operations, not a shared canned trace |
| J-09 | User-written bubble sort and selection sort over [3, 1, 2], then renamed/reformatted variants and duplicate/empty inputs | Correct final arrays and per-operation trace; no algorithm recognition branch |
| J-10 | Admitted Arrays.sort call without an adapter | Output-only diagnostic, not invented internal comparison/swap playback |
| J-11 | Planned helper `static void set(int[] p) { p[0] = 9; p = null; }` | Caller array changes; parameter rebinding does not rebind caller; entry and exit remain replayable |
| J-12 | Planned helper `static int sum(int n) { if (n == 0) return 0; return n + sum(n - 1); }`, call sum(2) | Three distinct helper frames; returned results 0, 1, 3; backward replay restores frames |

J-06 specifically guards against an instrumentation rewrite that checks the
destination too early. It must be checked against original Java execution before
accepting a transformation. Numeric overflow, null and empty arrays, cancellation,
capture truncation, and method/argument exceptions need additional future cases.

## 7. Remaining required V1 coverage

Strings and broader arrays; lists, stacks, queues, maps, sets; custom object
allocation/field mutation and factual bounded graphs; tree and heap operations;
methods/recursion; live Scanner and separate test inputs; mappings, synchronized
grids, and evidence-based suggestions all remain required V1 work. Their exact
operation inventories and capture bounds are unfinished specification work.

Before enabling an increment, document its syntax/types, composition contexts,
operation boundaries, identity/alias behavior, exceptions, and acceptance cases.
Update the trace contract before incompatible producer/consumer changes. Agree
the complete V1 support matrix before declaring V1 finished; this initial matrix
does not silently defer agreed requirements to V2.
