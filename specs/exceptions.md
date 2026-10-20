# Exceptions: throw, try/catch/finally (Part 8h)

Status: approved by the user on 2026-10-20 ("ok, implement part 8h"), step 8h of the
[coverage roadmap](coverage-roadmap.md). Builds on [methods](methods.md) (8d) and
[library](library.md) (8f). Trace contract **1.7.0**.

## What is traced

In traced methods (main, static methods of Main, methods and constructors of traced
classes):

| Construct | Steps |
| --- | --- |
| A covered operation that fails (array bounds, division by zero, null field access, a failing library call) | EXCEPTION at the operation, as before; the program may now continue |
| `throw new E()` / `throw new E(message)` | the message's own steps, then EXCEPTION at the throw statement |
| `throw e` of a caught exception | EXCEPTION at the throw statement, with the same exception object |
| `catch (E e)`, `catch (A \| B e)` | CATCH at `catch (E e)`: declares `e`, referring to the exception object |
| `finally` | its operations run on every path: after the try block, after each catch block, before a `return`, `break` or `continue` that leaves the statement, and before an exception passes through it |
| `e.getMessage()` | LIBRARY step whose result is checked against the recorded message |

`E` is a `Throwable` class of the `java.lang` or `java.util` package, or a class of
the source (top level or a static member of Main). A source class can be created by
traced code only when it is *simple*: it declares only constructors that pass their
parameters to `super(...)`, plus constants, and its superclass is a JDK class above
or another simple class. Any source `Throwable` class can be caught.

## The exception object

Contract 1.7.0 adds the object kind `exception` (`objectId`, `className`, `message`).
`className` is the binary name Java reports (`Main$InsufficientFunds`); `message` is
`getMessage()` when the exception was first seen, or null. The recorder calls
`getMessage()` only when the JDK implements it, so no user code runs inside the
recorder. An exception is captured (OBJECT_CAPTURE) when it is first thrown or
caught. EXCEPTION events now carry its `objectId`.

## Propagation and unwinding

After an EXCEPTION the exception is *in flight*. Until a catch receives it:

- a finally block it passes through runs. The builder learns this from a record
  written at the start of javac's finally handler. Its operations are steps.
- when it leaves a traced method, that frame ends with FRAME_END `unwind` at once
  (bookkeeping), and the caller's frame becomes active;
- no other operation may run.

A CATCH ends the flight. The trusted builder checks that the caught object is the one
in flight and that the clause catches its class. It uses the source's class
hierarchy, then the JDK's. An exception thrown by a statement that runs uncaptured
may be caught without an earlier EXCEPTION; it is captured when the catch receives
it. If no catch receives it, main fails as before ("Uncaught E."), and the
remaining frames unwind at the end.

## Not traced (the statement runs uncaptured)

- try-with-resources. The uncaptured region now starts at the first resource,
  which javac gives its own range. Before this part, such a program lost its
  visualization entirely.
- Finally blocks that contain `return`, `break`, `continue`, `yield`, `throw` or
  `try`, or a statement that would run uncaptured. The whole try statement then
  runs uncaptured.
- In a try statement with finally: an uncaptured statement that contains a jump.
  javac copies the finally block into that jump's code, where it would run
  unseen.
- An empty try block, and `yield` out of a try statement with finally.
- Creating exceptions with other constructors, creating non-simple source
  classes, throwing anything other than a new exception or a caught one,
  `throw null`, and calls such as `printStackTrace()` or `toString()`.

A catch that receives an exception the recording never saw thrown (outside the cases
above) makes capture partial instead of inventing the failure.

## Implementation notes

- The analyzer plans javac's layout: the try block; its finally copy when the block
  can complete normally (JLS 14.22, with javac's handling of constant `if`); per
  catch the parameter store, the block and its copy; then the finally handler.
  The handler is the store of the exception, the block, and the rethrow. Jumps
  plan the copies of every finally block they leave. A value `return` stores its
  value in javac's hidden local first.
- Raw records (plan format 8):
  - `x<site> <class> <message> <ref>` for a failure or throw;
  - `E<id> <class> <message>` for an exception's first sight;
  - `w` when an exception leaves a traced helper;
  - the catch parameter's store, and the finally handler's store of the
    exception in flight.
- A library call's failure handler now sits right after the call and comes first
  in the exception table, so the failure is recorded before a user handler runs.

## Results

Verified on 2026-10-20 with Docker Desktop 29.8.0:

- Root reactor `clean verify` with live Docker: **170 Java tests, 0 failures or
  skips** (tracer 77, backend 93). The new tracer tests cover:
  - catch then finally in main;
  - a source exception thrown by a helper, a `return` inside try/finally, and
    `getMessage()`;
  - an uncaught throw through a finally block;
  - `break`/`continue` out of try/finally, multi-catch, a caught library failure
    and a `return` from main;
  - a rethrow keeping its identity;
  - an exception thrown by an uncaptured statement, then caught;
  - uncaptured constructs, including try-with-resources;
  - forged catch records.
  A container test runs the exceptions example.
- `contracts/` `npm test`: 62 passed.
- Frontend: 21 reducer tests, production build, **10 Playwright tests** with the
  real backend and Docker. The new one steps to "Throw InsufficientFunds: short
  by 430" with two frames, then to "Catch e = …" with one frame. Stepping back
  restores the unwound frame.
