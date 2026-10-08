# Next steps

Last updated: 2026-10-07 (Asia/Saigon).

Confirmed direction: visualize arbitrary user-authored Java as correctly as
possible through reusable language capabilities, never exact program/algorithm
templates. Define coverage to track implementation and truthful limitations,
not to restrict the product to examples. Runtime facts must remain captured,
and execution isolation restrictions still apply.

1. Parts 1–3 and Parts 4a–4d are merged. Part 4b commit 1884354 and Part 4c
   commit 2ce9ca4 are verified ancestors of main at 8e4af20. Review the proposed
   512 MiB container/128 MiB heap budgets, swap policy, verified OOM classification,
   and safe trace/cleanup behavior. No runtime enforcement has been implemented
   or tested. Part 4c cancellation documentation is merged in
   specs/execution-isolation.md, with both memory updates. Its rules cover
   whole-run termination, waiting input, safe trace retention, cancelled status,
   races, and cleanup. Documentation checks passed: 40-line section, local
   references, trace/schema consistency, and whitespace. No runtime checks run.
   Part 4d compiler-timeout documentation in specs/execution-isolation.md is
   merged as 69f4f40 in main at d75cf05, with both memory updates. Review its proposed
   10,000 ms deadline, shared budget, no execution after timeout, limited versus
   compile_error status, unavailable capture, and cleanup. Documentation checks
   passed: 40-line section, references, trace/schema consistency, and whitespace.
   Runtime checks remain unrun. Part 4e process/thread documentation is approved
   and added in specs/execution-isolation.md with both memory updates. Review
   the proposed 128-kernel-task cap, counting scope, trusted denial evidence,
   limit/error distinction, trace retention, and verified cleanup. Official
   Docker/kernel references were checked; documentation checks passed: 40-line
   section, local references, trace/schema consistency, and whitespace.
   No runtime checks run. Commit/push and pause for review; no next task approved.
   No application/Docker implementation is approved. Other remaining
   isolation definitions include output/trace bounds,
   input-wait limits, and
   termination/cleanup confirmation deadlines. Discuss one small scope before
   starting it. Keep tasks to one purpose, target 1–3 files, and split long
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
on docs/smaller-task-plan. Part 4a commit 781da5c is verified on main at 7cff8f9
(PR #10 merge). Proposal notes were pushed as 20ea472 on docs/memory-limit-proposal.
Part 4b branch: docs/execution-memory, based on those notes.
Cancellation proposal notes are pushed as 7e4f60d on docs/cancellation-proposal.
Part 4c was pushed as 2ce9ca4 on docs/execution-cancellation. On 2026-10-07,
main was pulled to 8e4af20 and ancestry verified both 1884354 and 2ce9ca4 merged.
Compilation proposal notes are pushed as e1ec9a1 on docs/compilation-timeout-proposal.
Part 4d was pushed as 69f4f40 on docs/compilation-timeout. On 2026-10-07, main
was pulled to d75cf05 and ancestry verified that commit merged.
Process-limit proposal notes are pushed as 4099115 on docs/process-limit-proposal.
Current Part 4e branch: docs/process-thread-limits, based on those notes.
Git author identity is resolved.
The `gh` command and GitHub PR tools are unavailable; automated PR creation is
blocked. Publish the branch and provide a GitHub compare link for manual review.
Step/Java specs, a versioned trace schema, contract fixtures and a development-only
Node/Ajv test package now exist. No application/runner implementation exists.
These findings do not establish the status of historical work
outside this workspace.
