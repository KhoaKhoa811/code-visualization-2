# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-19 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: Part 8 (coverage); Milestone 0, full isolation and V1 incomplete

## Last approved task: Part 8f strings and library collections (complete)

User (2026-10-19): "ok" to the 8f proposal. Spec: [specs/library.md](../specs/library.md).

- [x] 1. Spec, contract 1.5.0, tracer (concatenation, String/StringBuilder, collections, statics, boxing) — 69b14bc
- [x] 2. Frontend Collections panel and library steps, example — bccb59c
- [x] 3. Browser check in Docker — 40888f0
- [x] 4. Guides and memory (this commit)

## Git

- Branch `feat/part-8f-library`, stacked on unmerged 8e (`feat/part-8e-objects`, 4332e6d), 8d
  (f6ced75) and 8c (d22f1f9), on main 234a586.
- PRs (manual, `gh` unavailable), merge in order 8c, 8d, 8e, 8f:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-8c-statements?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-8c-statements...feat/part-8d-methods?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-8d-methods...feat/part-8e-objects?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-8e-objects...feat/part-8f-library?expand=1

## Verification (2026-10-19, Claude)

- Root reactor `clean verify` with `-Dcodeviz.docker.live=true` and
  `-Dcodeviz.docker.executable=<docker path>`: **152 tests, 0 failures/skips**
  (tracer 67, backend 86). Log `.tools/part-8f-verify.log`. No owned container left.
- Contracts 60; frontend 19 reducer tests, build, 8 Playwright tests (real backend + Docker).

## Decisions in this task

- Contract 1.5.0 (1.4.0 retired): collection/map/builder objects, LIBRARY step (receiver,
  method, arguments, result, after), n-ary `+` for flattened concatenation, generic javaTypes.
- Library internals are never read: each covered call is recorded around the original
  invocation and replayed by the builder on mirrors made of the same JDK classes, which
  verifies results and derives contents. Library objects from uncaptured code are captured
  by iteration (no user code runs for the covered classes and element types).
- Programs compile with `-XDstringConcat=inline` (StringBuilder chains instead of indy).
- Keys of hashed/sorted collections and PriorityQueue elements: boxed primitives or String.
  HashSet/HashMap shown ascending; PriorityQueue in poll order, never its internal layout.
- Boxed values are values (identity not modeled); `==` between two boxed values stays uncaptured.
- Not covered: iterators/for-each over collections, map views, streams/lambdas, comparators,
  Collections/Arrays utilities, split/format, Math.pow.

## Resources

- No commands running; no containers.

## Next action

Wait for review/merge of 8c-8f. Propose 8g (live Scanner console over WebSocket, ADR 0001)
for approval.
