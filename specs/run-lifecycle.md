# Run lifecycle contract — Part 6b

Version 1.0.0 defines a noninteractive request and supervisor snapshot. These
machine-readable contracts do not expose an HTTP route or admit submitted code.
Live input and separate test inputs remain required V1 work; this first slice
accepts only `inputMode: none`. HTTP methods, transport errors, input messages,
console transport, and production admission validation remain later work.

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
