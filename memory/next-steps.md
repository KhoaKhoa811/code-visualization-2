# Next steps

Last updated: 2026-10-16 (Asia/Saigon).

Standing user preference, clarified 2026-10-14: begin each major part (4, 5, 6,
etc.) with its full task roadmap and intended smaller parts. Begin each smaller
part (5a, 5b, etc.) with its own implementation checklist. Maintain both levels
and update the relevant list when plans change. Apply this to future major parts;
roadmaps do not approve later implementation or expand scope. This preference is
also recorded in AGENTS.md. Latest approved task is Part 5t; no later task is approved.

## Part 5 roadmap and latest handoff

Part 5t approved by "ok" after the 5s handoff. Checklist: review logic/specs,
stop/cleanup failure tests, outcome orderings, visible failure/recovery, publication.
Fetch still reports main 238abf6; test/part-5t-stop-failures starts from 8fe8091
and includes unmerged 5r/5s. Test commit 91a2836 adds disposable script copies and
a test-only fault hook. Seven live checks passed: kill failure, false termination
proof, removal failure, late cleanup proof, early completion, cancellation first,
and successful exit after timeout. Original errors remain visible; separate owned
recovery verifies absence after each case. Syntax/whitespace passed; production
scripts did not change. Exact-boundary timing, hung daemon operations, duplicate
cancellation, launch/input/output races, slots, trace publication, and restart
recovery remain gaps. Publish and stop. Next proposed: 5u final prototype gate
review with explicit verified/unfinished coverage; not approved. Manual PR link:
https://github.com/KhoaKhoa811/code-visualization-2/pull/new/test/part-5t-stop-failures

Part 5s approved by "ok" after the 5r handoff. Checklist: recovery, distinct run
markers, concurrent visibility, fresh-run absence/cleanup/regressions, publication.
Fetch still reports main 238abf6; chore/part-5s-run-isolation starts from f87531c
and includes unmerged Part 5r. Added -RunIsolation, a supervisor and shell fixture.
Private marker bytes, absent foreign files, fresh recreated-peer contents, and
original-run survival after peer cleanup passed on /work, /tmp, and /dev/shm.
Both running restricted profiles match. Two negative cases failed with cleanup;
final isolation/mount/filesystem/normal Java regressions, syntax, fourteen mode
conflicts, and whitespace passed. Kernel/IPC-wide isolation, physical memory erasure,
and cross-run trace/console routing remain unverified. Publish and stop for review.
Next proposed task: 5t stop/cleanup failures and completion/cancel/timeout races;
not approved. Automatic PR creation unavailable; manual link:
https://github.com/KhoaKhoa811/code-visualization-2/pull/new/chore/part-5s-run-isolation

