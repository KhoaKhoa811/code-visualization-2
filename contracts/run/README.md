# Run contracts (Part 6b)

- [Request v1](v1/request.schema.json): exact source association, noninteractive input.
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
