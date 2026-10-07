# Execution isolation: runtime timeout and memory

Status: Milestone 0, Parts 4a–4b, 2026-10-07. Proposed rules for review.
Timeout and memory enforcement are not implemented or verified yet.

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
Compilation, process, output, traversal, cancellation, and input-wait bounds
remain unfinished; Part 4b defines the proposed memory budget below.

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
