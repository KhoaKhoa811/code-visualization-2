# Next steps

Last updated: 2026-10-07 (Asia/Saigon).

Confirmed direction: visualize arbitrary user-authored Java as correctly as
possible through reusable language capabilities, never exact program/algorithm
templates. Define coverage to track implementation and truthful limitations,
not to restrict the product to examples. Runtime facts must remain captured,
and execution isolation restrictions still apply.

1. Parts 1–3 are merged. Part 4a was approved and is drafted in
   specs/execution-isolation.md: runtime timeout rules only, plus both memory
   updates. Review the proposed 5,000 ms initial noninteractive budget, timer
   boundary, whole-environment termination, safe trace retention, and cleanup.
   No enforcement or application/Docker implementation exists yet.
   Pause for review after delivery; memory limits and cancellation are separate
   future tasks. Keep all tasks to one purpose, target 1–3 files, and split long
   changes further. Explain why and which file to read first.
   Completed Part 3 checkpoints:
   - 3a: complete, committed and pushed as 9905d0c.
   - 3b: complete, committed and pushed as afe8bf6.
   - 3c: complete and pushed as ade131d; 46 tests passed after clean locked installation.
     Commit subject: test: validate trace examples and numeric encodings.
     User approved Node/Ajv after clarification; Spring Boot remains the backend.
     Ajv 8.17.1 is pinned under contracts; no Python is used.
   Review contracts/trace/README.md for the commit breakdown and check commands.
   The user requested an explanation; current-state.md records the distinction
   between defining/validating trace records and implementing Java execution.
   No application code. Execution isolation remains the recommended next
   specification part, requiring separate approval; do not start it automatically.
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
branch was docs/recovery-2026-10-07 (272b0fc). Current Part 3 branch is
contracts/trace-v1. Its merge was verified on 2026-10-07: local main was pulled
to 7c94967 (PR #8), containing 4cdc5c8. Task-size preference was pushed as 80d63ef
on docs/smaller-task-plan. Current Part 4a branch: docs/execution-timeout.
Git author identity is resolved.
The `gh` command and GitHub PR tools are unavailable; automated PR creation is
blocked. Publish the branch and provide a GitHub compare link for manual review.
Step/Java specs, a versioned trace schema, contract fixtures and a development-only
Node/Ajv test package now exist. No application/runner implementation exists.
These findings do not establish the status of historical work
outside this workspace.
