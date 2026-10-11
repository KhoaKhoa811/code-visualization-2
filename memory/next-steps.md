# Next steps

Last updated: 2026-10-10 (Asia/Saigon).

Confirmed direction: visualize arbitrary user-authored Java as correctly as
possible through reusable language capabilities, never exact program/algorithm
templates. Define coverage to track implementation and truthful limitations,
not to restrict the product to examples. Runtime facts must remain captured,
and execution isolation restrictions still apply.

1. Parts 1–3 and Parts 4a–4k are merged. Main was pulled with --ff-only to
   1bad656 on 2026-10-10; ancestry checks verified Parts 4h–4k and Part 4j correction
   02b6257. Main matched origin/main; no application/runner implementation exists.
   Milestone 0 remains incomplete. Part 4b commit 1884354 and Part 4c
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
   Runtime checks remain unrun. Part 4e process/thread documentation is merged
   as 95adf12 in main at dc4a332, verified on 2026-10-08. Review
   the proposed 128-kernel-task cap, counting scope, trusted denial evidence,
   limit/error distinction, trace retention, and verified cleanup. Official
   Docker/kernel references were checked; documentation checks passed: 40-line
   section, local references, trace/schema consistency, and whitespace.
   No runtime checks run. Part 4f stdout/stderr documentation is merged
   as ade1c31 in main at de1c660. Review its
   proposed shared 1 MiB compiler/runtime quota, byte accounting/retention,
   output/trace separation, overflow outcome, safe capture, and cleanup.
   Documentation checks passed: 40-line section, byte conversion, local references,
   trace/schema consistency, and whitespace. Runtime checks remain unrun.
   Part 4g trace-data documentation is merged as f0df9cb in main at de1c660,
   in specs/execution-isolation.md with both memory updates. Review its proposed
   8 MiB captured-data and 256 KiB record limits, counting scope, atomic acceptance,
   safe observable-step cutoff, TRACE_LIMIT outcome, and cleanup. Source/terminal
   metadata are excluded and need separate bounds; this is not a whole-envelope
   or decoded-memory guarantee. Documentation checks passed: 40-line section,
   byte conversions, local references, safe-prefix/schema consistency, and whitespace.
   Runtime checks remain unrun.
   Part 4g delivery is complete; no application code or later task approved.
   Part 4h source-size admission documentation was approved by "ok" on 2026-10-09
   and is added in specs/execution-isolation.md with both memory updates. Review
   its proposed 256 KiB exact UTF-8 text cap, decoded counting, guarded early
   rejection, source/hash/UTF-16 preservation, no output-only bypass, and admission
   SOURCE_LIMIT diagnostic. Oversized requests cannot produce a truncated v1
   trace; the bounded request-error contract remains future API work.
   Documentation checks passed: 40-line section, byte conversion, local paths,
   source/admission/schema consistency, and whitespace. Four future checks are
   listed, not run. Part 4h was committed/pushed as 7f138e8 on
   docs/part-4h-source-limits and verified merged in main at 1bad656.
   Part 4i terminal-metadata documentation was approved by "ok" on 2026-10-09
   and is added in specs/execution-isolation.md with both memory updates. Review
   its proposed 32 KiB encoded cap, 8 KiB reserve, 32-diagnostic maximum, and
   1 KiB encoded reason/message limits; guarded buffers; exact identity/outcome/
   primary-cause preservation; and explicit METADATA_TRUNCATED diagnostic.
   Source and captured values cannot be shortened, and metadata omissions do not
   change execution/capture outcomes or imply lost runtime facts. Impossible
   mandatory serialization is an infrastructure failure, not a fake run outcome.
   Existing v1 fields suffice; no schema or replay semantics change.
   Documentation checks passed: 40-line section, byte conversions, local paths,
   quota/source/status/diagnostic consistency, and whitespace. Four future checks
   are listed, not run. Part 4i was committed/pushed as cf88b68 on
   docs/part-4i-terminal-metadata and verified merged in main at 1bad656.
   Part 4j array-capture documentation was approved by "ok" on 2026-10-09 and is
   added in specs/execution-isolation.md with both memory updates. Review its
   proposed 1,024-element per-array cap, actual-length guards before traversal,
   complete contents/shared identities, no expression reevaluation, separate
   Part 4g byte/record quotas, and existing output-only coverage policy.
   Observed capture overflow uses ARRAY_CAPTURE_LIMIT, phase capture, limited
   execution only after confirmed termination, safe partial/unavailable capture,
   and verified cleanup. Never truncate arrays, invent Java events, or change
   actual Java exceptions. Existing schema fields and Step boundaries stay unchanged.
   Documentation checks passed: 40-line section, local paths, array/identity/prefix/
   status/diagnostic consistency, and whitespace. Four future checks are listed,
   not run. Part 4j was committed/pushed as 3b4d504 on
   docs/part-4j-array-capture-limits and verified merged in main at 1bad656.
   Runtime enforcement remains unfinished.
   Part 4j follow-up corrects a reference error missed in earlier verification:
   OBJECT_CAPTURE is String-only in trace v1. The array guard now names only
   initialState and ALLOCATE, with no schema/coverage change. Checks passed:
   40-line section, schema/event-reference consistency, and whitespace.
   Correction committed/pushed as 02b6257 on docs/part-4j-capture-reference-fix;
   it is verified merged in main at 1bad656. Part 4k includes that correction.
   Part 4k call-depth documentation was approved by "ok" on 2026-10-09 and is
   added in specs/execution-isolation.md with both memory updates. Review its
   proposed 64 active user-frame cap including main; invocation counting rather
   than lifetime calls/method count/JVM depth; once-only argument evaluation;
   atomic CALL acceptance; and actual return/unwind depth release with unique IDs.
   Observed overflow uses CALL_DEPTH_LIMIT, phase capture, limited execution only
   after confirmed termination, safe partial/unavailable capture, and verified
   cleanup. Preserve actual Java StackOverflowError behavior, aliases, and backward
   prefix reconstruction. No fake frames/returns/unwinds or schema/Step changes.
   Documentation checks passed: 40-line section, main-plus-63 arithmetic, local paths,
   frame/identity/prefix/status/diagnostic consistency, and whitespace. Four future
   checks are listed, not run. Part 4k was committed/pushed as da8de3a on
   docs/part-4k-call-depth-limits and verified merged in main at 1bad656.
   Part 4l termination confirmation documentation approved by "ok" on 2026-10-10.
   Read its section in specs/execution-isolation.md first. Proposed 5,000 ms begins
   at the latched stop cause and covers control calls/retries without resets.
   Require trusted proof for all run processes and pending launches. Expiry exposes
   infrastructure failure outside the terminal trace and retains the slot/isolation.
   Stop automatic retries; explicit recovery fences pending operations. Late proof
   preserves the original cause and visible failure; cleanup must verify before reuse.
   Four prototype checks are planned, not run. Enforcement remains unfinished.
   Documentation checks passed: 40-line section, local paths, deadline/race,
   trace-status/safe-prefix consistency, and whitespace. No runtime tests run.
   Delivered as 3ab61d1 on docs/part-4l-termination-confirmation; merge not verified.
   Part 4m cleanup confirmation documentation approved by "ok" on 2026-10-10.
   Read its new section in specs/execution-isolation.md first: proposed 5,000 ms
   after confirmed termination; shared transfer/removal/verification/retry budget;
   trusted inventory, owned paths, and proof that no pending operation can recreate
   resources or affect another run. Failure retains the slot/isolation; explicit
   recovery verifies cleanup before one release. Preserve actual execution and
   trace facts; host deletion does not determine Java capture completeness.
   Four prototype checks are planned, not run. Runtime enforcement remains work.
   Documentation review passed: 40-line section, local paths, deadline/race and
   ownership/slot-release rules, trace-status consistency, and whitespace.
   Deliver on docs/part-4m-cleanup-confirmation, then pause for review.
   Planning notes are on docs/part-4l-planning; main was last verified at 1bad656.
   API error shape and runtime implementation remain separate work.
   Transport/request-decoder/framing guards, generated-source bounds, request errors,
   and other remaining isolation definitions are separate unfinished work.
   Recovery on 2026-10-08 found only interrupted proposal notes in the two memory
   files, with no Part 4f specification or recorded approval. Those notes were
   published as e42efe3 and are now verified merged in main at dc4a332. Part 4f
   scope was subsequently approved by "ok, continue" on 2026-10-08. The separate
   branch-naming request itself did not authorize Part 4f implementation.
   Part 4g approval covers documentation only, not runtime implementation.
   No application/Docker implementation is approved. Other remaining
   isolation definitions include collection/object traversal, transport/generated-source
   and decoded-memory bounds, and input-wait limits. Discuss one small scope before
   starting it. Keep tasks to one purpose, target 1–3 files, and split long
   changes further. Explain why and which file to read first.
   Include the part label in all task/planning branch names:
   `<type>/part-<id>-<purpose>`, as requested on 2026-10-08 and recorded in AGENTS.md.
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
Part 4e was pushed as 95adf12 on docs/process-thread-limits. On 2026-10-08, main
was pulled to dc4a332 and ancestry verified 95adf12 and recovery notes e42efe3 merged.
Branch-label preference was pushed as df1e6d4 on docs/part-4f-planning.
Part 4f was pushed as ade1c31 on docs/part-4f-output-limits and verified merged
on 2026-10-09 in main at de1c660.
Part 4g proposal notes were pushed as 6bbbbb1 on docs/part-4g-planning.
Part 4g was pushed as f0df9cb on docs/part-4g-trace-limits and verified merged
on 2026-10-09 in main at de1c660. Recovery notes were pushed as 9072610 on
docs/part-4h-planning. Part 4h was pushed as 7f138e8 on
docs/part-4h-source-limits, based on those notes. It does not authorize runtime
implementation or a later task. Its merge is verified in main at 1bad656 on
2026-10-10. Part 4i proposal notes
were pushed as bdd275d on docs/part-4i-planning. Part 4i was pushed as cf88b68 on
docs/part-4i-terminal-metadata, based on those notes. It does not authorize runtime
implementation or a later task; its merge is verified in main at 1bad656.
Part 4j proposal notes
were pushed as 4c36237 on docs/part-4j-planning. Part 4j was pushed as 3b4d504 on
docs/part-4j-array-capture-limits, based on those notes. It does not authorize runtime
implementation or a later task; its merge is verified in main at 1bad656.
Current task branch: docs/part-4m-cleanup-confirmation, based on planning a1511f7.
Part 4l 3ab61d1 is pushed but its merge is not verified. Part 4m documentation is approved.
Main was last verified at 1bad656. Earlier planning notes: docs/part-4l-planning.
Part 4k proposal notes were pushed as b3528b9 on
docs/part-4k-planning. Correction 02b6257 was pushed on
docs/part-4j-capture-reference-fix and locally fast-forwarded into the approved
Part 4k branch before the user's merges were verified. Runtime implementation and a later
task are not authorized. Recovery on 2026-10-10 pulled main to 1bad656 and verified
Parts 4h–4k and correction 02b6257 merged by ancestry. Part 4k delivery commit:
da8de3a. Parts 4l and 4m documentation are added; no later task is approved.
Git author identity is resolved.
The `gh` command and GitHub PR tools are unavailable; automated PR creation is
blocked. Publish the branch and provide a GitHub compare link for manual review.
Step/Java specs, a versioned trace schema, contract fixtures and a development-only
Node/Ajv test package now exist. No application/runner implementation exists.
These findings do not establish the status of historical work
outside this workspace.
