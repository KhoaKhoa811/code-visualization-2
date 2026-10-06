# Trace format: observable Step behavior

Status: Milestone 0 specification, Part 1, 2026-10-06. The user approved defining
Step behavior. Detailed choices below are a proposed baseline for review, not
verified implementation or a complete wire-format contract.

Source of truth: [project requirements](../requirements/PROJECT_REQUIREMENTS.md),
especially sections 7, 8, and acceptance cases AC-07, AC-08, AC-10, AC-15,
AC-17, AC-25, AC-26, and AC-33.

## 1. Confirmed behavior

- One manual Step advances exactly one observable operation, not one source line.
- Highlight the source expression associated with that operation and show its
  recorded result/state at the same cursor in every panel.
- Different programs use the same language-operation rules. Examples below are
  acceptance cases, never exact-source or algorithm recognition templates.
- Playback uses recorded execution after termination. Forward, backward, and
  restart do not execute Java or request input again.
- Capture must preserve evaluation order, side effects, exceptions, identities,
  and source-version association. Missing facts must never be guessed.

## 2. Proposed observable operations

These choices determine click granularity for the first scalar/array increment.
They do not define the entire Java support matrix or authorize implementation.

| Operation | One observable Step shows |
| --- | --- |
| Successful initialized local declaration | New binding, type, and recorded initial value/reference |
| Successful local assignment/update | Binding's new value; updates also record old value and expression result when different |
| Successful array creation | New array identity, length, type, and captured initial contents |
| Successful array element read | Array identity, evaluated index, and returned value; contents unchanged |
| Successful array element write | Array identity, index, previous value, and committed new value |
| Arithmetic operation | Evaluated operands and result; bindings unchanged unless a separate update commits |
| Comparison | Evaluated operands and Boolean result |
| Evaluated Boolean operator | Evaluated inputs and result, including explicit skipped operands for short-circuiting |
| Failing covered operation | Exception at the failing expression, without a successful result/write event |

Literal evaluation, simple local-variable lookup, parentheses, and assignment
result propagation do not create separate clicks. Their values are captured as
operands of the relevant operation. A standalone Boolean condition without a
comparison/operator, such as `if (ready)`, needs one condition-evaluation Step.
When a comparison/operator already supplies the condition result, branching is
attached bookkeeping and does not add a duplicate click.

Nested operations complete in actual execution order. For example, arithmetic
inside an assignment precedes the assignment Step. Array creation and the local
binding that receives its reference are separate operations. An array may thus
exist as a temporary recorded object before the binding is introduced.

No synthetic "swap" replaces Java's individual reads, declarations, and writes.
Automatic playback visits the same boundaries as manual stepping; animation
must not collapse multiple observable operations into one manual Step.

## 3. State and highlighting at a cursor

Cursor zero is the recorded initial state before the first observable operation.
Cursor k is the state immediately after observable operation k has completed or
failed. Successful mutation Steps show committed state, not a predicted change.

Read/comparison/arithmetic Steps may leave all persistent values unchanged.
Their evaluated operands and result still make the Step observable. Temporary
expression values are recorded facts, not Java variables invented by the UI.

Each operation identifies its original source range, source version, and frame
where applicable. Highlight the smallest source expression that represents the
operation. For a write, highlight the assignment; for a read, the access; for
arithmetic/comparison, the corresponding expression. Nested ranges may overlap.
Several consecutive Steps can highlight parts of the same line.

Bookkeeping records have order relative to observable operations but no extra
clicks. Apply records between successive operations when advancing to the next
operation; do not apply facts from beyond that operation's boundary. Scope exit
removes bindings at the next accepted boundary. Trailing cleanup is applied only
when playback reaches the end of the complete recorded sequence. At the last
operation, show terminal playback state without requiring a blank extra click.

Run status may be known before playback begins; it does not permit revealing
future variable values, objects, or interpretation evidence at an earlier cursor.
At a capture cutoff, apply only validated records in the safe prefix, not later
cleanup or state from execution that continued without adequate recording.

