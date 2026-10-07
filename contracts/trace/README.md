# Trace contract review guide

This contract records Java facts for later playback. It is not a runner or UI.
Read [the specification](../../specs/trace-format.md) and
[Java coverage](../../specs/java-support.md) alongside the schema.

## Review in three parts

| Part | Commit | What to review | Verification/status |
| --- | --- | --- | --- |
| 3a | 9905d0c | Values, identities, cursor boundaries, execution/capture status, ADR 0003 | Complete; documentation/whitespace reviewed |
| 3b | See Git history: `feat: add versioned trace JSON schema` | Closed structural schema for current operation/frame model | JSON/local-reference checks; schema-validator gate follows in 3c |
| 3c | Pending | Valid/invalid examples and reproducible checks | Pending |

Schema path: `v1/trace.schema.json`. Exact schemaVersion: `1.0.0`.
Unknown properties, event kinds, and versions are rejected. Shapes for additional
numeric types describe representation only, not implemented tracing support.

Schema cannot prove event ordering, source hash correctness, identity lifetimes,
numeric range semantics, or agreement between recorded reads and prior state.
Those gates are listed in specification section 12. Passing a sample through a
schema does not prove the producer, reconstruction, or Java execution is correct.
