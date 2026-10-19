# Static methods and recursion (Part 8d)

Status: approved by the user on 2026-10-19 ("ok", after the 8d proposal), step 8d
of the [coverage roadmap](coverage-roadmap.md). Implements the method rules of
[java-support.md section 5](java-support.md) and the call-depth budget of
[execution-isolation.md Part 4k](execution-isolation.md). Trace contract stays
**1.3.0**: CALL, RETURN and FRAME_END `unwind` already exist.

## Traced methods

A *traced helper* is a `static` method declared in class `Main`, other than
`main`, that is not generic, varargs, native or abstract, and whose parameter
and result types are traced types (primitives, `String`, arrays of those) or
`void`. Its body is planned with the same statement rules as `main`, including
uncaptured statements (Part 8a). Instance methods, constructors, methods of other
classes and library methods stay uncaptured.

A call to a traced helper from traced code is traced: its arguments' steps in
Java order, then one CALL step, then the callee's steps, then one RETURN step,
then the caller continues (a receiving declaration or assignment is a later step).
Calls may appear anywhere a value is allowed, including conditions and as
expression statements (an ignored result creates no binding).

## Steps

| Event | Source | Data |
| --- | --- | --- |
| CALL (caller's frame) | the call expression | new frame (`Main.name(types):result`, caller frame), its root scope, parameter bindings with the passed values |
| RETURN (callee's frame) | the `return` statement; an uncaptured statement that returned; or the method's closing brace for an implicit void return (`implicit: true`) | result (typed, or JSON null for void) |
| FRAME_END `unwind` (bookkeeping) | — | after an uncaught exception, one per active frame, innermost first |

Each invocation has its own frame, scopes and bindings, so recursive calls with
identical names never share locals. Arrays passed as arguments keep their
identity (aliases across frames).

## Calls from uncaptured code

A traced helper called from an uncaptured statement, a static initializer or
library code runs silently: the recorder records nothing until that invocation
returns or unwinds, so the effects belong to the uncaptured step that caused them
(locals it assigns become unknown, arrays are re-read). Silence is decided at
entry: a traced call site announces itself just before `invokestatic`; an entry
without that announcement is silent, and so is every call made while silent.

## Returns

Every return instruction of a traced helper records its value, including one
inside an uncaptured statement (located at that statement) and javac's implicit
`return` at the end of a void method. A catch-all handler added by the
instrumenter ends silent invocations that unwind and rethrows the same exception.

## Depth limit

At most **64 active traced frames including main**. Entering one more writes a
limit record and halts the JVM (exit 86), like the raw-record limit: execution
`limited`, capture `partial`, diagnostic `CALL_DEPTH_LIMIT` (phase capture). The
accepted prefix ends at the last safe step; no CALL is invented for the
overflowing invocation. Silent invocations are not counted. A real
`StackOverflowError` is not this limit.

## Raw records

`c<method> <callSite>` a traced entry, followed by one record per parameter
(`<paramSite> <value>`); `<returnSite> [<value>]` a return; `D` the depth limit.

## Results

Pending.
