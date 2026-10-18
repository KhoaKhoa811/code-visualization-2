# Next steps

Last updated: 2026-10-18 (Asia/Saigon). Short roadmap and approval boundaries
only. For the active task, [handoff.md](handoff.md) is authoritative. History:
[archive/](archive/README.md).

## Approval boundaries

- Done, awaiting merge: 6e.0 handoff-efficiency docs and Part 6e cancellation,
  run slots and recovery. No approved task is unfinished.
- Not approved: 6f, 6g and everything after. Propose with a checklist and wait
  for explicit user approval before starting.
- Never merge pull requests; the user merges.

## Part 6 roadmap (backend run orchestration)

1. 6a Java 21 / Spring Boot / Maven setup: merged.
2. 6b lifecycle contracts: merged.
3. 6c bounded Docker adapter and resource ownership: merged.
4. 6d shared output quota and phase deadlines for fixed fixtures: merged.
5. 6e cancellation, run slots and recovery: done, awaiting merge.
6. 6f fixed-fixture API integration (HTTP, single supervisor wiring): proposed.
7. 6g admission/isolation-gap review before tracing: proposed.

## After Part 6 (direction, not approved)

Follow the requirements' milestone order: trace feasibility for variables,
arrays and a user-written sort (Milestone 1); editor, playback, console and
live input end to end (Milestone 2), including methods/recursion before trees
and heaps; then collections (Milestone 3), trees/heaps (Milestone 4) and V1
completion (Milestone 5). Each needs its own specified scope and approval.
