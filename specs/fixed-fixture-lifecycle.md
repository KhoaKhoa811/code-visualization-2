# Fixed-fixture lifecycle — Part 6d

Implement one noninteractive, one-shot backend lifecycle using trusted repository
fixtures. This is not source admission, an HTTP endpoint, a trace producer, or a
replacement for the Part 5 probes. Compiler and program JVMs run only inside the
owned restricted container. Fixture selection is a closed enum; callers cannot
supply source, paths, shell commands, image options, or phase budgets.

## Phases and ownership

Create, inspect, start the fixed idle container, and stage a bundled fixture
within one 10,000 ms preparation budget. Stage through a fixed base64 command,
without host mounts. Fixture resources originate in root runner/fixtures and are
included explicitly in the backend artifact. Normal staging emits no program
output; staging/control errors are infrastructure failures.

Compilation gets 10,000 ms; runtime gets a separate 5,000 ms. Start each monotonic
deadline before requesting its first Docker exec launch, including host-process
startup and stream draining. Activity never resets it. One compiler invocation
is used in this slice. Runtime starts only after compilation exits zero, both
streams reach EOF, and no stop cause exists. Original Java remains uninstrumented.

Use a dedicated phase transport so user stdout/stderr never enter the 64 KiB
Docker-control collector. Keep stdin closed, TTY disabled, and stdout/stderr
separate. Clear each exec worker environment with env -i; docker exec does not
inherit the idle process's cleared environment automatically.

## Output and outcomes

Share 1,048,576 raw bytes across both streams and compile/runtime phases. Exact
quota is allowed; the first excess byte retains only the fitting prefix and
latches OUTPUT_LIMIT. Preserve per-stream order, never invent cross-stream order
or decode an incomplete UTF-8 suffix. Use bounded read buffers and retention.
Deadline/overflow/completion decisions serialize on the phase gate. At or after
deadline expiry, timeout wins over newly observed output or completion; an
already latched cause remains unchanged. No records arrive after the cutoff.

A phase exit includes process exit and both stream EOFs. Nonzero Docker exec exit
is reported internally as COMPILE_NONZERO or EXECUTION_NONZERO, not automatically
as a trusted Java error: CLI errors and worker failures need better attribution
before public trace/API integration. No trace-v1 envelope is manufactured here.
Transport failure, timeout, and overflow block runtime launch. Interrupted callers
are treated as infrastructure failure; user cancellation is Part 6e work.

## Stop, cleanup, and bounded workers

Every completed, failed, or limited phase goes through whole-container stop and
owned removal, each with the existing five-second control budget. Do not mistake
terminating the Docker exec CLI for terminating the container. Preserve the
primary phase outcome and accepted output even if stop/cleanup fail; expose
separate infrastructure failures and truthful confirmation flags.

The phase transport has at most three daemon workers (launch/wait plus two readers)
and one phase in flight. On a cutoff, fence late local launches and request CLI
termination. A pending Docker exec targets only the immutable ID: after container
stop/removal it cannot restart or recreate the environment. The control adapter
never restarts an environment after stop. Unknown daemon control outcomes remain
quarantined under Part 6c; explicit recovery and slot release remain Part 6e.

The lifecycle is one-shot. No unbounded task queues, automatic worker replacement,
retry, or reusable failed lifecycle is exposed. A future shared supervisor must
enforce global admission/capacity; this class alone does not establish that gate.
Accepted output uses two bounded byte buffers with a shared logical cap; buffer
growth and defensive result copies are bounded multiples of that cap.

## Required checks

Unit checks cover exact/overflow quotas, shared phases/streams, raw bytes, cutoff
precedence, process EOF before success, launch gating, and failure retention.
Opt-in live cases cover normal output, real compiler rejection, a stalled javac,
an infinite Java loop, stdout/stderr/combined overflow, exact output, multibyte
cutoff, compile output preventing runtime, and cross-phase quota sharing. Verify
whole-container termination/removal for every successful live probe handoff.
These are acceptance requirements until results are recorded in memory.
