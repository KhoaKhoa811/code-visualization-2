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

## Last approved task: Part 8e objects and fields (complete)

User (2026-10-19): "ok" to the 8e proposal. Spec: [specs/objects.md](../specs/objects.md).

- [x] 1. Spec, contract 1.4.0, tracer (classes, fields, constructors, methods, cycles, limit) — 55f713d
- [x] 2. Frontend object graph, field steps and paths, example — 1ef0197
- [x] 3. Browser check in Docker — aaca97d
- [x] 4. Guides and memory (this commit)

## Git

- Branch `feat/part-8e-objects`, stacked on unmerged 8d (`feat/part-8d-methods`, f6ced75)
  and 8c (`feat/part-8c-statements`, d22f1f9), on main 234a586.
- PRs (manual, `gh` unavailable), merge in order 8c, 8d, 8e:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-8c-statements?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-8c-statements...feat/part-8d-methods?expand=1
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-8d-methods...feat/part-8e-objects?expand=1

## Verification (2026-10-19, Claude)

- Root reactor `clean verify` with `-Dcodeviz.docker.live=true` and
  `-Dcodeviz.docker.executable=<docker path>`: **149 tests, 0 failures/skips**
  (tracer 63, backend 86). Log `.tools/part-8e-verify.log`. No owned container left.
- Contracts 58; frontend 17 reducer tests, build, 7 Playwright tests (real backend + Docker).

## Decisions in this task

- Traced classes: plain classes of the file (top level or static members of Main), no
  extends/implements/type parameters/abstract, not extended, instance fields of traced types.
  Static fields stay uncaptured.
- Contract 1.4.0 (1.3.0 retired): object kind, FIELD_READ/FIELD_WRITE, UNCAPTURED
  objects/unknownObjects; references inside objects captured in one step may refer to each
  other (cycles) and resolve by the end of the step.
- Constructors are traced only with simple field initializers and no this(...); others run
  silently and the object is captured on first use. Entry is after super().
- Field access keeps the original getfield/putfield in user code; a recorder null check
  records the failure (message names the field; known stderr difference).
- 1,024 captured identities per run (Part 4t), raw `T`, halt 86, TRAVERSAL_LIMIT. The
  8,192-slot and 16-hop limits are not enforced yet.

## Resources

- No commands running; no containers.

## Next action

Wait for review/merge of 8c, 8d and 8e. Propose 8f (strings and library collections as
logical operations with list/stack/queue/map/set views) for approval.
