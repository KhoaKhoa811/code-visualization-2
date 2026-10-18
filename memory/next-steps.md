# Next steps

Last updated: 2026-10-18 (Asia/Saigon). Short roadmap and approval boundaries
only. For the active task, [handoff.md](handoff.md) is authoritative. History:
[archive/](archive/README.md).

## Approval boundaries

- Done, awaiting merge: 6e.0 handoff docs and Part 6e (branch
  `feat/part-6e-cancellation-recovery`).
- Approved 2026-10-18: Core V1 / V1.1 tiers (see requirements); Part 7a engine
  spike including trying the ASM dependency; skip 6g and shrink 6f until the
  first visible slice exists.
- Done, awaiting review: 7a spike. Not approved yet: ADR 0004 acceptance, 7b and later.
- Never merge pull requests; the user merges.

## Part 7 roadmap (trace engine and first visible slice)

1. 7a engine spike: ASM bytecode tracer vs source rewriting on AC-01 to AC-05,
   AC-07, AC-10 and recursion; report and ADR 0004. Done; ADR awaiting acceptance.
2. 7b first trace producer: scalars, arrays, comparisons, frames emitting trace-v1,
   run inside the existing 6d/6e container lifecycle. Proposed.
3. 7c minimal run API (slimmed 6f): run, poll, cancel, fetch trace. Proposed.
4. 7d frontend slice: Vite/React/TypeScript, Monaco, playback reducer, array and
   variable renderers; bubble sort demo with browser check. Proposed.
5. 7e+ widen Core V1: methods/recursion view, live Scanner console (WebSocket),
   collections, BST sort and heap sort. Proposed.

Then V1.1: custom mappings, object-graph fallback, view rankings, separate test
inputs, broader coverage. 6g isolation-gap review is deferred until before any
public deployment.

## Part 6 (backend run orchestration)

6a-6d merged; 6e awaiting merge; 6f shrunk into 7c; 6g deferred.
