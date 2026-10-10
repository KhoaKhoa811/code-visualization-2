# Execution isolation: timeouts, memory, and cancellation

Status: Milestone 0, Parts 4a–4r, 2026-10-10. Proposed rules for review.
Resource-limit and cancellation enforcement are not implemented or verified yet.

Sources: [requirements, section 10](../requirements/PROJECT_REQUIREMENTS.md)
and [trace termination rules](trace-format.md#11-termination-and-safe-capture).

## Proposed timer

- Initial noninteractive prototype limit: **5,000 ms of elapsed time**.
- An external supervisor starts a monotonic timer immediately before requesting
  launch of the submitted Java JVM. JVM startup counts toward this limit.
- Queueing, environment preparation, and compilation precede this timer.
  Parts 4d/4o propose compiler and queue/preparation deadlines respectively.
- The timer is independent of submitted code, stdout, and trace activity.
- Waiting or blocking does not pause this timer. Input arrival does not reset it.
- The 5,000 ms value is a prototype proposal to measure, not a verified V1 default.
  Part 4r proposes a separate interactive profile and input-wait budget.

## At the deadline

1. If process termination was already confirmed, retain its actual outcome.
   Otherwise latch the timeout once; a later exit cannot turn it into success.
2. Stop accepting input and new execution records. Preserve only records already
   accepted atomically as a valid safe prefix; discard incomplete trailing data.
3. Request forced termination of the entire isolated run environment, including
   child processes. Killing only the submitted Java process is insufficient.
4. Confirm termination before publishing the terminal trace envelope. While
   confirmation is pending, show that the run is stopping; do not claim it ended.
5. Publish execution.status = limited and reason = "Execution exceeded 5000 ms".
   Add an execution diagnostic with code EXECUTION_TIMEOUT and that same reason.
6. Use capture.status = partial for a trustworthy initial state and safe prefix,
   or unavailable if none exists. Never claim complete capture after timeout.
7. Remove the run's temporary resources. Release its concurrency slot only after
   termination and cleanup are verified. Retain accepted output and trace facts.

If termination or cleanup cannot be verified, expose the infrastructure failure
and retain the occupied slot; do not report successful cleanup or start another
run in that environment. Parts 4l and 4m propose termination and cleanup confirmation bounds.
Do not manufacture a Java exception, return, scope exit, or mutation on timeout.

## Required prototype checks

- A short program finishes below the limit and retains its real terminal status.
- An infinite loop reaches the deadline; all run processes stop and files clear.
- A cut-off trace record is discarded; earlier valid facts remain replayable.
- A completion/deadline race produces exactly one latched terminal outcome.
- Blocking execution still reaches the deadline; activity never resets the timer.

These are future checks, not test results. Part 4a covers runtime timeouts only.
Collection/object traversal, input-wait, and transport bounds remain work;
Parts 4b–4k propose memory, cancellation, compilation, process/thread, output,
trace-data, source, metadata, array, and call-depth rules; enforcement is unfinished.

## Proposed memory budget — Part 4b

- Initial prototype container budget: **512 MiB (536,870,912 bytes)** per run.
- Submitted Java JVM heap cap: **128 MiB (134,217,728 bytes)**, using `-Xmx128m`.
  Heap includes application objects and any tracing buffers allocated there.
- The container cap covers all its processes and accounted memory, including
  compilation. Heap is one portion; other JVM/native memory needs headroom.
- Set the hard container limit before compilation/execution. A soft reservation
  alone does not enforce this budget. Failure to establish limits blocks launch.
- Proposed Docker values: `--memory=536870912 --memory-swap=536870912`.
  Equal memory and memory-swap limits prevent extra swap allowance. Keep the
  container OOM killer enabled and verify host enforcement in the prototype.
- These budgets are candidates to measure, not verified V1 sizing guarantees.
  Part 4o proposes compiler heap and run capacity; capture/traversal limits remain
  work. Never increase this budget silently for one program.

### When memory is exhausted

Use trusted container evidence to identify a container memory-limit termination;
printed text or exit code 137 alone does not prove that cause. Stop input and
record acceptance, terminate any surviving run processes, then verify cleanup
using the timeout section's procedure. Publish execution.status = limited with
diagnostic code MEMORY_LIMIT and an explanation of the verified container limit.
Capture is partial at the last safe accepted boundary, or unavailable; never
fabricate missing writes, exceptions, or completed cleanup.

An OutOfMemoryError reported by Java is a Java error, not proof of a container
kill. Preserve caught-error behavior; an uncaught error has failed status with
available safe capture. Do not relabel an explicitly thrown error as a verified
resource limit. Detection and trustworthy error capture require prototype proof.

### Required prototype checks

- Small compilation/execution succeeds; measured budgets and JVM settings agree.
- Heap exhaustion preserves actual Java error behavior and available safe facts.
- A verified container OOM stops the whole run and releases resources after cleanup.
- Incomplete final records are discarded; spoofed output cannot assert MEMORY_LIMIT.

No checks above have run. Reference: [Docker memory limits](https://docs.docker.com/engine/containers/resource_constraints/#memory)
and [Java 21 heap options](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html).

## Proposed cancellation rules — Part 4c

- Cancellation targets one run identity, never another run or a reused environment.
  The trusted external supervisor accepts the request; stdout cannot request it.
- Permit cancellation while queued, preparing, compiling, executing, or waiting
  for input. A cancelled queued run must never launch; stop any active compiler.
- Serialize cancellation with confirmed termination and verified limit triggers.
  Keep an already latched outcome; otherwise, confirmed termination retains its
  actual outcome. If neither exists, latch cancellation once. A later normal exit
  cannot replace it with success. Repeated requests do not restart cleanup.
- On acceptance, stop input delivery and execution-record acceptance together.
  Drop pending input; a concurrent input request must not write after this cutoff.
  Preserve only the already accepted safe prefix; discard incomplete records.
- Force termination of the entire isolated environment, including descendants.
  Do not depend on submitted Java accepting interruption or handling a signal.
- While termination is unconfirmed, report that the run is stopping. Do not
  publish a terminal trace envelope or claim that execution has ended.
- After confirmed termination, publish execution.status = cancelled with reason
  "Run cancelled by user" and an execution diagnostic with code RUN_CANCELLED.
  A never-launched run needs confirmation that no launch or run process remains.
- Capture is partial with a trustworthy initial state and safe accepted prefix,
  or unavailable without one. Cancellation never yields complete capture, even
  if execution exits normally after cancellation was latched. Retain accepted
  output and source/run associations; replay never resends input or reruns Java.
- Do not manufacture an exception, return, scope exit, or successful mutation.
  Release the run slot only after termination and temporary-resource cleanup
  are verified, following the timeout procedure. Keep failed cleanup visible.

Parts 4l and 4m propose termination/cleanup confirmation and retry bounds.
Cancellation must not permit an unlimited orphaned worker;
these proposed bounds do not establish implemented or verified enforcement.
Part 4r proposes disconnect/input-wait rules; API message contracts remain separate work.

### Required prototype checks (planned, not run)

- Cancel a loop or blocked input read; all run processes stop and cleanup verifies.
- Cancel before launch or during compilation; no submitted program starts later.
- Race cancellation with completion, a limit, repeated cancellation, and input:
  one outcome remains; no input is delivered after the accepted cancellation cutoff.
- Cut off a record: keep only safe facts. Failed cleanup keeps the slot occupied.

## Proposed compilation timeout — Part 4d

- Initial prototype budget: **10,000 ms elapsed per run**, proposed, not verified.
- A trusted external supervisor starts one monotonic timer immediately before
  requesting the first isolated compiler launch; compiler JVM startup counts.
  All compiler invocations for that run share this deadline without resets.
- Compiler output, retries, or activity never pause or extend the deadline.
  Queueing and preparation precede it under Part 4o; Part 4q bounds analysis
  within preparation. The run-container memory cap still applies.

### At the compilation deadline

1. Serialize the deadline with cancellation, verified limits, and compiler exits.
   An already latched stop cause keeps its own handling. If the entire compilation
   phase has ended, ignore this deadline. Otherwise latch this timeout once and
   follow the steps below; later compiler success cannot erase it.
2. For this timeout, prevent further compiler or submitted application JVM launch.
   Ignore late success notifications; do not execute partially produced classes.
3. Force termination of the entire isolated run environment, including compiler
   descendants. Report stopping until termination is confirmed.
4. Only then publish execution.status = limited with reason
   "Compilation exceeded 10000 ms" and a compile-phase diagnostic with code
   COMPILATION_TIMEOUT and the same message. Do not label this compile_error.
5. Runtime capture is unavailable: initialState is null, events and stepEnds
   are empty, and safeEventCount is zero. Retain accepted compiler diagnostics;
   never invent runtime events or source locations for a timeout.
6. Verify temporary-resource cleanup before releasing the slot, following the
   existing timeout procedure. Failed confirmation/cleanup keeps the slot occupied.

Normal compiler rejection instead yields compile_error with unavailable capture
and actual diagnostics, unless a stop cause was already latched. Start the separate
5,000 ms execution timer only after all required compilation succeeds with no
latched stop cause. Confirmation/retry deadlines follow Parts 4l/4m.

### Required prototype checks (planned, not run)

- Short compilation succeeds; normal Java errors retain their actual diagnostics.
- Stalled compilation reaches the deadline; all processes stop and cleanup verifies.
- Race success/cancellation with the deadline: one outcome; no launch after timeout.
- Multiple compiler invocations share one budget; output activity never resets it.

## Proposed process/thread budget — Part 4e

- Proposed cap: **128 simultaneous Linux kernel tasks per run container**, unverified.
  Count compiler/runtime processes, descendants, JVM native threads, and helpers.
- Proposed Docker setting: `--pids-limit=128`, established before compilation.
  Block launch if enforcement cannot be verified; never silently raise the cap.
- Kernel task identities count; Java virtual-thread objects do not.
  PID enforcement denies creation without killing existing processes; admission
  and tracing coverage stay unchanged. The supervisor runs outside this budget.
- Verify the deployed cgroup version and PID controller/counter semantics first.
  For the proposed cgroup v2 profile, establish a fresh run's pids.events:max
  baseline and trusted read access; submitted code cannot alter the controller.

### Verified limit handling

Check for a run-attributed denial counter increase during compilation/execution,
before further compiler/application launch and before finalizing normal completion.
Attribute the evidence to this run/controller, accounting for parent limits.
Reaching 128 tasks alone, printed text, exit codes, or Java errors do not prove a hit.

Serialize verified evidence with cancellation/termination; keep an already latched
outcome. Otherwise latch the PID limit, stop input/record acceptance and new launches,
then terminate the entire environment. Only after termination is confirmed publish
execution.status = limited and diagnostic code PROCESS_THREAD_LIMIT with the verified
reason and phase compile or execution. Retain safe partial capture after execution,
or unavailable before execution/without trusted initial state; never complete.
Discard incomplete records; invent no Java events. Release the slot only after
verified cleanup. Failed confirmation/cleanup follows the existing timeout rules.

Without trusted PID evidence, preserve actual Java/compiler errors, including
OutOfMemoryError. Monitoring and confirmation/retry bounds remain runner work.

### Required prototype checks (planned, not run)

- Ordinary compilation/execution fits; measure all JVM/helper tasks and headroom.
- Creation is denied at the cap; the supervisor stops all existing run tasks.
- Spoofed errors do not assert a hit; fast exits/cancellation keep one outcome.
- Failed cleanup retains the slot; another run's counters never affect this run.

References: [Docker](https://docs.docker.com/reference/cli/docker/container/run/), [Linux PID controller](https://docs.kernel.org/admin-guide/cgroup-v2.html#pid).

## Proposed stdout/stderr budget — Part 4f

- Proposed quota: **1 MiB (1,048,576 bytes)** combined output per run, unverified.
- Count raw stdout and stderr bytes from compilation and execution, including
  descendants, before decoding/filtering. Share one atomic counter across streams
  and all compiler invocations; starting execution does not reset it.
- Exact-quota output is allowed; the first observed byte beyond it triggers the
  limit. Retain only the fitting prefix of a chunk that crosses the boundary.
- Preserve each stream's byte order and identity; do not claim a total Java write
  order across stdout/stderr. Count bytes, not characters, lines, or UI markup.
- Trace transport and supervisor diagnostics are separate from this console
  quota. Printed text cannot become trace events or trusted limit diagnostics.
- Use bounded read buffers and queues; never accumulate excess bytes or permit
  unbounded duplicate container logs. Slow/disconnected consumers cannot lift
  the quota. Part 4p proposes concrete buffering/transport guards.

### At the output limit

Latch overflow against cancellation/other limits; keep an already latched stop
cause. Stop input, trace-record acceptance, and further compiler/application launch.
Discard excess console bytes and incomplete trace records; preserve the already
accepted output prefix and safe trace boundary. Terminate the entire environment.
Drain each compiler's streams before launching further compilers/the application,
and runtime streams before final normal completion. A fast exit cannot hide
buffered overflow. Do not wait for input or consumers; activity resets no timer.

If overflow wins, publish execution.status = limited after confirmed termination,
with diagnostic code OUTPUT_LIMIT, phase compile or execution, and quota reason.
Capture is unavailable before execution/without a trusted initial state, otherwise
partial at the safe accepted prefix; never complete. Mark console output incomplete;
retain exact accepted bytes, without inventing missing characters, newlines, source
locations, or Java events. Replay never resends input. Verify cleanup before releasing
the slot; failed termination/cleanup follows the timeout procedure.

### Required prototype checks (planned, not run)

- Quota minus one/exact quota succeed; one extra byte yields the limit outcome.
- Split multibyte/chunked output counts bytes; stdout/stderr share one quota.
- Compiler output reduces runtime allowance; overflow prevents application launch.
- Fast exit, slow consumer, and cancellation races preserve one outcome and cleanup.

## Proposed trace-data budget — Part 4g

- Prototype quota: **8 MiB (8,388,608 bytes)** captured data per run, unverified.
- Maximum encoded initial-state/event record, including framing: **256 KiB (262,144 bytes)**.
- Count uncompressed UTF-8 JSON bytes for initialState, every event, and stepEnds,
  including punctuation, escapes, and transport framing. Never refund discarded
  suffix bytes; exact-quota data is allowed, an addition beyond it triggers a limit.
- Bound encoding/framing buffers before assembling or parsing a whole record.
  Oversized records cannot bypass the guard by being split into smaller chunks.
- Never truncate a value/array/object or split an event to make it fit. stdout/stderr
  has its separate quota; printed text cannot become accepted execution facts.
- Source and terminal envelope metadata are outside this captured-data quota;
  Parts 4h/4i propose source admission and terminal metadata; enforcement remains unfinished.
  This is not a whole-envelope bound or proof of bounded decoded memory.

### At a trace-data limit

Serialize the limit with cancellation/other stop causes; retain a latched outcome.
Otherwise latch the data/record limit. Accept an event and its cursor-index changes
atomically only if they fit. Drop the overflowing/incomplete record and unsafe suffix.
Retain initialState and events only through the last safe observable record; discard
trailing bookkeeping, even if its bytes fit. Set stepEnds and safeEventCount to that
same prefix under trace-format.md. With a trusted initial state but no safe operation,
partial capture has empty events/stepEnds; without one, capture is unavailable.

Stop input/record acceptance and new launches; terminate the whole environment.
If this limit wins, only after confirmed termination publish execution.status = limited,
diagnostic code TRACE_LIMIT with phase capture, and a reason naming the exceeded quota.
Capture is partial or unavailable, never complete. Preserve accepted console output;
invent no mutations, object contents, returns, scope cleanup, or rerun/input replay.
Verify temporary-resource cleanup before releasing the slot; failed confirmation
or cleanup follows the existing timeout procedure. Check final trace data before
normal completion so a fast exit cannot hide an oversized final record/index update.

### Required prototype checks (planned, not run)

- Exact byte/record limits fit; excess, multibyte text, and JSON escaping count correctly.
- Large initial/event records are refused without buffering the whole oversized record.
- Overflow between operations drops trailing bookkeeping and restores the last safe state.
- Missing initial state, final-index overflow, and cancellation preserve truthful status/cleanup.

## Proposed source-size admission budget — Part 4h

- Prototype cap: **256 KiB (262,144 bytes)** of submitted Main.java text, unverified.
- Count the exact well-formed text encoded as UTF-8, before Java Unicode-escape
  processing. Count comments, whitespace, line endings, and any leading BOM.
- Decode request escapes before counting; JSON spelling/framing is not Java text.
  Identical decoded text has the same size regardless of request escape choices.
- Exactly the cap passes this size check; exceeding it rejects admission.
  Passing this check does not establish Java validity, execution policy, or coverage.
- Check incrementally before queueing, source analysis, instrumentation, temporary
  source-file creation, or compiler/application launch; guard buffers while decoding.
  Stop retaining source as soon as overflow is known, including across chunks.
- Malformed Unicode fails source validity; never replace invalid units to fit.
- This decoded-source cap does not bound request bodies, JSON overhead, generated
  instrumentation, or decoded memory. Part 4p proposes transport/decoder guards;
  Part 4i proposes separate terminal-metadata limits. Enforcement remains unfinished.

### Source preservation and rejection

For admitted source, preserve the exact text and its UTF-8 SHA-256 under
trace-format.md. Do not normalize whitespace/line endings, remove comments/BOM,
or shorten text. Source highlights retain the original UTF-16 offsets.
This source allowance is separate from Part 4g captured-data/record quotas;
JSON escaping when serializing source is not charged to those captured-data quotas.

Reject oversized submissions before run admission; no compiler or submitted JVM
starts, and output-only execution cannot bypass the cap. Report an admission
diagnostic with code SOURCE_LIMIT and a fixed message naming the byte cap.
Do not echo source in errors/logs or fabricate source ranges, output, or events.
Do not emit a trace-v1 envelope with missing/truncated source or a prefix hash:
that contract requires the full exact source. Use a bounded admission error;
its machine-readable request-error contract remains future API work.
This is not a runtime limited or compile_error outcome; no playback exists.

### Required prototype checks (planned, not run)

- ASCII at cap minus one/exact cap passes size admission; one extra byte rejects.
- Multibyte/supplementary text, BOM, CRLF, and request escapes count exact UTF-8 bytes.
- Chunked overflow stops retention; no queue, source file, compiler, or JVM is created.
- Accepted source/hash/UTF-16 ranges stay exact; rejection echoes no source or fake trace.

## Proposed terminal-metadata budget — Part 4i

- Prototype cap: **32 KiB (32,768 bytes)** retained encoded metadata per run, unverified.
- Count uncompressed UTF-8 JSON envelope bytes excluding only the encoded values
  of source, initialState, events, and stepEnds; include keys, separators, and escapes.
- Reserve **8 KiB (8,192 bytes)** within this cap for mandatory fields, primary
  execution/capture diagnostics, and a metadata-shortening notice; optional text
  cannot consume the reserve. Retain at most **32 diagnostics**, including notices.
- Each execution/capture reason and diagnostic message is at most **1 KiB (1,024
  bytes)** as an encoded JSON string, including quotes, escapes, and any notice.
- Guard collection/encoding buffers while receiving text; never first assemble a
  huge message/list. Exactly a cap fits; optional additions beyond it are omitted.
- Verify bounded runIds/codes and reserved fallback before admission; never truncate them.
- Parts 4p/4q propose framing/decoded-memory and generated-source bounds.
  API request-error contracts and enforcement remain unfinished work.

### When explanatory metadata does not fit

Keep execution.status, capture.status, safeEventCount, schemaVersion, runId,
and the real execution/capture causes exact. Use bounded reasons naming those causes
before optional detail. Retain their primary diagnostic codes/phases and known ranges.
Keep further diagnostics in accepted order only while byte/count budgets fit;
count reserved primary diagnostics and the notice before accepting optional entries.
Shorten only explanatory text at Unicode boundaries with "[text shortened]";
include that marker in the string cap. Never split a surrogate pair or JSON escape.
When text is shortened or diagnostics omitted, emit one capture-phase diagnostic
with code METADATA_TRUNCATED and a fixed message describing those omissions.

Preserve real outcomes and the safe prefix; this notice does not imply missing facts.
Never add fake outcomes/exceptions/values/cleanup; source/events cannot be shortened here.
Emit only after required termination confirmation; cleanup/slot rules still apply.
If reserved mandatory metadata cannot fit, report an infrastructure failure outside
the trace; emit no invalid envelope or fake outcome. Use existing v1 fields only.

### Required prototype checks (planned, not run)

- Byte/count/string boundaries and escaped Unicode fit or produce an explicit notice.
- Diagnostic floods/huge messages stay bounded and cannot displace terminal causes.
- Completion, failure, cancellation, limits, and unavailable capture keep real outcomes.
- Serialization preserves source/events/identities/ranges; impossible reserve reports infrastructure failure.

## Proposed array capture budget — Part 4j

- Prototype cap: **1,024 elements per captured one-dimensional array**, unverified.
- Length exactly at the cap passes this gate; empty arrays pass. Null is not an array.
- Check actual length before traversing/encoding elements; bound capture buffers first.
- Capture only already evaluated references. Never reevaluate allocation, size,
  initializer, index, or RHS expressions; preserve effects and original exceptions.
- Apply the gate when an array first enters initialState or ALLOCATE.
  Aliases reuse its objectId/state, not duplicate objects or separate allowances.
- Within the cap, capture all elements exactly; never keep a shortened array,
  replace missing elements with null/defaults, or drop references to make it fit.
- Part 4g byte/record quotas still apply; element count does not bound large strings,
  many arrays, reference traversal, or decoded memory. Their other guards remain work.
- This bounds capture, not Java admission. Known coverage limits use java-support.md's
  admitted output-only policy; never silently rerun after partial execution.

### When an actual captured array exceeds the cap

Serialize this finding with cancellation/other limits; retain an earlier stop cause.
Otherwise latch the array limit; use code ARRAY_CAPTURE_LIMIT, phase capture, and quota reason.
Accept no incomplete initial state or array-bearing record. Keep the trusted initial
state and records only through the last safe observable step under Part 4g;
drop unsafe trailing bookkeeping and align stepEnds/safeEventCount to that prefix.
No trusted initialState means unavailable with null initialState and empty events/stepEnds.
With one, partial may have zero operations and empty events/stepEnds; never complete.

Stop accepting input/records and new launches; terminate the whole run environment.
Only after confirmed termination publish execution.status = limited if this cause
wins. Preserve accepted console bytes and earlier trace facts; invent no allocation,
binding, mutation, exception, return, cleanup, or source range for the missing record.
Preserve actual Java failures; the capture guard never preempts original evaluation.
Verify cleanup before releasing the slot; failed termination/cleanup follows timeout rules.
No array schema fields or Step boundaries change; this is not truncated-array support.

### Required prototype checks (planned, not run)

- Empty/null and cap minus one/exact cap/excess preserve distinct truthful outcomes.
- Aliases retain one identity; accepted contents and initializer side effects stay exact.
- Oversized initial/runtime capture stays bounded and retains only the safe prefix.
- Java failures, cancellation, byte limits, output, termination, and cleanup stay truthful.

## Proposed call-depth capture budget — Part 4k

- Prototype cap: **64 active captured user frames, including main**, unverified.
- Count invocations, including recursive/mutual/nested helpers, not distinct methods
  or total lifetime calls. Compiler, tracer, and JDK frames do not consume this cap.
- Guard actual covered entry after arguments evaluate once in Java order, before
  copying/encoding another frame payload. A throwing argument enters no callee.
- Accept CALL and its frame/scope/parameter bindings atomically only if depth and
  Part 4g byte/record budgets fit. Guard initialState frame capture too.
- Covered returns/unwinding release depth; preserve RETURN/FRAME_END effects; never reuse IDs.
- Recursive invocations retain distinct locals/parameters and shared object aliases.
- This bounds captured user depth, not JVM stack bytes or library recursion. Other
  memory/time/capture bounds and existing admitted output-only coverage policy apply.

### When the next captured frame exceeds the cap

Serialize the finding with cancellation/other limits; retain an earlier stop cause.
Otherwise latch the limit; use code CALL_DEPTH_LIMIT, phase capture, and quota reason.
Accept no overflowing CALL, partial frame, or parameter placeholder. Retain only
the trusted initial state and last safe observable prefix under Part 4g, excluding
unsafe trailing bookkeeping; stepEnds/safeEventCount describe that same prefix.
Without a trusted initialState, capture is unavailable with empty events/stepEnds;
otherwise partial may have zero operations. Never label limit capture complete.

Stop input/record acceptance and new launches; terminate the whole run environment.
Publish execution.status = limited only after confirmed termination if this cause
wins. Preserve accepted output and facts; invent no callee entry, return, unwind,
exception, binding removal, or source range. Verify cleanup before releasing the slot;
failed termination/cleanup follows the timeout rules. Never silently rerun Java.
A real StackOverflowError retains caught/uncaught behavior under supported coverage;
it does not establish this capture limit, and the guard must not throw a fake one.
Backward replay restores frames, locals, and aliases from the accepted prefix only.
No Java calls during replay; schema fields and observable Step boundaries stay unchanged.

### Required prototype checks (planned, not run)

- Main plus 63 helpers fits; the next active entry limits; shallow calls do not hit this cap.
- Recursion/mutual recursion preserve unique frames, arguments once, and shared aliases.
- Throwing arguments/real stack failures and backward return/unwind replay stay truthful.
- Initial/entry overflow, byte limits, cancellation, output, termination, and cleanup stay safe.

## Proposed termination confirmation deadline — Part 4l

- Prototype budget: **5,000 ms elapsed time**, proposed and unverified.
- Start an external monotonic deadline when cancellation or a limit is latched,
  before any termination control call. This budget is separate from phase timers.
- Cover queued/preparing/compiling/executing/input-wait runs and all descendants.
- Stop new launches, input, and record acceptance at the existing stop cutoff.
- Control calls, polling, and retries share this deadline; each wait uses at most
  the remaining time. Repeated cancellation, activity, or errors never reset it.
- Bound control workers; hung or abandoned calls must not block deadline handling.
- Request forced whole-environment termination; Java cooperation is not required.
  A request acknowledgement, main-process exit, or closed pipe alone is not proof.
- Require trusted evidence for this exact run that all its processes stopped and
  no pending launch can start later. Never-launched runs need the latter proof too.

### Confirmation, expiry, and recovery

Serialize evidence acceptance and deadline expiry with the latched stop cause.
Evidence accepted before expiry permits the existing cancelled/limited outcome;
an already confirmed earlier termination retains its actual outcome. At or after
the deadline, first record confirmation failure; never reset or hide the expiry.
Expose infrastructure failure outside the v1 terminal trace; unknown termination
is not a Java failure or a new execution.status. API error shape remains future work.
Retain the occupied slot, run identity, isolation, accepted output, and safe prefix.
Publish no terminal trace or playback while termination remains unconfirmed.
Do not reuse the environment, start a replacement there, or claim successful cleanup.
Stop automatic retries at expiry; retain the unresolved run for explicit recovery.
Recovery must fence pending launches/control operations and verify this same run.
Late trusted proof may finalize the original cause once; keep the infrastructure
failure visible. Never add Java events or accept records after the original cutoff.
Cleanup requires separate verification before slot release under Part 4m.
This budget bounds supervisor waiting, not guaranteed process death
during infrastructure failure. Runtime enforcement and recovery need prototype proof.

### Required prototype checks (planned, not run)

- Confirm before, exactly at, and after expiry: one cause; late proof cannot hide failure.
- Hang control calls/retries; deadline handling stays responsive without worker buildup.
- Cancel before/during launch or with descendants: acknowledgement alone cannot release the slot.
- Lose confirmation, then recover: no premature trace, reused environment, or invented facts.

## Proposed cleanup confirmation deadline — Part 4m

- Prototype budget: **5,000 ms elapsed time**, proposed and unverified.
- Start an external monotonic deadline when whole-run termination is confirmed,
  before cleanup work. This is separate from execution and Part 4l stop timers.
- Cover every outcome and abandoned setup; prove no pending launch for never-launched runs.
- Preserve accepted output and trace facts outside resources being removed first;
  any required transfer shares this budget and existing data limits.
- Removal calls, verification, and retries share the remaining time without resets.
  Bound control workers; hung or abandoned calls must not block deadline handling.
- Use a trusted inventory of this run's environment and temporary resources.
  Validate ownership and resolved paths; never follow user-controlled links outside
  the run boundary or delete shared images, another run's files, or retained results.

### Proof, failure, and recovery

Require trusted evidence that every inventoried temporary resource is absent and
no pending setup, launch, or cleanup operation can recreate it or affect another run.
A removal acknowledgement alone is insufficient. Already absent resources count
only when their exact identity and scope are verified; absence of an inventory is no proof.
Serialize accepted proof, deadline expiry, and slot release. Release the slot once
only after termination and cleanup both verify; never reuse an unresolved environment.
Proof accepted before expiry succeeds. At or after expiry, first record cleanup
infrastructure failure outside the v1 trace; never change the actual execution cause.
Retain the occupied slot, resource identities, isolation, and accepted output/trace.
Show failed cleanup explicitly; do not claim deletion or release succeeded.
Stop automatic retries at expiry. Explicit recovery must fence outstanding operations
and verify this same inventory; late proof permits one release without hiding failure.
Termination remains known: cleanup failure alone does not invalidate accepted trace
facts or prevent terminal playback. Capture completeness concerns recorded Java
state/terminal bookkeeping, not host resource deletion; never invent cleanup events.
No Java rerun, source changes, or new execution.status. API error shape remains work.
This bounds waiting, not guaranteed removal; enforcement/recovery evidence remains unverified.

### Required prototype checks (planned, not run)

- Complete/error/cancel/limit/setup paths: remove only owned resources; retain results.
- Stall removal/verification/retries: deadline fires; workers stay bounded; slot stays occupied.
- Race proof before/at/after expiry and repeated recovery: one release, visible failures.
- Wrong identity, links, missing inventory, or late operations cannot delete another run's data.

## Proposed container restrictions and storage — Part 4n

- Use a fresh Linux container per run, including compilation, from a vetted Java 21
  image pinned by digest during setup. Verify the Docker Desktop/WSL2 profile first.
- Run compiler and submitted JVM as UID/GID 10001:10001, without extra groups;
  use --cap-drop=ALL, --security-opt=no-new-privileges=true, and --read-only.
- Retain the pinned runtime's default seccomp profile; never use privileged mode,
  unconfined seccomp, host/shared PID/IPC namespaces, or additional host devices.
- Use --network=none and no published ports. This leaves local loopback only;
  no external/host/other-run access. Console/control transport must not enable networking.
- Expose no host bind mounts, shared volumes, management sockets, or credentials.
  Allowlist environment variables; no host environment inheritance or image secrets.
- Disable automatic restarts, core dumps, and persistent container output logging;
  preserve bounded stdout/stderr through Part 4f's supervisor-owned transport.
- Proposed writable tmpfs caps: /work **64 MiB**, /tmp **16 MiB**, /dev/shm **16 MiB**;
  total **96 MiB (100,663,296 bytes)**, within Part 4b's memory/no-swap budget.
- Cap inodes at **4,096 / 1,024 / 1,024** respectively, including directories;
  enforce before use. Mount nosuid,nodev,noexec; assign only required user permissions.
- Put submitted/generated source and classes in /work; direct JVM temporary files
  to /tmp. Keep the JDK/runtime read-only. Verify Java 21 works with these restrictions.
- Audit every mount and writable path, including /dev: permit no other writable
  regular-file storage or unbounded anonymous volumes. Never rely on rootfs flags alone.

### Verification and failure behavior

Before any submitted compilation/execution, verify effective identity, namespaces,
mounts, quota/inode enforcement, seccomp, network, and existing memory/process limits.
Use trusted configuration/evidence plus profile probes; submitted output is not proof.
Missing or unenforceable controls block launch and expose infrastructure failure,
without a fabricated Java outcome. Clean partial setup under Parts 4l/4m.
Do not silently add privileges, writable mounts, network, or larger quotas to make code run.
Storage denial preserves actual compiler/Java error and caught-error behavior;
ENOSPC text alone is not proof of a limit-caused termination. Verified OOM uses Part 4b.
Preserve only the accepted safe trace; never fill missing records or rerun Java.
Exact launch commands and platform compatibility need prototype proof, not assumed support.

### Required prototype checks (planned, not run)

- Compile/run a small program; verify identity, effective restrictions, and trace/output separation.
- Attempt external/host access, privilege gain, rootfs writes, and cross-run reads; verify denial.
- Fill each writable mount by bytes and inodes; verify caps, caught errors, and no extra storage.
- Remove a required control or fail setup; no submitted launch, bounded output, verified cleanup.

References: [Docker controls](https://docs.docker.com/reference/cli/docker/container/run/),
[tmpfs](https://docs.docker.com/engine/storage/tmpfs/), [inode limits](https://docs.kernel.org/filesystems/tmpfs.html),
[seccomp](https://docs.docker.com/engine/security/seccomp/), [network none](https://docs.docker.com/engine/network/drivers/none/).

## Proposed run capacity and preparation — Part 4o

- Prototype capacity: **1 occupied run slot and 2 queued requests**, unverified.
- One supervisor owns admission for the local runner; multiple API callers share
  these caps. No independent supervisor may bypass unresolved ownership or slots.
- Atomically reserve before preparation; promote the oldest eligible queued request first.
  Queue at most two in acceptance order, without containers/compilers; otherwise respond busy.
- Queue only source already within Part 4h limits. Queue count does not replace
  Part 4p request/decoded-memory bounds or bound all host/application memory.
- Queue wait cap: **30,000 ms**, monotonic from acceptance into the queue.
  At expiry, atomically remove the request and prohibit later promotion/launch.
  Serialize promotion, cancellation, and expiry; repeated activity never resets time.
- Queue expiry/busy responses describe scheduling, outside the terminal trace;
  do not fabricate executed Java. Accepted user cancellation follows Part 4c.
- Occupancy includes preparation, compilation, execution, stopping, and cleanup.
  Release only under Parts 4l/4m; unresolved failures still consume the sole slot.
- After supervisor restart, reconcile existing run ownership before new admission;
  unknown environments block launch. Do not silently reset occupied-slot accounting.

### Preparation deadline and compiler sizing

Start a **10,000 ms** monotonic preparation deadline when the slot is reserved.
It covers container creation, restriction checks, source staging, analysis, and
instrumentation before the first compiler launch; retries share the remaining time.
Require a locally installed pinned image; no per-run image download or dependency install.
Bound outstanding setup calls; stalled work cannot block supervisor deadline handling.
Serialize readiness with expiry/cancellation; launch the first compiler only before
expiry with no stop cause. Then use Part 4d's separate shared compilation deadline.
Preparation expiry latches a stop cause and exposes infrastructure failure outside
the trace. Fence late create/launch work; confirm termination and cleanup under
Parts 4l/4m before release. Never label setup failure compile_error or invent Java events.

Propose **256 MiB (268,435,456 bytes)** compiler heap using javac -J-Xmx256m.
Run compilers sequentially; confirm each compiler/descendant exited before the next JVM.
The 512 MiB container cap includes compiler/native/tracer/tmpfs costs; this heap is
not extra memory. Preserve actual compiler errors and Part 4b's verified OOM rules.
No larger automatic retry. JVM/native headroom and analysis guards need prototype proof.

### Required prototype checks (planned, not run)

- Race submissions: one occupied slot, two queued; no queued container or extra worker.
- Race queue promotion/expiry/cancel at boundaries; preserve order and prevent late launch.
- Stall setup or restart with an unresolved run; deadlines fire and admission stays blocked.
- Verify sequential compiler heap/phase limits, real errors, retained output, and cleanup.

Reference: [Java 21 javac JVM options](https://docs.oracle.com/en/java/javase/21/docs/specs/man/javac.html).

## Proposed transport and decoding bounds — Part 4p

All values below are prototype proposals, not verified memory or transport guarantees.

- Run-submission HTTP limits: **16 KiB headers**, **2 MiB body**, **2 concurrent
  receivers** (headers/body), and **5,000 ms** body receive time from accepted headers, without resets.
  Bound header reception by the same duration from connection acceptance; reject excess
  readers before buffering. Guard actual incoming bytes, independent of Content-Length.
- Accept uncompressed UTF-8 JSON only; reject compressed bodies before expansion.
  Stream-decode escapes and apply Part 4h's separate 256 KiB exact-source cap.
  Cap JSON nesting at **32** and request/record tokens at **65,536**, counting keys,
  scalar values, and container starts/ends. Reject duplicate keys and malformed Unicode.
- Oversized/invalid/slow requests never enqueue or launch. Return at most **1 KiB**
  of fixed error JSON without source echo; HTTP/API shapes remain contract work.

### Trace records and buffers

Use a dedicated ordered runner-to-supervisor channel, bound to one run/source identity;
stdout/stderr are never parsed as trace. Endpoint isolation/authenticity needs prototype proof.
Proposed record prefix: **1-byte kind** (1 initialState, 2 event), then **4-byte unsigned
big-endian UTF-8 JSON payload length**. Accept initialState once before ordered events.
The **256 KiB** Part 4g record cap includes all five prefix bytes; reject the announced
length before allocation. Chunking cannot reset counters; never resynchronize past corruption.
Derive stepEnds under trace-format.md section 10; charge index growth/received framing under Part 4g.
Source and terminal metadata are supervisor-owned envelope fields, not worker records.
Use at most **16 KiB per read buffer** and **256 KiB pending bytes per channel** across
all user-space queue layers; record assembly and copies also consume memory below.
Stream terminal JSON with bounded output buffers, preserving existing source/data/metadata caps.
Slow console/result clients must not block trace capture or duplicate retained data without
charges. Bound each client queue to 256 KiB; disconnect lagging clients without losing facts.

### Decoded memory and failure behavior

Reserve **8 MiB per receiving request** and **64 MiB per active run**, within a shared
**128 MiB supervisor transport/data budget**. Queued source, retained results, and every
client count too; reject new admission/connections when reservations cannot fit.
Protect active-run capacity from other requests; reserve 1 MiB within it for stop/finalization work.
Charge conservative allocated capacities before allocation: byte/character buffers,
parser nodes, collection capacity, state/index tables, copies, and transient encodings.
Transfer ownership charges atomically; no refund while data remains live. Verify charges
against runtime allocation sizes; raw JSON length alone is not decoded-memory accounting.
These are managed-data limits, not total backend/browser/kernel/JVM heap guarantees;
Part 4q bounds analysis/instrumentation separately. No silent eviction of accepted source or facts.
Capture budget exhaustion (bytes/tokens/depth/memory) follows Part 4g TRACE_LIMIT,
with the last safe atomic prefix, confirmed termination, and Parts 4l/4m cleanup.
Malformed/truncated/out-of-order records instead expose infrastructure failure; retain
trusted facts, stop the environment, and never manufacture a terminal execution status.
Drain/validate buffered records before normal completion; limits and corruption cannot hide
behind fast exit. Existing cancellation/stop causes win races; never append fake Java events.

### Required prototype checks (planned, not run)

- Request byte/token/depth/time boundaries, escaped source, compression, and duplicate keys.
- Split headers/records, oversized lengths, truncated EOF, ordering, and forged stdout.
- Allocation amplification, copies, queued/retained data, and slow clients stay within reservations.
- Fast exit, cancellation, and decoder limits preserve one cause, safe prefix, and verified cleanup.

## Proposed analysis and generated-source budgets — Part 4q

- Prototype analysis/instrumentation budget: **5,000 ms**, within Part 4o's remaining
  10,000 ms preparation time. Start before requesting the first analysis-worker launch;
  JVM startup, parsing, resolution, transformations, mappings, and retries all count.
- Use one isolated runner worker at a time with **256 MiB heap**, within the existing
  512 MiB container cap. Confirm worker/descendant exit before any compiler launch.
  No submitted-code evaluation, compilation, or execution inside the Spring Boot JVM.
- Resolve against the submitted source and vetted local JDK/tracer classes only;
  no network resolution, user plugins, annotation processors, or application class initialization.
- Cap simultaneously retained parsed/generated syntax nodes at **65,536** and tree
  depth at **256** (root depth 1). Count new copies; guard before construction/descent.
  A post-parse count alone is insufficient. Parser stacks, caches, symbols, and maps
  obey the worker heap/time caps; native allocations also count against the container cap.
- Cap total generated Java at **2 MiB (2,097,152 UTF-8 bytes)** and source-map data at
  **2 MiB UTF-8 JSON bytes**, across all generated files/maps; exact caps fit.
  Preinstalled immutable tracer classes are excluded; generated helpers are included.
- Count incrementally before growing buffers/writing files. Copies and temporary
  output also consume worker memory and Part 4n storage; splitting files resets no quota.
- Preserve original source text/hash and UTF-16 ranges; generated files never replace
  the submitted source in traces. Validate mappings to that exact source version.
- Publish transformed artifacts atomically only after successful analysis, budget,
  mapping, and coverage checks. Never compile truncated/partially instrumented output.

### Outcomes and fallback

Check capabilities by Java constructs and contexts, never algorithm/source templates.
Known unsupported tracing or a guarded tracing-only size/node/depth limit can select
the original program once under java-support.md section 1, only if execution admission
is already complete, remaining preparation budgets fit, and worker exit is verified.
Discard incomplete transformed artifacts; explain unavailable capture with a bounded
coverage diagnostic and a source range only when known. This does not reject valid Java
solely for missing tracing. All compilation/execution/isolation limits still apply.
No fallback after a latched timeout, worker crash/OOM, uncertain admission, or execution.
Time expiry stops the worker under Parts 4l/4m; no timer reset or larger automatic retry.
Unresolved analysis failures expose infrastructure failure outside the terminal trace;
verified container OOM follows Part 4b. Never invent Java errors, values, or source ranges.
Real javac diagnostics remain authoritative for compilation; transformation defects
must not be reported as errors in the user's original source without verified attribution.
These limits do not prove transformation correctness or establish new Java coverage.

### Required prototype checks (planned, not run)

- Node/depth/byte boundaries guard before growth; copies, helpers, and maps share their caps.
- Deep/malformed source, resolution loops, stalled workers, and OOM cannot escape phase bounds.
- Generated overflow/coverage gaps use original once only after admission; crash/timeout cannot bypass it.
- Preserve exact source/hash/ranges; compare supported transformations for side effects and outcomes.

## Proposed interactive input bounds — Part 4r

- Select interactive mode before launch; it replaces the noninteractive 5,000 ms
  execution timer with **120,000 ms elapsed**, starting before the submitted JVM launch.
  This includes startup, computation, and waiting; input/output/reconnect never resets it.
  Preparation/compilation keep their existing deadlines; values remain unverified proposals.
- A verified blocked stdin read has a **30,000 ms** wait deadline from actual blocking.
  Count until the read actually resumes; message arrival/acknowledgement alone cannot reset it.
  A later blocked read has a new wait deadline but shares the overall execution deadline.
  Never infer waiting from printed prompts. Without reliable wait evidence, retain
  the overall deadline and report unknown waiting state; do not claim verified wait tracking.
- Live input line cap: **4 KiB (4,096 UTF-8 bytes)** including one appended LF byte.
  Cap each uncompressed input JSON message at **32 KiB** across fragments; use Part 4p parsing guards.
  Preserve whitespace; reject embedded CR/LF and malformed Unicode in a single-line message.
  Empty Enter sends LF. Do not trim, tokenize, or convert text on Java's behalf.
- Total accepted input cap: **64 KiB (65,536 bytes)** per run, including delimiters;
  pending unsent/in-flight input cap: **16 KiB (16,384 bytes)**. Exact caps fit.
  Guard before buffering/encoding, including fragmented messages; charge Part 4p memory.
  Pending bytes retire only on confirmed pipe writes; acceptance does not mean consumption.
- Admit whole lines atomically and in order for the exact run/controller identity,
  only during execution before EOF/stop. Reject oversized/over-total lines without
  sending a prefix. Reject pending overflow as backpressure; never reset accepted totals.
- Use bounded sequence/deduplication state: retrying accepted input acknowledges it
  without writing/counting it twice; reject conflicting or out-of-order sequences.
  Never automatically retry an uncertain pipe write.

### EOF, disconnection, and limits

Explicit EOF stops further input admission and closes stdin once after already accepted
input drains; it adds no bytes. Repeated EOF is idempotent. Preserve actual Scanner/Java
behavior for EOF and invalid tokens, including caught errors; EOF itself is not cancellation.
Serialize writes/EOF with termination and cancellation: stop cutoff discards pending
input and forbids later writes. Input rejection alone does not terminate execution.
Loss of the controlling console connection, including slow-client disconnection, requests
cancellation immediately when detected; observer disconnection does not cancel the run.
If this cause wins, use cancelled with execution diagnostic INPUT_DISCONNECTED and reason
"Input controller disconnected", after verified termination. No automatic reconnect/restart.
Undetected connection loss cannot extend wait/overall deadlines; all input paths are bounded.

On verified wait/overall expiry, preserve earlier stop causes; otherwise latch limited,
using execution diagnostic INPUT_WAIT_TIMEOUT / EXECUTION_TIMEOUT and the exceeded duration.
Use Part 4a's stop procedure with the interactive duration, never its fixed 5,000 ms reason.
Retain safe partial/unavailable capture, accepted output, and actual source association;
confirm whole-environment termination and cleanup under Parts 4l/4m before slot release.
Unexpected pipe failure is infrastructure failure; never invent consumed input or Java events.
Expected pipe closure after confirmed termination must not overwrite the known outcome.
Replay sends no input and requests none. Future separate test inputs share total/pending
bounds; their case-management/delivery format and exact Scanner coverage remain V1 work.
These rules do not add input events to trace v1 or define the future API message schema.

### Required prototype checks (planned, not run)

- UTF-8/empty/whitespace/fragmented lines at byte limits; no partial delivery on rejection.
- Prompt without newline, repeated reads, invalid tokens, EOF, and blocked pipe behavior.
- Duplicate input, backpressure, controller/observer loss, and cancel/write/EOF races.
- Wait/overall boundaries and backward replay preserve truthful status, no resend, and cleanup.
