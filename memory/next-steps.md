# Next steps

Last updated: 2026-10-06 (Asia/Saigon).

Confirmed direction: visualize arbitrary user-authored Java as correctly as
possible through reusable language capabilities, never exact program/algorithm
templates. Define coverage to track implementation and truthful limitations,
not to restrict the product to examples. Runtime facts must remain captured,
and execution isolation restrictions still apply.

1. Part 1 Step specification is merged. Part 2 was approved and is drafted in
   specs/java-support.md, with corresponding Step-document updates. Review the
   proposed initial coverage, method/frame/return rules, and planned acceptance
   cases; runtime proof remains pending. No implementation is authorized.
   Later trace/schema/numeric contracts and execution-isolation specifications
   remain separate proposed parts; do not begin them or application code yet.
   Other open definitions: Java support matrix, separate test-input workflow,
   custom mapping interaction, grid grouping, and measurable V1 completion.
   These are discussion topics, not approved requirement changes.
2. Record every discussion exchange in current-state.md, including user wording,
   assistant explanations, decisions, open questions, and verified outcomes.
   Read that file when recovering context. Do not claim unavailable history.
3. Await the user's next approved implementation task. Milestone 0 remains incomplete. Do not
   treat historical implementation claims in requirements as recovered code or
   authorization to rebuild the application.
4. For every approved task, update `current-state.md` with the conversation,
   decisions, verified results, and blockers; update this file with remaining
   work. Read applicable instructions and requirements before implementation.

Initial baseline `335b769` and memory commit `5579be7` are verified on origin/main.
PR #1 merged the memory update at `f3b4601`. PR #2 merged the requirements
clarification through `46237df` at `b9f63f9`, verified by fetch and ancestry.
Planning commit `3598a8d` and Step specification `d997f32` are verified ancestors
of origin/main at `ca65ddc` (PR #4 merge). The current discussion-memory branch
was docs/next-specification-part (commit ea4712d). Part 2 uses docs/java-support
based on that memory commit; the latest fetch did not show its merge into main.
Git author identity is resolved.
The `gh` command and GitHub PR tools are unavailable; automated PR creation is
blocked. Publish the branch and provide a GitHub compare link for manual review.
The Step and Java support specifications now exist; no application/build/test artifacts
exist locally. These findings do not establish the status of historical work
outside this workspace.
