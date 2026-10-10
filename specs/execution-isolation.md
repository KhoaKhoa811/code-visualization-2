# Execution isolation: timeouts, memory, and cancellation

Status: Milestone 0, Parts 4a–4i, 2026-10-09. Proposed rules for review.
Resource-limit and cancellation enforcement are not implemented or verified yet.

Sources: [requirements, section 10](../requirements/PROJECT_REQUIREMENTS.md)
and [trace termination rules](trace-format.md#11-termination-and-safe-capture).

## Proposed timer

- Initial noninteractive prototype limit: **5,000 ms of elapsed time**.
- An external supervisor starts a monotonic timer immediately before requesting
  launch of the submitted Java JVM. JVM startup counts toward this limit.
- Queueing, environment preparation, and compilation precede this timer.
  Part 4d proposes a compiler deadline; queue/preparation bounds remain unfinished.
- The timer is independent of submitted code, stdout, and trace activity.
- Waiting or blocking does not pause this timer. Input arrival does not reset it.
- The 5,000 ms value is a prototype proposal to measure, not a verified V1 default.
  Interactive execution and its separate input-wait budget need a later decision.

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
run in that environment. Confirmation/retry bounds need the runner specification.
Do not manufacture a Java exception, return, scope exit, or mutation on timeout.

## Required prototype checks

- A short program finishes below the limit and retains its real terminal status.
- An infinite loop reaches the deadline; all run processes stop and files clear.
- A cut-off trace record is discarded; earlier valid facts remain replayable.
- A completion/deadline race produces exactly one latched terminal outcome.
- Blocking execution still reaches the deadline; activity never resets the timer.

These are future checks, not test results. Part 4a covers runtime timeouts only.
Traversal, input-wait, and transport/generated-source bounds remain unfinished;
Parts 4b–4i propose memory, cancellation, compilation, process/thread, output,
trace-data, source-admission, and terminal-metadata rules; implementation remains unfinished.

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
  Compiler heap sizing, separate capture/traversal limits, and host-wide capacity
  remain later decisions; never increase this budget silently for one program.

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

Termination/cleanup confirmation and retry deadlines remain unfinished runner
specification work. Cancellation must not permit an unlimited orphaned worker;
this document does not claim that those bounds or enforcement already exist.
API responses, disconnect policy, and input-wait limits remain separate tasks.

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
  Queueing, preparation, and source analysis precede it; their bounds remain
  separate unfinished work. The run-container memory cap still applies.

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
latched stop cause. Confirmation/retry deadlines remain unfinished runner work.

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
  the quota. Concrete buffering/transport settings remain runner work.

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
  instrumentation, or decoded memory. Separate transport/decoder guards remain
  required before implementation; Part 4i proposes separate terminal-metadata limits.

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
- Envelope framing outside JSON, decoded-memory bounds, request errors, and
  generated-source bounds remain separate unfinished work.

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
