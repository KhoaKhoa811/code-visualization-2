# Next steps

Last updated: 2026-10-18 (Asia/Saigon). Short roadmap and approval boundaries
only. For the active task, [handoff.md](handoff.md) is authoritative. History:
[archive/](archive/README.md).

## Approval boundaries

- Merged: 6e (PR #82), 7a (PR #83), 7b (PR #84), 7c (PR #85). ADR 0004 accepted.
- Done, awaiting review: 7d minimal run API (branch `feat/part-7d-run-api`).
- Not approved yet: 7e and later. Propose with a checklist and wait for approval.
- Never merge pull requests; the user merges.

## Part 7 roadmap (trace engine and first visible slice)

1. 7a engine spike and ADR 0004: merged.
2. 7b host trace-v1 producer, first increment in `main`: merged.
3. 7c run the tracer inside the 6d/6e container lifecycle: stage the recorder,
   compile + instrument in the compile phase, keep the site table outside the
   program's reach, fetch raw records, build the trace. Merged.
4. 7d minimal run API (slimmed 6f): submit, poll, cancel, recover, output.
   Done, awaiting review.
5. 7e frontend slice: Vite/React/TypeScript, Monaco, playback reducer, array and
   variable renderers; bubble sort demo with browser check. Proposed.
6. 7f+ widen Core V1: helper methods/recursion and call-stack view, live Scanner
   console (WebSocket), uninitialized declarations, strings, collections, BST
   sort and heap sort. Proposed.

Then V1.1: custom mappings, object-graph fallback, view rankings, separate test
inputs, broader coverage. 6g isolation-gap review is deferred until before any
public deployment.

## Part 6 (backend run orchestration)

6a-6e merged; 6f shrunk into 7d; 6g deferred.
