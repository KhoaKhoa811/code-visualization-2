# Trace format: observable Step behavior

Status: Milestone 0, Parts 1–3, updated 2026-10-07. Part 3 defines candidate
wire contract 1.0.0 for review and fixture validation. It is not an implemented
producer, playback engine, or proof of Java tracing correctness.

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
| Uninitialized local declaration | New binding with an explicit unassigned state, never an invented default value |
| Successful local assignment/update | Binding's new value; updates also record old value and expression result when different |
| Successful array creation | New array identity, length, type, and captured initial contents |
| Successful array element read | Array identity, evaluated index, and returned value; contents unchanged |
| Successful array length read | Array identity and recorded length; contents unchanged |
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

The [Part 2 Java support specification](java-support.md) adds the proposed
initial capability matrix, local update/declaration semantics, and early helper
entry/return/unwinding boundaries. Its method rules extend this first-increment
Step baseline; they do not claim implementation. Literal-only array creation is
one creation Step; initializer expressions need further decomposition work.

Part 3 below specifies the initial wire contract and numeric representation.
Remaining work includes broader initializer expressions,
array updates/compound assignment, full exception-handler semantics, collection
operations, input events, concrete limits, and library-internal capture.

Define these before their producers/consumers are implemented. In particular,
method/frame/return semantics must be specified early and implemented before
tree/heap sorting. The examples above do not establish general Java coverage.

## 7. Part 3a: wire contract overview

The runner records facts; the backend validates them; playback reconstructs an
accepted prefix. The same document carries all facts required by this initial
contract. It contains no layout, colors, timing, suggested views, or React names.