Part 5r is implemented and verified after the 2026-10-16 recovery request.
Pulled main to 238abf6 (PR #72); merged into chore/part-5r-output-quota as 52b2b64
and restored unfinished files without conflicts. Parts 5o–5q are now merged.
Review commits: 5r.1 collector/tests 4d3533c; 5r.2 integration bda02e4; 5r.3 notes.
The fixed -OutputQuota path shares 1 MiB of raw stdout/stderr across compile and
runtime, drains before normal completion, blocks launches after overflow, and
confirms whole-container stop/cleanup. All nine integration cases and local
boundary/concurrency/raw-byte/fault/launch checks passed. Compilation timeout
(10,011 ms), cancellation (488 ms), normal Java, syntax, thirteen mode conflicts,
OutputCase guard, and whitespace checks passed. Restarted installed Docker Desktop.
Legacy probe collectors, general executor integration, analysis output, UI/trace
transport, transport-error attribution, and output/cancellation races remain gaps.
Publish and stop for review. Next proposed task: 5s cross-run file/data isolation;
not approved. Automatic PR creation unavailable; manual link:
https://github.com/KhoaKhoa811/code-visualization-2/pull/new/chore/part-5r-output-quota
Older scope and merge notes below are historical.

Part 5q approved by "ok" after the Part 5p handoff. Checklist: recovery, fixed
compiler stall, deadline/runtime gate, stop/cleanup/regressions, notes/publication.
Fetch still reports main ccf332a. chore/part-5q-compilation-timeout starts from
fac15b1 and retains unmerged 5o/5p. Added -CompilationTimeout and a focused helper
using the existing Main.java. A real javac blocks on an argument-file FIFO;
the final ten-second deadline latched at 10,007 ms. Runtime stayed blocked,
whole-container termination and cleanup passed. Normal compilation, actual Java
error, and fake-compiler controls passed; temporary edits restored. Task-limit
(521 ms), cancellation (433 ms), child-timeout (5,014 ms), normal Java, syntax,
twelve mode conflicts, and whitespace checks passed. Multiple compiler invocations,
noisy output, compiler descendants, phase races, and trace envelopes remain gaps.
Publish and stop for review. Next proposed task: 5r bounded stdout/stderr and shared
byte quota; not approved. Automatic PR creation is unavailable; manual link:
https://github.com/KhoaKhoa811/code-visualization-2/pull/new/chore/part-5q-compilation-timeout

Part 5p approved by "ok, continue" after the Part 5o handoff. Checklist: recover
notes/Git, effective shared cap, fixed denial fixture, evidence/stop/cleanup and
regressions, then notes and publication. Fetch still reports main ccf332a; branch
chore/part-5p-task-limits starts from Part 5o bee99c8, which is not yet on main.
Added -Tasks, TaskLimit.java, and task-probe.ps1. Final fixed case reached 128
tasks, verified thread and process denials, and recorded five PID counter events
in 529 ms without container OOM. Whole-container stop and cleanup passed. Five
negative cases rejected fake/invalid evidence with cleanup; temporary edits restored.
Container-memory (515 ms), heap, cancellation (347 ms), child-timeout (5,016 ms),
normal Java, syntax, eleven mode conflicts, and whitespace checks passed.
Continuous monitoring at saturation, phase/race handling, headroom measurements,
and terminal traces remain work. Publish and stop for review; Part 5q compilation
deadline verification is the next proposal, not approved. Older scope notes below
record their historical state. Automatic PR creation remains unavailable.
Manual PR: https://github.com/KhoaKhoa811/code-visualization-2/pull/new/chore/part-5p-task-limits

Latest recovery: user reported all branches merged and requested newest code.
Pulled main with --ff-only to ccf332a (PR #68), including 618915b; main matched
origin/main and was clean. Earlier pending-merge notes below are historical.
User approved Part 5o with "ok" after its checklist. Completed on
chore/part-5o-container-memory from planning commit 227ea0f: effective 512 MiB/
zero-swap checks, fixed Java memory pressure, trusted cgroup max/oom/oom_kill
deltas, whole-container termination, and cleanup. Final evidence arrived at
569 ms (235/12/2). Five negative cases rejected spoofed output, exit 137 alone,
missing counters, preexisting OOM evidence, and the wrong expected memory limit;
each verified cleanup. Heap, cancellation (414 ms), timeout (5,011 ms), and default
Java regressions, syntax, ten mode conflicts, and whitespace checks passed.
Publish this task and stop for review. Automatic PR
creation is unavailable (no gh or GitHub tool); provide the manual branch PR link.
Next proposed task is 5p process/thread limits; it is not approved. General
submitted-Java classification, partial traces, and stop races remain unfinished.

Part 5n approved and recovered on 2026-10-15, completed on chore/part-5n-java-heap
from 2a07e6d. -Heap verifies configured 128 MiB HotSpot heap, caught continuation/
exit 0, uncaught heap error/exit 1, and zero cgroup OOM/kill counters. Seven negative
cases, cleanup, cancellation (382 ms), timeout (5,003 ms), default Java regression,
syntax, and nine mode conflicts passed. Fetch still reports main 45fa36f; preserved
unfinished files and restarted Docker before tests. Publish and stop for review.
Next proposed task is 5o container memory enforcement; it is not approved.
Submitted-Java classification, partial traces, and container OOM handling remain gaps.

Part 5m approved by "ok" and verified on chore/part-5m-inode-limits from 9f498e5.
-StorageInodes verifies total inode capacities 4096/1024/1024, empty-file exhaustion,
failed file/directory creation with bytes available, restored counts, and reuse.
Four negative cases and cleanup passed. Storage-byte/mount/filesystem/default Java
regressions, cancellation (393 ms), syntax, and eight mode conflicts passed.
Fetch still reports main 45fa36f. Publish and stop for review. Next proposed task
is 5n Java heap exhaustion; it is not approved. Java-specific storage errors,
terminal classification, concurrency, and mqueue resource controls remain gaps.

Part 5l approved by "ok" and verified on chore/part-5l-storage-bytes from 4cd2ead.
-StorageBytes checks effective 64/16/16 MiB tmpfs capacities, fills real pages,
requires extra-byte failure while inodes remain available, restores space, and
verifies reuse. Three negative cases, cleanup, mount/filesystem/default Java
regressions, cancellation (369 ms), syntax, and seven mode conflicts pass.
Latest fetch still reports main 45fa36f. Publish and stop for review. Next proposed
task is 5m inode limits; it is not approved. Java storage-error behavior, terminal
classification, concurrency, and message-queue resource controls remain unverified.

Part 5k approved by "ok, continue implement 5k" and verified on
chore/part-5k-mount-audit from 721f365. -Mounts audits configured/effective mounts,
approved storage modes, /dev permissions, masked null devices, and management
socket absence. Live audit, five altered-inventory failures, extra-tmpfs prelaunch
rejection, relevant existing probes, cleanup, syntax, and six mode conflicts pass.
Finding to retain: mqueue permits queue-entry creation but rejects ordinary byte
writes; the probe removes its entry. Message-queue resource limits and other kernel
interfaces remain unverified. No profile change; no full isolation claim. Fetch
still reports main 45fa36f. Publish and stop. Next proposed part is 5l storage byte
limits; it is not approved. Parts 5l/5m and final review must preserve these gaps.

Part 5j is approved and verified on chore/part-5j-privilege-probe. Recovery pulled
main to 45fa36f (PR #62), including Parts 5f through 5i, and fast-forwarded the
clean task branch. -Privileges checks PID 1/probe identities, groups, empty
capability sets, no-new-privileges, and active seccomp filters. Seven altered-status
rejection cases, cleanup, filesystem/network/default Java regressions, cancellation
(398 ms), syntax, and five mode conflicts passed. Full syscall-policy/adversarial
privilege testing and Java-worker-specific status checks remain unverified.
Publish and stop for review. Next proposed part is 5k mount/writable-path audit;
its implementation is not approved. Older pending-merge notes below are historical.

User approved the next task with "ok, continue doing next task" after the roadmap.
Part 5i is implemented on chore/part-5i-network-probe, based on roadmap commit
02063de. -Network compiles a fixed Java fixture under the existing profile and
checks no published ports, only loopback interfaces/addresses/routes, successful
local TCP transfer, and network-unreachable errors for representative IPv4/IPv6
destinations. Positive and three negative cases passed with cleanup. Filesystem,
cancellation (347 ms), normal compile/output, syntax, and mode-conflict checks pass.
Host/peer literals are representative, not discovered running services. Direct
Windows-listener/live-peer tests and DNS/UDP/adversarial checks remain unverified;
retain those gaps for final review. Latest fetch still reports main 49c9c29.
Publish Part 5i and stop; Part 5j and later implementations require separate approval.

User asked how many Part 5 tasks remain and requested a list. No fixed endpoint
was previously approved. Parts 5a through 5h are delivered; Part 5h is pushed as
76f2321. Propose 13 remaining parts for the runner-isolation prototype:

| Part | Proposed work |
| --- | --- |
| 5i | Implemented loopback-only network probe; evidence and limits recorded above. |
| 5j | Implemented effective privilege probe; verified evidence and limits recorded above. |
| 5k | Implemented mount/writable-path audit; mqueue resource gap recorded above. |
| 5l | Implemented storage byte-limit probe; three tmpfs capacities/exhaustion/reuse verified. |
| 5m | Implemented inode-limit probe; capacities, exhaustion, recovery, and reuse verified. |
| 5n | Implemented Java heap probe; caught/uncaught behavior verified without container OOM. |
| 5o | Implemented fixed container memory/zero-swap probe, trusted OOM evidence, and whole-run stop. |
| 5p | Implemented fixed shared 128-task cap, thread/process denial counters, and whole-run stop. |
| 5q | Implemented fixed javac stall, ten-second deadline, runtime gate, and whole-run stop. |
| 5r | Implemented bounded shared output collector and nine fixed compile/runtime quota cases. |
| 5s | Implemented fixed concurrent/fresh-run marker isolation on all three tmpfs mounts. |
| 5t | Verified seven controlled stop/cleanup failures and completion/cancel/timeout orderings; broader races remain gaps. |
| 5u | Consolidate repeatable checks and review remaining gaps before closing Part 5. |

These labels and the count are proposed, not implementation approval or a promise
that no task will need splitting after inspection. Keep each implementation small.
This endpoint covers fixed runner probes and bounded supervision; it does not close
every Part 4u gate. Admission/queue APIs, interactive input, source analysis,
trustworthy trace transport and capture, replay, and broader V1 features remain
required later increments. Do not drop these requirements or claim full isolation
from passing only this list. The subsequent approval covers Part 5i only.

## Previous handoff

Latest handoff: Part 5h approved by "ok" and verified on
chore/part-5h-filesystem-probe, based on planning notes 16ac4e6. -Filesystem runs
a fixed shell fixture that checks effective root read-only mounting, protected
write denials, and small-file round trips in /work, /tmp, and /dev/shm. Profile
unchanged; cleanup passed. Two negative cases were rejected with cleanup and
fixture restoration. Filesystem, cancellation (322 ms), both timeout modes
(5,009/5,005 ms), normal Java compile/output, syntax, and mode-conflict checks
passed. Publish and stop for review; no later task is approved. Storage quotas,
network, cross-run reads, and a complete writable-path audit remain unfinished.
Fetch still reports main 49c9c29; Parts 5f/5g merges remain unverified.

Latest handoff: Part 5g approved by "ok" and implemented on
chore/part-5g-cancellation-probe, based on planning notes 7026f02. -Cancel reuses
the fixed parent/child fixture and stop/cleanup checks. Live cancellation at
337 ms passed; early child exit and fake readiness markers failed with verified
cleanup. Existing parent/child timeout (5,006 ms), single-loop timeout (5,013 ms),
and normal compile/output checks passed. Temporary fixture edits were restored.
Publish this small task and stop for review; no later task is approved. Application
cancellation APIs, repeated requests, queued/compiling cancellation, input/trace
cutoffs, and race coverage remain unfinished. Fetch still reports main 49c9c29;
Part 5f is pushed as 6486a62 but its merge has not been verified.

Confirmed direction: visualize arbitrary user-authored Java as correctly as
possible through reusable language capabilities, never exact program/algorithm
templates. Define coverage to track implementation and truthful limitations,
not to restrict the product to examples. Runtime facts must remain captured,
and execution isolation restrictions still apply.

1. Recovery on 2026-10-13 pulled main with --ff-only to 49c9c29 (PR #54).
   Parts 5b–5e are verified merged; earlier pending-merge notes below are historical.
   Part 5f approved by "ok, I understand now. Please implement it". Added the
   fixed ParentChildTimeout fixture and -ChildTimeout mode. Require real Java
   readiness and Docker PID/PPID evidence within the existing runtime budget;
   stop process-list polling in the final second. Final timeout at 5,014 ms,
   whole-container termination, and cleanup passed. Early-exiting child and
   markers without a child were rejected; existing single-loop and normal checks
   pass. Publish on chore/part-5f-child-timeout, from planning notes 23cc8dd.
   Stop for review; no API/tracing/playback, arbitrary-descendant proof, or next task approved.
   User asked what parent/child Java fixture means. Explained: a fixed test
   program starts another Java process; both keep running so timeout can verify
   neither survives. This explanation does not approve Part 5f implementation.
   Supplied an illustrative ParentChildExample: parent starts the same Java class
   with a child argument in another JVM; both print their roles and sleep forever.
   That illustrative example was not executed. Subsequent Part 5f approval and
   its distinct verified fixture are recorded above.

   Part 5e approved by "no, please implement it" after discussion. Added a fixed
   loop and timeout-probe.ps1; smoke.ps1 -RuntimeTimeout reuses the container profile
   and cleanup. Compilation precedes the 5,000 ms runtime clock; whole-container
   stop confirmation and cleanup each have their own five-second budget.
   Live timeout handled at 5,017 ms with termination/removal verified. Early-return
   and compiler-error rejection, injected stop-failure visibility, independent
   cleanup inventory, and default Part 5d regression passed. Test modifications
   were restored byte-for-byte. Syntax/whitespace passed. Publish on
   chore/part-5e-runtime-timeout, from planning notes 2fdd335, then stop for review.
   No API/trace handling, full race/descendant/cancellation proof, or later task approved.
   Latest fetch still reports main 30c5171; no newer main was available to pull.

   Part 5d approved by "no, please implement it" after scope discussion.
   Added runner/fixtures/Main.java and extended smoke.ps1 to stage/compile/run it
   inside /work, compare exact runner-ok plus LF, require empty stderr, and verify
   cleanup. Restrictions/heaps/shared wait remain unchanged. Live success and
   compiler-error/wrong-output/missing-LF failures passed with verified removal;
   the fixture was restored byte-for-byte. Syntax/whitespace passed. The missing
   local image was restored using the existing approved digest; no pin changes.
   Publish one task commit on chore/part-5d-compile-run, based on planning notes
   8208882. No submitted-source API, tracing, playback, or broader isolation proof.
   Stop for review; discuss and approve the next small task separately.
   Latest fetch still reports main 30c5171; Parts 5b/5c remain unmerged.

   Previous task: Part 5c, explicitly approved on 2026-10-12. runner/smoke.ps1 and
   its README usage notes are implemented. Live restricted-container check passed:
   UID/GID 10001, Java/javac 21.0.12.1, normal exit, and verified removal. Seven
   simulated lifecycle cases plus syntax/whitespace passed. Publish on
   chore/part-5c-toolchain-smoke, based on Part 5b c4512e4 (not yet merged).
   This verifies a fixed trusted toolchain probe, not submitted-code compilation,
   all kernel restrictions, hostile output, or production lifecycle correctness.
   Next scope requires discussion/approval; stop after this task's delivery.

   Latest recovery on 2026-10-12 pulled main with --ff-only to 30c5171 (PR #48).
   Parts 4t/4u/5a are verified merged. Part 5b fix 5dea983 was local only and its
   image pin/README remained uncommitted. Preserved those files, merged main into
   the Part 5b branch as 8366ca4, and restored the files without conflict.
   Part 5b review is complete: runner/image.json pins the Java 21 JDK image;
   README setup commands reverified after Docker restart. Preflight exits 0;
   platform/digest/Java metadata match the pin. Nine metadata cases, syntax,
   JSON pin, references, ancestry, and whitespace checks passed. Publish both task
   commits and provide manual PR link; automatic PR tooling is unavailable.
   Next: user review, then discuss a small runtime/profile probe scope. No later
   task is approved; no container execution or isolation gate has passed.

   Earlier sync on 2026-10-11 pulled main with --ff-only to c454563 (PR #43).
   Part 4s e8e6ed8 and recovery notes f64fc0c are verified merged. Local main
   matches origin/main. Verification notes were published as 21acb79. Part 4t is
   published as 17478b2; merge unverified. Part 4u is published as 5013e53/5f27982;
   merge unverified. Latest fetch still reports c454563; no newer main to pull.
   Part 5a approved by "ok": runner/preflight.ps1 and runner/README.md now check
   Docker readiness, Linux-container mode, WSL2, and selected local Java 21 image
   metadata. Eight simulated cases and missing/nonzero/timeout CLI probes passed;
   PowerShell 5.1 syntax and whitespace passed. Real WSL2 check passes, but the
   Docker Linux daemon pipe is unavailable; exit 1 and skipped image inspection
   are expected. Successful live image inspection remains unverified.
   No installs, image pulls, container launches, or submitted Java execution.
   Part 5b was explicitly approved: Docker Desktop is now running; Linux/amd64
   engine 29.8.0 responds. Downloaded official eclipse-temurin:21-jdk-noble and
   verified preflight exit 0 against its selected immutable digest. The image
   declares jdk-21.0.12.1+1; fixed preflight to accept Temurin's jdk- prefix.
   Delivery has two small task commits: metadata correction 5dea983, then image
   pin/setup notes. Recovery verification and publication are recorded above.
   No containers or submitted Java were launched. Preflight is not isolation proof.
   Docker/WSL/Java/javac/Node are on PATH; Maven/gh were not found. Pinning and
   enforcement probes require separately approved follow-up work.
   Earlier recovery on 2026-10-11 pulled main with --ff-only to bf58364; ancestry checks
   verified Parts 4l–4r merged. Part 4s e8e6ed8 was then local only; publication is now complete.
   Part 4s review found no correction needed; its base files match current main.
   Earlier recovery verified Parts 1–3 and Parts 4a–4k merged at
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
   Delivered as aa6a59a on docs/part-4m-cleanup-confirmation; merge not verified.
   Remaining Part 4 estimate, proposed on 2026-10-10: seven definition topics
   plus one consistency review. The count question alone approved none of these.
   - 4n: subsequently approved by "ok, continue doing the task"; documentation added.
   - 4o: approved by "ok, continue"; capacity/preparation/compiler documentation added.
   - 4p: approved by "ok, continue"; transport/decoding bounds documentation added.
   - 4q: approved by "ok, continue"; analysis/generated-source documentation added.
   - 4r: approved by "ok, continue"; interactive input/EOF/disconnect documentation added.
   - 4s: approved by "ok, continue"; collection/string capture budget documentation added.
   - 4t: approved by "ok, continue" on 2026-10-11; traversal budget documentation added.
   - 4u: approved by "ok, continue"; consistency review and prototype acceptance checklist.
   This is an estimated breakdown, not eight approved tasks or a 40-line guarantee.
   Part 4n review: read its section in specs/execution-isolation.md. Proposed
   non-root identity, dropped capabilities, no-new-privileges, read-only root,
   seccomp, network none, no host mounts/secrets, and bounded supervisor output.
   Writable tmpfs caps /work 64 MiB, /tmp 16 MiB, /dev/shm 16 MiB total 96 MiB;
   inode caps 4096/1024/1024. Audit other paths and fail closed before submitted
   launch. Four runtime checks remain planned, not run; compatibility is unverified.
   Documentation checks passed: 46-line section including primary references,
   storage arithmetic, local paths, memory/error/trace/cleanup consistency, and whitespace.
   Part 4n delivered as 241d5e9; merge unverified. Part 4o now adds proposed
   one occupied slot, two queued requests, 30,000 ms queue wait, 10,000 ms preparation,
   and 256 MiB compiler heap within the 512 MiB container. Read its new section first.
   Atomic admission/promotion/expiry, unresolved-slot retention, restart reconciliation,
   bounded setup calls, and sequential compilers preserve existing stop/cleanup rules.
   Part 4o delivered as cbcfa2b; merge unverified. Four runtime checks remain unrun.
   Documentation checks passed: 46-line section, heap arithmetic, local paths,
   admission/deadline/slot and trace/error consistency, and whitespace. No runtime tests run.
   Part 4p: read its new transport/decoding section first. Proposed request caps,
   receive deadlines, strict JSON parsing, five-byte trace framing within Part 4g,
   bounded channel/client queues, and 8/64 MiB reservations under a 128 MiB managed-data cap.
   Charge decoded allocations/copies and preserve accepted facts; distinguish valid
   capture limits from corrupt transport. Four runtime checks remain planned, not run.
   Part 4p delivered as 02421b8; merge unverified. Its four runtime checks remain unrun.
   Documentation checks passed: 56-line section, frame/source arithmetic, local paths,
   quota/safe-prefix/status consistency, and whitespace. No runtime tests run.
   Part 4q: read its new section first. Proposed 5,000 ms analysis inside remaining
   preparation time, 256 MiB isolated-worker heap, 65,536 syntax nodes/depth 256,
   2 MiB generated Java and 2 MiB encoded maps. Guard growth and publish artifacts
   atomically with exact source mapping. Known tracing-only limits can use admitted
   original output-only execution once; timeout/crash/uncertain admission cannot bypass policy.
   Part 4q delivered as 38e114a; merge unverified. Four runtime checks remain unrun.
   Documentation checks passed: 47-line section, byte arithmetic, local paths,
   preparation/memory/admission/fallback/source-identity consistency, and whitespace.
   Part 4r: read its new section first. Proposed 120,000 ms interactive execution,
   30,000 ms verified blocked-read wait, and 4/64/16 KiB line/total/pending input.
   Input JSON messages cap at 32 KiB across fragments under Part 4p parsing guards.
   Preserve line order, duplicate handling, Java EOF/token behavior, and cancellation
   cutoff. Controller loss cancels; observers do not. Replay never resends input.
   Part 4r delivered as 4c3a2b6; merge unverified. Four runtime checks remain unrun.
   Documentation checks passed: 56-line section, byte/escaped-line arithmetic,
   local paths, deadline/EOF/disconnect/cancellation and trace-status consistency, and whitespace.
   Part 4s: read its new section first. Proposed 4,096 UTF-16 units per String and
   1,024 logical collection entries; preserve complete content, identities, aliases,
   and actual evaluation. Covered overflow uses safe partial/unavailable capture
   and confirmed limited termination. Trace v1 has no collections; contracts and
   verified adapters remain required before implementation. Four checks are planned,
   not run. Part 4s commit e8e6ed8 was not pushed before interruption; recovery
   rechecked its schema references, safe-prefix rules, and whitespace without
   finding a required correction. Publish on docs/part-4s-collection-string-limits,
   including separate recovery notes. Part 4s is now verified merged at c454563.
   Part 4t: read its new section first. Proposed 1,024 identities per run, 8,192
   inspected slots per pass, depth 16 from the nearest root, and bounded visits
   preserving aliases/cycles. Existing trace v1 does not add general graph support.
   TRAVERSAL_LIMIT rejects the affected record and preserves the safe prefix;
   four runtime checks remain planned, not run. Published as 17478b2 on
   docs/part-4t-traversal-limits; merge unverified. Part 4u review is approved:
   cross-phase corrections are committed as 5013e53; the second commit adds six
   prototype gates. Part 4 documentation is complete; all runtime gates are unrun.
   Review Part 4t before Part 4u because the review branch includes its unmerged work.
   After delivery, discuss one small prototype scope and prerequisites; await approval.
   Enforcement and graph contracts remain work; no implementation is authorized.
   Documentation checks passed: 53-line section, local paths, existing object contract,
   identity/depth/cycle/pass-budget and safe-prefix consistency, and whitespace. No runtime tests run.
   Documentation checks passed: 48-line section, String-only event/UTF-16 schema
   consistency, local paths, alias/callback/safe-prefix/status consistency, and whitespace.
   Group related settings; discuss each scope before starting. Finish the definitions
   needed for bounded execution, then validate/tune values through an approved
   prototype. Do not treat broader V1 coverage or runtime verification as complete.
   Planning notes are on docs/part-4l-planning; latest fetched main is c454563.
   API error shape and runtime implementation remain separate work.
   Transport/decoder/framing and generated-source enforcement, request errors,
   and prototype validation remain unfinished work.
   Recovery on 2026-10-08 found only interrupted proposal notes in the two memory
   files, with no Part 4f specification or recorded approval. Those notes were
   published as e42efe3 and are now verified merged in main at dc4a332. Part 4f
   scope was subsequently approved by "ok, continue" on 2026-10-08. The separate
   branch-naming request itself did not authorize Part 4f implementation.
   Part 4g approval covers documentation only, not runtime implementation.
   No application/Docker implementation is approved. Other remaining
   isolation definitions include collection/object traversal, transport/generated-source
   and decoded-memory enforcement. Discuss one small scope before
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
Current task branch: chore/part-5f-child-timeout, from planning notes 23cc8dd.
Part 5f is approved and verified within the scope above; no later task is approved.
Part 4l 3ab61d1 is pushed but its merge is not verified. Part 4m documentation is approved.
Main was pulled/verified at bf58364 on 2026-10-11; Parts 4l–4r merge statements
above are historical handoff notes. Part 4s was subsequently verified merged at c454563.
Earlier planning notes: docs/part-4l-planning.
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
