# Call relationships (Part 13)

Status: approved by the user on 2026-10-23 ("ok, go ahead with all of them", after the proposal
of a call tree, waiting lines and kept results). Frontend only: the trace contract (1.11.0) is
unchanged, because CALL, RETURN and FRAME_END events already record every fact used here.
Builds on [methods](methods.md) and [motion](motion.md).

## Rule

Everything comes from recorded events up to the playback cursor: a CALL (the call's code in
the caller, the new frame and its parameters), the RETURN of that frame (its result), or its
FRAME_END while unwinding (it threw). Nothing is inferred about the algorithm, and no later
event is read, so stepping back removes calls that have not happened yet.

## 13a Call tree panel

- A panel "Call tree" next to the call stack, shown once the program has made a call. Each call
  is a node under the frame that made it: `fib(3)`, with its argument values, and `via forEach`
  for a function a library call ran.
- A call that ended keeps its node, greyed, with its result (`fib(3) → 2`), `done` for a void
  method, or `threw`. Frames still running are outlined; the innermost is marked `running`.
- Bound: at most 200 calls are drawn. The running path (and main) always is, then the most
  recent calls; a note gives the number not drawn. A call whose caller is not drawn hangs
  under main.

## 13b What a frame waits for

A frame that is waiting for a call shows the source line of that call, with the call marked in
place and the call it waits for: `waits at return fib(n - 1) + [fib(n - 2) ⏳ fib(2)];`.

## 13c What a frame received

When a call returns, its value fills the mark in the caller's line, `fib(n - 1) = 2`, and stays
until the caller uses it. A value is used by the caller's first later step whose code contains
the call: the `+` of `fib(n - 1) + fib(n - 2)`, the declaration `int r = f(x)`, the `return` of
`return f(n - 1);`, or an enclosing call `g(f(x))`. Steps that only evaluate the next argument
(`n - 2`) do not use it. A call made as a statement (`f(x);`) has no such step; its value is
left behind once the caller runs code past the call's line. The mark is still shown on the
step that uses the value, then it is gone. A call nested inside another marked call, or one
spread over several lines, is listed after the line instead of marked inside it.

## Code

- `frontend/src/trace/calls.ts`: `callsAt(playback, view)` (pure, unit-tested in
  `calls.test.ts`) and `callLine`.
- `frontend/src/views/CallTreePanel.tsx`, `frontend/src/views/CallLink.tsx`.
- Example "Fibonacci (call tree)"; browser test "the call tree keeps every call ...".