The machine-readable file is [trace.schema.json](../contracts/trace/v1/trace.schema.json).
It uses JSON Schema draft 2020-12. Schema validation checks shape and allowed
values; section 12 defines additional semantic checks. See the official
[core](https://json-schema.org/draft/2020-12/json-schema-core) and
[validation](https://json-schema.org/draft/2020-12/json-schema-validation) specifications.

| Top-level field | Meaning |
| --- | --- |
| schemaVersion | Exactly `1.0.0`; reject unrecognized versions rather than silently skipping facts |
| runId | Nonempty identity for this run; identities below are local to it |
| source | Exact source text, file `Main.java`, and lowercase SHA-256 of its UTF-8 bytes |
| execution | Terminal status and human-readable reason; describes execution, not capture completeness |
| capture | complete / partial / unavailable, reason, and safeEventCount |
| diagnostics | Code, message, phase and optional original source range |
| initialState | Captured objects, frames, scopes and bindings at cursor zero, or null when unavailable |
| events | Ordered accepted records, excluding any untrusted or unsafe suffix |
| stepEnds | Event sequence number at each observable cursor's accepted boundary |

`source.text` must be well-formed Unicode, without line-ending or whitespace
normalization. Hash the submitted text, not transformed Java. Source ranges use
zero-based UTF-16 code-unit offsets, start inclusive/end exclusive, into that text.
Bounds and hash correctness require semantic validation. Implicit method exit
may use a zero-width body-end range; explicit operations must be nonempty.

Use JSON integer numbers only for nonnegative counters/indices/offsets within
0..9007199254740991. IDs are opaque strings, not counter values or display names.
Duplicate object/frame/scope/binding IDs within a run are invalid even after an
entity's lifetime ends. A declarationId may repeat across distinct invocations
or loop iterations, but bindingId identifies each separate lifetime.

## 8. Values and recorded state

| Value kind | Representation |
| --- | --- |
| byte, short, int, long | `value` is a canonical signed decimal string: no plus, leading zeros, or negative zero |
| char | `value` is one UTF-16 code unit, integer 0..65535 |
| boolean | `value` is a JSON boolean |
| float, double | `bits` is 8 or 16 lowercase hexadecimal digits representing the raw IEEE-754 bit pattern, most significant digit first |
| ref | `objectId` identifies a captured object in this run |
| null | Only `kind: null`; distinct from missing capture and unassigned bindings |
| unassigned | Only valid as a local binding value before initialization, never an operand, parameter, return result or array element |

Examples: `{"kind":"long","value":"9223372036854775807"}` and
`{"kind":"double","bits":"8000000000000000"}`. The latter preserves negative
zero. Bit strings also preserve infinities and NaN payloads without JSON numeric
conversion. Decimal strings require signed 8/16/32/64-bit range checks beyond
their schema pattern. A consumer must not convert long to JavaScript Number.
Representation for a type does not establish tracing coverage for its operations.

Objects are either arrays (`objectId`, `kind: array`, `elementType`, `elements`)
or strings (`objectId`, `kind: string`, `utf16` code-unit array). Array length is
elements.length; elements are complete within this contract. The element types
are Java primitive names and `java.lang.String`. This permits representing the
standard main argument array; it does not add broader array tracing coverage.
String references preserve identity independently of identical text. Strings
are immutable. Other objects or truncated arrays require a future contract;
do not fill missing elements with null or silently drop references.

A frame has frameId, methodId, and nullable callerFrameId. A scope has scopeId,
frameId, and nullable parentScopeId. A binding has bindingId, declarationId,
frameId, scopeId, name, javaType, and value. Initial frames are ordered from main
to the active frame. Initial scopes are ordered outer-to-inner per frame.
Method IDs identify resolved declarations, not names alone. Explicit lifetimes
and object identity allow recursion and aliases without sharing local bindings.

## 9. Observable event records

Each observable record has seq (1-based), step (1-based), source range, frameId,
kind, and data. seq counts all events; step counts only observable operations.
The active frame supplies frameId, including the caller for CALL and departing
callee for RETURN. CALL source is the caller's call expression. Other source
ranges follow sections 2–3 and the Java-support specification.

| Kind | data fields and state effect |
| --- | --- |
| DECLARE | binding; add its captured binding, including explicit unassigned where appropriate |
| ASSIGN | bindingId, previous, value; replace the binding value; previous may be unassigned |
| UPDATE | bindingId, previous, value, result, operator (++/--), position (prefix/postfix); one committed update, including the expression result |
| ALLOCATE | object; add a fresh captured array identity |
| ARRAY_READ | objectId, index, value; no mutation |
| ARRAY_LENGTH | objectId, length; no mutation |
| ARRAY_WRITE | objectId, index, previous, value; commit exactly one element change |
| EXPRESSION | operator, operands, result; no persistent mutation |
| CALL | frame, scope, parameters; push new frame and its root scope and parameter bindings |
| RETURN | result, implicit; pop this frame and remove its scopes/bindings; result is JSON null for void, otherwise a typed value |
| EXCEPTION | exceptionType, message; no successful mutation or result for the failing operation |

EXPRESSION operators are `+`, `-`, `*`, `/`, `%`, `!`, comparisons, `&&`, `||`,
and `condition`. Each operand is either `{value: <typed value>}` or
`{skipped: true}`. Position determines operand role; unary has one operand,
binary two. Only the second operand of &&/|| may be skipped, and only when its
first result warrants skipping. Earlier nested operations have their own events.
No generic expression event substitutes for an observable mutation.

DECLARE/ASSIGN identify a binding rather than copying its referred object.
Read/expression/exception events retain observations without modifying state.
RETURN retains a captured result after removing the frame; the caller receiving
it is a later declaration/assignment. Void JSON null differs from a returned
Java null represented as `{"kind":"null"}`. Exception propagation is not RETURN.

## 10. Bookkeeping and cursor boundaries

Bookkeeping has seq, kind, data, but no step/source/frameId envelope fields:

| Kind | data and effect |
| --- | --- |
| SCOPE_ENTER | scope; add scope under its recorded parent in the active frame |
| SCOPE_EXIT | scopeId; remove that innermost scope and its bindings |
| FRAME_END | frameId, reason (main_return / unwind); pop the active frame and all remaining scopes/bindings |
| OBJECT_CAPTURE | object; register a newly observed string identity and contents without claiming a Java allocation at that expression |

Helper normal return uses RETURN, never FRAME_END. Main normal termination uses
FRAME_END; unwinding uses FRAME_END for each frame actually departed. Binding
removal is not object destruction; keep referenced and historic objects in replay
storage. Objects are never reused as later identities.

OBJECT_CAPTURE is for an actually observed String reference, such as a literal
first used by a declaration. It precedes that operation without a separate click.
It must not register strings by scanning unexecuted code or duplicate an already
known identity. Array allocation remains an observable ALLOCATE operation.

`seq` must equal the record's array position + 1. Observable step numbers are
contiguous. For every non-final cursor k, stepEnds[k-1] equals the seq of its
observable event: bookkeeping between operations is consumed with the next Step.
At the final cursor, complete capture includes terminal bookkeeping through
events.length. Partial capture ends exactly at its last safe observable record;
unsafe or trailing cleanup is excluded. safeEventCount always equals events.length.

Reconstruct cursor k from initialState and events up to stepEnds[k-1], then show
the kth operation's recorded result and highlight. Backward selects an earlier
prefix; it never reverses Java or reads future records for interpretation.
Cursor zero uses only initialState. A complete zero-operation run is a special
case: only terminal bookkeeping is allowed, applied at its sole terminal cursor
zero; no blank completion click. Partial zero-operation capture has no events.

All panels share this boundary, including frame state and future ranking rules.
Checkpoints may be added later only if equivalent to this reconstruction; v1 has
no optional snapshot cache or layout state in its envelope.

## 11. Termination and safe capture

execution.status is completed, failed, cancelled, limited, compile_error, or
rejected. execution.reason is always present, including completion. Capture
status is independent: a failed program can have a complete trace of its failure.

- complete: execution is completed or failed, initialState is present, all
  relevant facts through termination are recorded, and terminal cleanup is known.
- partial: initialState is present and events contain only a safe prefix;
  execution may be completed, failed, cancelled, or limited. The capture reason
  explains the boundary without implying later execution state is visualizable.
- unavailable: initialState is null, events and stepEnds are empty, and
  safeEventCount is zero. Use for pre-execution tracing limitations or when no
  initial state is trustworthy. compile_error/rejected always use unavailable.

A terminal envelope is produced by orchestration after the process ends. A killed
worker cannot be trusted to write its own terminal record. No fake exception,
return, cleanup, or mutation is appended to repair incomplete recording. Partial
capture may include a successfully recorded failure operation only if the whole
operation record is accepted. Transport framing and atomic acceptance remain
runner work; this contract consumes already validated records.

Console bytes and interactive input are separate run-associated transport, not
events in this schema. Replaying a trace never resubmits input. Diagnostics have
phase (compile / admission / capture / execution), code, message, and optional
source; missing source information is omitted rather than fabricated.

## 12. Versioning and validation obligations

Use exact-version dispatch. Changing accepted event kinds, fields, typed-value
rules, or replay semantics requires a new schemaVersion and explicit consumer
support. Reject unknown fields/kinds/versions. Runtime facts and presentation
remain separate. Rationale is recorded in
[ADR 0003](decisions/0003-versioned-trace-contract.md).

Schema checks are necessary, but these cross-record checks are additionally
required before playback (not implemented by JSON Schema alone):

1. Match source hash/ranges and run identity; all offsets and counters are safe.
2. Check sequence, step numbering, stepEnds, safe count, and terminal consistency.
3. Resolve every ID in order; enforce fresh identities and stack/scope lifetimes.
4. Check binding/array element types, integer ranges, reference target types,
   operand counts, operator/result types, and skipped-operand legality.
5. Match recorded previous/read values to reconstructed state, indices/lengths to
   actual arrays, and CALL/RETURN transitions to the recorded frame stack.
6. Require completed/complete traces to end with no active frames; failed/complete
   traces must account for uncaught failure and unwind. Never synthesize facts.

Fixtures will distinguish schema-invalid data from structurally valid but
semantically invalid data. A fixture checker is not a Java interpreter or a
production replay engine. Original-versus-instrumented and browser replay tests
remain future milestones.
