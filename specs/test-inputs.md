# Separate test inputs (Part 10a)

Status: approved by the user on 2026-10-21 ("continue implement next part", step 10a of
the roadmap in [V1 scope](v1-scope.md)). Requirements: section 15 (input workflows),
AC-35. Builds on the [live console](live-console.md) (Part 8g).

## Request and association

Run request **1.2.0** (1.1.0 retired) adds `inputMode: "provided"` with
`input: { "text", "sha256" }`. The other modes must not carry `input`. Admission:
- the text is well-formed Unicode (else `INPUT_INVALID`);
- it is at most 65,536 bytes of UTF-8, the per-run input bound of execution-isolation
  Part 4r (else `INPUT_LIMIT`, HTTP 413);
- its SHA-256 matches (else `INPUT_HASH_MISMATCH`).

A rejected request never queues. The text is never echoed.

Run snapshot **1.1.0** adds `input: { "mode", "sha256" }`, which ties the input to the
run, its source and its result:

| Mode | sha256 |
| --- | --- |
| `provided` | hash of the given input, from admission on |
| `live` | hash of the input actually delivered, once the run finished (null before) |
| `none` | null |

## Running

If the source refers to `System.in`, the program's standard input is the given bytes,
exactly as given (CR, missing final LF and long lines included), and then end of input.
Nobody waits for this input, so the normal runtime deadline (5,000 ms) applies, not the
interactive one. A program that does not refer to `System.in` gets a closed standard
input, as before. The console accepts no live input in this mode
(`INPUT_NOT_INTERACTIVE`).

The trace builder replays `Scanner` and `BufferedReader` reads over the bytes actually
delivered, as for live input. Replay never sends input again (AC-21). Too little input
fails the way Java does, for example with `NoSuchElementException`.

## Frontend

"Use test input" in the console shows a text box. Run then sends that text as provided
input instead of opening a live console. The run summary shows "Input: test input, N bytes
(SHA-256 …)". If the text is edited after the run, the summary says the playback used the
input it was given. Playback stays valid, because a trace belongs to its own run's input.

## Not covered here

Several test cases per run, expected-output checks, input files uploaded from disk, and
keeping test inputs after the page closes.
