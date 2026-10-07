# Execution isolation: runtime timeout

Status: Milestone 0, Part 4a, 2026-10-07. Proposed rules for review.
Timeout enforcement is not implemented or verified yet.

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

These are future checks, not test results. This part does not specify compilation,
memory, process, output, traversal, cancellation, or input-wait limits.
