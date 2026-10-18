# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-18 (Asia/Saigon)
- Active owner: Claude Code
- Status: active, Part 7b
- Milestone: trace engine (Part 7); Milestone 0, full isolation and V1 incomplete

## Approved task

**Part 7b first trace-v1 producer (host side).** User merged 6e and 7a into main
(528d45d) and said: "please continue implement part 7b". Claude treats this as
accepting ADR 0004 (7b is built on it) and uses the split proposed with 7a:
7b host producer, 7c container execution, 7d run API, 7e frontend. Tell the user.

Scope: java-support first increment inside `Main.main` only: int/boolean/String
literal locals with initializers, assignment, int[] literal and `new int[n]`,
reads/writes/length, + - * / % unary -, comparisons, ! && ||, ++/-- on int
locals, if/else, while, basic for, break/continue, return, System.out/err
print/println of covered values. Anything else: capture unavailable, output-only.
Excludes: helper methods/recursion, uninitialized declarations, compound
assignment, string concatenation, container execution (7c).

Checklist:
- [ ] 1. ADR 0004 Accepted; `specs/bytecode-tracing.md` (site plan, matching,
      scope derivation, raw format, limits)
- [ ] 2. Analyzer: javac tree plan in javac code-layout order + support check
- [ ] 3. Instrumenter + recorder: site IDs, raw records, recorder-package rejection, raw cap
- [ ] 4. Trace builder: raw records + site table to trace-v1 (scopes, bindings,
      conditions with skipped operands, exceptions, termination, stepEnds, 8 MiB limit)
- [ ] 5. Tests: differential, Ajv schema validation via contracts/, expected steps
      (AC-01..05, AC-10, AC-17, J-01, J-02, J-04..J-08), unsupported, limit
- [ ] 6. Docs, memory, commit, push

Design: user JVM records only site ID + values; trusted builder supplies source
ranges, operators, declaration identity and previous values from its own state.

## Git

- Branch `feat/part-7b-trace-producer` from main 528d45d. Not pushed yet.
- Module: `runner/tracer/` (new). `runner/tracer-spike/` stays as 7a evidence.

## Verification

- None yet for 7b. 7a spike: 20 tests passed. Backend 6e: 49 tests passed.

## Resources

- No commands running; no containers.

## Next action

Write ADR acceptance and spec, then analyzer.
