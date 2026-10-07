# Execution isolation: runtime timeout, memory, and cancellation

Status: Milestone 0, Parts 4a–4c, 2026-10-07. Proposed rules for review.
Timeout, memory, and cancellation enforcement are not implemented or verified yet.

Sources: [requirements, section 10](../requirements/PROJECT_REQUIREMENTS.md)
and [trace termination rules](trace-format.md#11-termination-and-safe-capture).

## Proposed timer

- Initial noninteractive prototype limit: **5,000 ms of elapsed time**.
- An external supervisor starts a monotonic timer immediately before requesting
  launch of the submitted Java JVM. JVM startup counts toward this limit.
- Queueing, environment preparation, and compilation precede this timer; their
  own bounds remain unfinished work, not an unlimited-execution policy.
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
Compilation, process, output, traversal, and input-wait bounds remain unfinished;
Parts 4b and 4c define the proposed memory and cancellation rules below.

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
