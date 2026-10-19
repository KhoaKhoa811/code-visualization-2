# Live console and Scanner input (Part 8g)

Status: approved by the user on 2026-10-19 ("ok" after the 8g proposal, and "Add
WebSocket" for the dependency), step 8g of the [coverage roadmap](coverage-roadmap.md).
Transport: [ADR 0001](decisions/0001-live-console-websocket.md). Bounds:
[execution-isolation.md Part 4r](execution-isolation.md). Trace contract **1.6.0**.

## Interactive runs

A program is *interactive* when its source refers to `System.in`. The analyzer
decides this before the runtime phase (plan field `interactive`); nothing is
inferred from prompts. An interactive run:

- starts its JVM with standard input attached (`docker exec -i`);
- uses the interactive deadline of **120,000 ms** for the whole runtime phase
  instead of 5,000 ms (startup, computation and waiting all count; input never
  resets it);
- accepts input lines only while it executes and before EOF.

A non-interactive run keeps the 5,000 ms deadline and an immediately closed
standard input (a read sees end of input at once).

The 30,000 ms input-wait deadline of Part 4r needs verified evidence that a read
is blocked. This part has no such evidence, so the waiting state is reported as
unknown and only the overall deadline applies. The console says the program
*reads input*, not that it is waiting.

## Input bounds and semantics

| Bound | Value |
| --- | --- |
| One line, including the appended LF | 4,096 UTF-8 bytes |
| Accepted input per run, including LFs | 65,536 bytes |
| Accepted but not yet written | 16,384 bytes |
| One WebSocket message | 32,768 bytes |

A line is sent exactly as typed plus one LF; it must not contain CR or LF itself
and must be well-formed Unicode. Lines are written in order. Each input message
carries a sequence number starting at 1: the next number is accepted, an earlier
number with the same text is acknowledged again without being written twice, any
other number is rejected. Rejected input is not written and does not stop the run.
EOF stops further input and closes standard input once every accepted line has
been written; repeated EOF is harmless. When the run stops, unwritten input is
discarded. Java's own `Scanner` behavior decides what the program sees, including
invalid tokens and end of input.

## WebSocket messages (`/api/runs/{id}/console`)

Client to server:

- `{"type":"input","seq":1,"text":"42"}`
- `{"type":"eof"}`

Server to client:

- `{"type":"state","phase":"executing","interactive":true,"controller":true,"inputOpen":true}`
  whenever any of these change (`phase` uses the run-v1 phase words).
- `{"type":"output","stream":"stdout","text":"Enter n: "}` for new console text,
  in order, decoded as UTF-8 across chunk boundaries.
- `{"type":"ack","seq":1}` and `{"type":"reject","seq":1,"code":"INPUT_LINE_LIMIT"}`
  (codes `INPUT_NOT_INTERACTIVE`, `INPUT_CLOSED`, `INPUT_LINE_LIMIT`,
  `INPUT_TOTAL_LIMIT`, `INPUT_PENDING_LIMIT`, `INPUT_SEQUENCE`, `INPUT_INVALID`,
  `NOT_CONTROLLER`).
- `{"type":"end"}` once termination is confirmed, then the server closes.

Unknown or malformed messages are rejected with `INPUT_INVALID` and are never
echoed. The first connection is the controller. If the controller of an
interactive run disconnects while the run executes, the run is cancelled with the
execution diagnostic `INPUT_DISCONNECTED` ("Input controller disconnected").

## Scanner subset

`new Scanner(System.in)` (one per run) and the calls `nextInt`, `nextLong`,
`nextDouble`, `nextBoolean`, `next`, `nextLine`, `hasNext`, `hasNextInt`,
`hasNextLine` and `close` are LIBRARY steps showing the value read. The trusted
builder replays them on a `Scanner` over the exact bytes delivered to the program,
so each recorded result is verified. A failing read (`InputMismatchException`,
`NoSuchElementException`) is an EXCEPTION step with Java's own message. Other ways
of reading `System.in` run without capture; a second `Scanner` stops capture at
that point.

Contract 1.6.0 adds the object kind `input` (`objectId`, `className`) for a
`Scanner`; replay never sends input again (AC-21).

## Results

Pending.
