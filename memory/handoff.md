# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-18 (Asia/Saigon)
- Owner: Claude Code
- Status: ready for review. No approved task is unfinished.
- Milestone: Part 8 (coverage); Milestone 0, full isolation and V1 incomplete

## Last approved task: Part 8b primitive types and operators (complete)

User (2026-10-18): "ok" to the 8b proposal. Spec:
[specs/primitive-types.md](../specs/primitive-types.md).

- [x] 1. Contract 1.2.0, specs, tracer (types, casts, bitwise, compound, ternary, uninitialized) — 689d883
- [x] 2. Frontend 1.2.0 playback, fixtures, number-types example — e8acf40
- [x] 3. Browser check in Docker — 2653f6c
- [x] 4. Guides and memory (this commit)

## Git

- Branch `feat/part-8b-primitive-types`, stacked on unmerged `feat/part-8a-partial-coverage`
  (653436c) <- 7e (0d87a90) <- 7d (bcbedd1); main 3d8fd53.
- PRs (manual, `gh` unavailable), in order 7d, 7e, 8a, 8b:
  https://github.com/KhoaKhoa811/code-visualization-2/compare/feat/part-8a-partial-coverage...feat/part-8b-primitive-types?expand=1

## Verification (2026-10-18, Claude)

- Root reactor `clean verify`, live Docker: **132 tests, 0 failures/skips**
  (tracer 46, backend 86). Log `.tools/part-8b-verify.log`. No owned container left.
- Contracts 54; frontend 10 reducer tests, build, 4 Playwright tests (real backend + Docker).

## Decisions in this task

- Contract bumped to exact 1.2.0 (operators `& | ^ ~ << >> >>>`, casts); 1.1.0 retired.
- Analyzer reproduces javac constant folding (JLS 15.29) and `coerce` conversions; implicit
  conversions are matched, not steps; explicit casts are one step (two-instruction casts
  folded into one helper).
- `x op= e` gives EXPRESSION + ASSIGN (also when javac emits `iinc`); `a[i] op= e` and
  `a[i]++` give READ, EXPRESSION, WRITE; `b++` on non-int locals gives one UPDATE.
- Declarations without initializer are recorded before the first recorded operation that
  follows them in their block; never-followed declarations leave no step.
- float/double recorded as raw bits; builder recomputes results with Java semantics.
- Lifted the old "constant left comparison operand" restriction (javac only special-cases
  constants on the right).
- 8a tests that used `+=`/`int x;` as "unsupported" now use library calls.

## Resources

- No commands running; no containers.

## Next action

Wait for review/merge of 7d, 7e, 8a and 8b. Propose 8c (statements: do, switch,
for-each over arrays, labeled break/continue, multi-dimensional and other element-type
arrays) for approval.
