# Run contracts (Part 6b)

- [Request v1](v1/request.schema.json): exact source association; version 1.1.0
  (Part 8g) selects `inputMode` `none` or `live`.
- [Result v1](v1/result.schema.json): lifecycle snapshot, termination/cleanup proof
  flags, slot ownership, retained infrastructure failures, optional trace-v1 result.
- [Lifecycle rules](../../specs/run-lifecycle.md): ownership, phases, failure and
  recovery semantics, plus explicit limits of schema validation.

Run `npm test` from `contracts/` using the existing pinned Node/Ajv setup. On
2026-10-17, all 51 tests passed: five lifecycle/request tests and 46 trace checks.
The tests construct normal, stop-failure, cleanup-failure, recovery, and invalid
snapshots and reuse the existing output-only trace fixture. They do not execute
Java or prove runtime transitions. No new dependency was installed.

Review commits: 6b.1 `205f92a` adds the request; 6b.2 `24336c0` adds snapshots,
rules, and tests; 6b.3 records documentation and memory. All three are complete.
No HTTP routes or backend orchestration are implemented by these schemas.

## Snapshot checker (Part 7d)

[`check.cjs`](check.cjs) validates a run-v1 snapshot against the result schema,
applies the shared [trace-v1 checks](../trace/check.cjs) to a nested trace, and
requires the trace's run ID and source hash to match the snapshot. CLI:
`node run/check.cjs <snapshot.json>...`. The backend live API tests run it on every
finished snapshot. On 2026-10-18 `npm test` passed 52 tests (one new checker test).
It does not prove transitions, slot accounting or timing.
