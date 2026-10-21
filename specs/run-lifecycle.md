# Run lifecycle contract — Part 6b

Version 1.0.0 defines a noninteractive request and supervisor snapshot. These
machine-readable contracts do not expose an HTTP route or admit submitted code.
Live input and separate test inputs remain required V1 work; this first slice
accepts only `inputMode: none`. HTTP methods, transport errors, input messages,
console transport, and production admission validation remain later work.

Part 8g: request version 1.1.0 accepts `inputMode` `none` or `live`; 1.0.0 is
retired. A live request whose source refers to `System.in` runs interactively with a
120 s execution deadline, and its input arrives over the
[live console](live-console.md). Any other request closes stdin at once.

Part 10a: request 1.2.0 (1.1.0 retired) adds `inputMode: provided` with test input text and
its hash. The input is written as standard input, followed by end of input, with the normal
5 s deadline. Snapshot 1.1.0 records the input association ([test inputs](test-inputs.md)).

## Request and ownership

[Request schema](../contracts/run/v1/request.schema.json) reuses trace-v1 source:
`Main.java`, exact text, and UTF-8 SHA-256. The supervisor verifies the hash,
well-formed Unicode, and existing source/transport limits before admission.
JSON Schema alone cannot establish byte size, hash correctness, or Java validity.
No normalization, truncated source, caller-selected run ID, host path, image,
command, resource budget, or container handle is accepted.

The supervisor assigns a new opaque run ID to each accepted request and owns its
immutable source association, resource inventory, stop cause, output, and slot.
Worker output cannot assert control facts. A future fixed-fixture API selects
server-owned source; accepting this shape does not authorize arbitrary execution.

## Snapshot and phase boundaries

[Result schema](../contracts/run/v1/result.schema.json) describes a polling-style
snapshot, not a streaming protocol. `revision` increases for each published change
within one run. Consumers ignore older revisions. Source SHA-256 and run ID must
match the request association and any nested terminal trace.

| Phase | Meaning |
| --- | --- |
| queued | Accepted, no occupied slot or resources; no workers launch. |
| preparing | Slot reserved; create/check/stage/analyze before compiler launch. |
| compiling | All isolated compiler work shares one compilation deadline. |
| executing | Compilation succeeded; separate runtime deadline applies. |
| stopping | Launch/input/record acceptance fenced; whole-run proof pending. |
| cleaning | Whole-run termination confirmed; remove owned temporary resources. |
| finished | No pending launches; termination and cleanup verified; no slot held. |

Normal path: queued, preparing, compiling, executing, stopping, cleaning,
finished. Queue promotion can be immediate. Cancellation/failure may skip forward
to stopping; never return to compilation/execution after a stop cause is latched.
Queued cancellation can finish without reserving a slot once no-launch and empty
resource-inventory proofs exist. Busy, malformed, and queue-expired requests are
scheduling/admission errors outside this snapshot and outside trace-v1.

`slotHeld` is an ownership fact, not inferred from an execution status. Preparing,
compiling, executing, and cleaning hold it. Stopping can represent either a queued
or occupied run; it must preserve prior ownership. Release once, only after both
proofs. No state-machine implementation or race enforcement is included in 6b.

## Terminal facts and infrastructure failures

`trace` is null until whole-environment termination is confirmed and a valid
trace-v1 envelope exists. A null trace is not a successful empty trace. Do not
manufacture an execution status for setup, worker, or transport failure. For
output-only results, use a real trace-v1 envelope with unavailable capture.

`infrastructureFailures` retains bounded, fixed supervisor codes separately from
Java execution/capture statuses. SETUP_FAILED, WORKER_FAILED, TRANSPORT_FAILED,
TERMINATION_UNCONFIRMED, and CLEANUP_UNCONFIRMED record observed failures. They
are append-only per run (deduplicated); late recovery never erases them.

Unconfirmed termination stays stopping, with null trace and retained ownership.
Cleanup failure stays cleaning with slot held; a valid terminal trace can remain
available. Explicit recovery may finish once the same run's proofs verify. The
original stop cause remains latched; successful recovery cannot change cancelled
or limited into completed. No automatic retry after confirmation expiry.

The schema enforces snapshot combinations, not trusted evidence, transition
history, source/trace identity equality, exactly-once release, or monotonic clocks.
Those must be enforced and race-tested by later orchestration. Existing limits
and ordering in [execution isolation](execution-isolation.md) remain authoritative;
no new budgets or trace-v1 statuses are introduced.

## HTTP API (Part 7d)

The backend exposes this contract over loopback HTTP; usage is in
[backend/RUN_API.md](../backend/RUN_API.md). One `RunSupervisor` is the only
admission path: it opens after the owner-label inventory, assigns `run-<uuid>`
IDs, and runs each request through the traced container lifecycle.

- `POST /api/runs` accepts request v1 and returns `202` with the first snapshot.
  Malformed, oversized, wrong-hash, busy and unavailable outcomes are fixed error
  codes outside the snapshot, as above. Nothing rejected is queued or launched.
- `GET /api/runs/{id}` returns the current snapshot. The revision increases
  whenever phase, proof flags, slot ownership, trace presence or failures change.
  A queue-expired run returns `410 QUEUE_EXPIRED` and has no snapshot.
- `POST /api/runs/{id}/cancel`: a queued run finishes without a slot or
  container, with a trace-v1 `cancelled` envelope and unavailable capture (no Java
  ran). An active run latches cancellation under Part 6e precedence.
- `POST /api/runs/{id}/recover` is explicit recovery of a held run; it never
  changes the original stop cause and keeps earlier failures.
- `GET /api/runs/{id}/output` returns stdout and stderr only after termination is
  confirmed. Console data stays outside trace-v1.

Infrastructure codes map from the lifecycle: preparation failures are
`SETUP_FAILED`; a failing trusted `Prepare` tool or an invalid plan is
`WORKER_FAILED`; phase transport failures and unreadable plan or record files
are `TRANSPORT_FAILED`. Finished and expired runs are retained in memory, at most
four, each for at most 10 minutes.