## 4. Worked acceptance examples

Snippets are fragments of an ordinary admitted Java program. A stated starting
state is a recorded prefix for the example, not state inferred by the frontend.
These expected Steps are specification examples; no runtime tests have run.

### A. Declaration and assignment on one line

```java
int x = 5; x = 8;
```

| Cursor | Highlight | Resulting state |
| --- | --- | --- |
| 0 | None | No x binding |
| 1 | `int x = 5` | x = 5 |
| 2 | `x = 8` | x = 8 |

Backward from 2 restores x = 5 and the declaration highlight. Restart restores
cursor zero. Neither action reruns the program.

### B. Read, calculate, and write on one line

Starting state: a refers to array A = [3, 1], i = 0, j = 1.

```java
a[i] = a[j] + 1;
```

| Step | Highlight | Recorded result | Array A afterward |
| --- | --- | --- | --- |
| 1 | `a[j]` | Read A[1] = 1 | [3, 1] |
| 2 | `a[j] + 1` | 1 + 1 = 2 | [3, 1] |
| 3 | `a[i] = a[j] + 1` | Write A[0]: 3 to 2 | [2, 1] |

Evaluating the destination does not read its element. Capture its actual array
reference and index according to Java evaluation order; do not reevaluate them
at the write to obtain metadata. Backward from Step 3 restores [3, 1] and the
arithmetic result/highlight, not a guessed inverse execution.

### C. Aliases share one object

Starting state: a refers to array A = [1, 2].

```java
int[] b = a;
b[0] = 9;
```

Step 1 introduces binding b referring to existing A; no new array is allocated.
Step 2 writes A[0] = 9. Every view of A shows [9, 2]. Backward restores A to
[1, 2] for both aliases. Binding names are not object identities.

### D. Short-circuiting does not invent a read

Starting state: a refers to array A = [4], i = -1.

```java
boolean found = i >= 0 && a[i] == 4;
```

Step 1 compares -1 >= 0 and records false. Step 2 completes `&&` as false with
the right operand marked not evaluated. Step 3 declares found = false. No array
read, right-side comparison, or array-index exception is invented. Highlight
the complete `&&` expression at Step 2 while indicating the skipped operand.

### E. Failure preserves completed earlier operations

Starting state: a refers to array A = [3, 1], x = 0.

```java
x = 7;
a[2] = 9;
```

Step 1 commits x = 7. Step 2 records ArrayIndexOutOfBoundsException at the array
assignment. A remains [3, 1]; no successful array write is recorded. If uncaught,
the run fails. Backward removes the exception display and restores the Step 1
cursor; x remains 7. Catch/unwind semantics need the later support specification.

## 5. Verification obligations before implementation is accepted

- Assert each expected intermediate state, result, source range, and click count.
- Assert backward/restart restore the same state, highlight, and shared identity.
- Compare original and instrumented output, exceptions, final observable values,
  and side-effect counts; include compound expressions and changed identifiers,
  formatting, inputs, and control-flow combinations, not only these snippets.
- Prove skipped expressions produce no execution facts and failed operations
  produce no successful mutation. Preserve effects that occurred before failure.
- Prove bookkeeping adds no unexplained clicks or future-state leakage; test
  scope exit, terminal cleanup, partial capture, and all synchronized views.
- Reject mixing events with edited source. Keep the original source association
  or explicitly invalidate playback; never silently highlight the edited text.

## 6. Remaining specification work

This part does not settle machine-readable event names/schema/versioning,
numeric serialization, precise bookkeeping encoding, supported Java syntax,
array-initializer suboperations, compound assignment/update decomposition,
method entry/return/unwinding, uninitialized declarations, collection operations,
input events, concrete resource limits, or library-internal capture.

Define these before their producers/consumers are implemented. In particular,
method/frame/return semantics must be specified early and implemented before
tree/heap sorting. The examples above do not establish general Java coverage.
