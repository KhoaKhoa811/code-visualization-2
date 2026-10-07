# Trace contract review guide

This contract records Java facts for later playback. It is not a runner or UI.
Read [the specification](../../specs/trace-format.md) and
[Java coverage](../../specs/java-support.md) alongside the schema.

## Review in three parts

| Part | Commit | What to review | Verification/status |
| --- | --- | --- | --- |
| 3a | 9905d0c | Values, identities, cursor boundaries, execution/capture status, ADR 0003 | Complete; documentation/whitespace reviewed |
| 3b | afe8bf6 | Closed structural schema for current operation/frame model | Complete; JSON/local-reference checks and strict Ajv compilation passed |
| 3c | See Git history: `test: validate trace examples and numeric encodings` | Valid/invalid examples and reproducible checks | Complete; 46 checks passed |

Schema path: `v1/trace.schema.json`. Exact schemaVersion: `1.0.0`.
Unknown properties, event kinds, and versions are rejected. Shapes for additional
numeric types describe representation only, not implemented tracing support.

Schema cannot prove event ordering, source hash correctness, identity lifetimes,
numeric range semantics, or agreement between recorded reads and prior state.
Those gates are listed in specification section 12. Passing a sample through a
schema does not prove the producer, reconstruction, or Java execution is correct.

## Run the development checks

From the repository root in PowerShell:

```powershell
npm --prefix contracts ci --ignore-scripts --no-audit --no-fund
npm --prefix contracts test
```

Tested environment: Windows, Node 22.23.2, npm 10.9.8, Ajv 8.17.1. The package
and lock file pin the dependency set. Installation needs registry access or a
populated npm cache. Dependencies stay under ignored contracts/node_modules.
Node's test runner starts a child process; the Codex sandbox blocked this with
`spawn EPERM`, and the approved run outside the sandbox passed.

This is Node development tooling for shared JSON files. The application backend
remains Java 21/Spring Boot/Maven. No Python dependency or application code was
added. Ajv uses its [2020-12-specific validator](https://ajv.js.org/json-schema.html#draft-2020-12) in strict mode without coercion, defaults, or removal of fields.

## What the results establish

- 26 trace fixtures: 11 valid, 10 schema-invalid, and 5 schema-valid but
  semantically invalid counterexamples. See [the fixture guide](v1/examples/README.md).
- 15 typed-value cases, including long bounds, negative zero, infinity, NaN bits,
  UTF-16 code units, and rejected numeric representations.
- Every event variant has a positive fixture. Invalid fixtures assert a targeted
  schema failure, not merely any error.
- Limited fixture checks also verify source hashes/ranges, sequence numbers,
  safe counts, and cursor boundaries. No production semantic validator or
  playback reducer is implemented. Dangling references and Java numeric ranges
  intentionally demonstrate remaining semantic gates.

Original Java execution, instrumentation equivalence, browser playback, and
isolation tests remain future work. These examples were authored, not captured
from an implemented runner. All agreed later V1 coverage remains required.
