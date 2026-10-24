# Next steps

Last updated: 2026-10-24 (Asia/Saigon). Short roadmap and approval boundaries
only. For the active task, [handoff.md](handoff.md) is authoritative. History:
[archive/](archive/README.md).

## Approval boundaries

- Merged: 6e (PR #82), 7a (PR #83), 7b (PR #84), 7c (PR #85), 7d (PR #86), 7e (PR #87), 8a (PR #88), 8b (PR #89),
  8c (PR #90), 8d (PR #91), 8e (PR #92), 8f (PR #93), 8g (PR #94), 8h (PR #95), 8i (PR #96), 9a-9d (PRs #97-#100). ADR 0004 accepted.
- Approved 2026-10-18: revised [coverage roadmap](../specs/coverage-roadmap.md).
- Approved 2026-10-20: Part 9/10 roadmap in [V1 scope](../specs/v1-scope.md).
- Merged: 10a-10c and 10d.0-10d.2 (PRs #101-#104).
- 10d approved (specs/problem-suite.md revision 2, five increments). Done, awaiting review: 10d.3 lambdas,
  10d.4 streams, 10d.5 suite test and examples (AC-36 Done), stacked in that order. Input-wait detection via /proc is deferred to 6g. Propose each with a checklist and wait for approval.
- Merged since: 10d, 6g, 11a-11d (PRs up to #112). V1 support matrix agreed 2026-10-23.
- Part 12 (approved 2026-10-23: "implement more library calls and Visual"): 12a-12d done on
  `feat/part-12-library-visual`, awaiting review.
- Part 13 (approved 2026-10-23: "ok, go ahead with all of them"): call tree, waiting lines,
  kept results. Parts 12 and 13 merged (PRs #113, #114).
- Part 14 (approved 2026-10-23: graph view, start with 2D arrays; -1 is no edge; layered "tree from a
  start node" layout): 14a matrix, 14b neighbour lists, 14c markers and per-node arrays, 14d examples
  and tests, on `feat/part-14-graph`, awaiting review. Possible later: weighted lists ({to, w} rows),
  user classes as graph nodes, a confirmed "grid as graph" (maze) view; each needs approval
  (user 2026-10-24: "no need these options right now").
- Part 15 (approved 2026-10-24): 15a estimates from the code's shape, 15b badges and ranking panel, on
  `feat/part-15-complexity` stacked on Part 14, awaiting review. Proposed later: 15c growth check (re-run
  with bigger n and plot), not approved yet.
- On hold (user: "deployment can hold on"): public-deployment gaps in specs/isolation-review.md
  (auth/quotas/TLS, CPU limit, startup sweep of orphaned `codeviz-run-*` containers, stronger
  sandbox, more slots and persistent state, re-verification on the host).
- Remaining optional ideas, each needing approval: user classes with inheritance, interfaces,
  records, enums; `switch` on String or enum; comparators kept inside sorted collections.
- Parts 14-18 merged (PRs up to #119).
- Part 19 (approved 2026-10-24: "1. it's right 2. show them automatically"): structure detection for
  complicated code, 19a-19f on `feat/part-19-structures`, awaiting review. Spec: specs/structure-detection.md.
  Possible later (not approved): object graphs with several parents drawn as graphs, tries labelled by
  letter, edge lists and grids as graphs, binary trees with parent pointers in the tree view.
- 2026-10-24: the user is still not satisfied with how code is visualized, but declined the researched
  suggestions (step explanations, predict mode, bigger steps, focus mode, inline values, legend, error pictures,
  a memory view): "all that things you just said are not what I want". Stopped there; ask what they want next.
- Never merge pull requests; the user merges.

## Part 7 roadmap (trace engine and first visible slice)

1. 7a engine spike and ADR 0004: merged.
2. 7b host trace-v1 producer, first increment in `main`: merged.
3. 7c run the tracer inside the 6d/6e container lifecycle: stage the recorder,
   compile + instrument in the compile phase, keep the site table outside the
   program's reach, fetch raw records, build the trace. Merged.
4. 7d minimal run API (slimmed 6f): submit, poll, cancel, recover, output.
   Merged.
5. 7e frontend slice: Vite/React/TypeScript, Monaco, playback reducer, array and
   variable renderers; bubble sort demo with browser check. Merged.
6. Part 8 (replaces the old 7f+): 8a graceful partial coverage (merged), 8b primitive (merged)
   types and operators, 8c statements, 8d methods/recursion, 8e objects and fields
   with the object-graph view (moved into Core V1), 8f strings and
   collections (8c-8f merged), 8g live Scanner console, 8h exceptions, 8i hardening (merged); then Core V1 completion 9a-9d (merged) and V1.1 10a-10d. See the coverage roadmap and V1 scope.

Then V1.1: custom mappings, view rankings, separate test inputs, broader coverage.
6g isolation-gap review done 2026-10-23 (specs/isolation-review.md, input-wait limit, AC-20 Done); public deployment needs the listed gaps closed, each with its own approval. V1 support matrix (specs/v1-support-matrix.md) agreed 2026-10-23.

## Part 6 (backend run orchestration)

6a-6e merged; 6f shrunk into 7d; 6g deferred.
