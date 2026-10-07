# Next steps

Last updated: 2026-10-07 (Asia/Saigon).

Confirmed direction: visualize arbitrary user-authored Java as correctly as
possible through reusable language capabilities, never exact program/algorithm
templates. Define coverage to track implementation and truthful limitations,
not to restrict the product to examples. Runtime facts must remain captured,
and execution isolation restrictions still apply.

1. Parts 1 and 2 are merged. Part 3 is now approved on contracts/trace-v1:
   - 3a: trace rules and ADR drafted; verify and commit separately.
   - 3b: encode the agreed shape in a versioned JSON schema.
   - 3c: add valid/invalid examples and validate; pinned Ajv permission pending.
   Keep each part reviewable and push at checkpoints. No application code.
   Execution
   isolation remains a separate later specification part requiring approval.
   Runtime proof for all documented coverage remains pending.
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
was docs/next-specification-part (commit ea4712d). On 2026-10-07, main was pulled
with --ff-only to 9449bd5 (PR #6 merge); Part 2 commit da820b3 and its preceding
memory are verified ancestors. Working tree was clean. Current recovery-memory
branch: docs/recovery-2026-10-07.
Git author identity is resolved.
The `gh` command and GitHub PR tools are unavailable; automated PR creation is
blocked. Publish the branch and provide a GitHub compare link for manual review.
The Step and Java support specifications now exist; no application/build/test artifacts
exist locally. These findings do not establish the status of historical work
outside this workspace.
