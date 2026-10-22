# Isolation-gap review (Part 6g)

Status: review written 2026-10-23 at the user's request ("please complete the rest"),
after V1 parts 7a–10d were merged. It updates the gate table of the
[Part 5u prototype review](../runner/PROTOTYPE_REVIEW.md) with what later parts built and
tested. It also implements the one missing V1 bound, the input-wait limit of
[Part 4r](execution-isolation.md) (AC-20).

**Conclusion.** For V1 as built, gates 1–6 have tested enforcement:
- one local user;
- backend bound to `127.0.0.1`, WebSocket origins limited to localhost;
- Docker Desktop on Windows.

**Public deployment is not approved.** The gaps listed under "Before any public deployment"
must be closed first. This review does not authorize it.

## Gates, with the evidence now available

| Gate | Status for local V1 | Evidence (parts and tests) | Remaining for public deployment |
| --- | --- | --- | --- |
| 1. Admission and capacity | Enforced, tested | **Source:** exact 256 KiB UTF-8 source admission and a 2 MiB request body limit (`RunControllerTest`, `TracedRunLifecycleTest`). **Queue:** one run slot, at most two FIFO queued runs, queue expiry 30 s, 10-minute bounded retention of 4 finished runs (`RunSupervisorTest`, 7d). **Restart:** recovery of held slots (6e, `CancellationRecoveryLiveTest`). **Ordering:** rejection never launches work. | Per-user identity, quotas and rate limits. Capacity for more than one slot. Restart reconciliation across backend restarts (runs live in memory only). |
| 2. Worker isolation | Enforced, tested | **Probes:** Part 5 probes 5a–5s (filesystem, network, privileges, mounts, storage, heap, cgroup memory, PID limit). **Profile:** a fixed container profile, checked by inspection before use (`ContainerProfile`, `ContainerInspection`, 6c), with uid 10001, no capabilities, `no-new-privileges`, read-only root, `--network=none`, private IPC, 512 MiB memory without swap, 128 tasks, size- and inode-limited tmpfs. **Analysis in the container:** analysis, compilation and execution all run in the run's container; the backend never compiles or runs submitted code (7c). | **No CPU limit:** the profile sets no `--cpus`, so one run can use every core until its deadline. **Writable `/dev/mqueue`:** still writable (5k). **Shared kernel:** isolation is a container on the shared Docker Desktop kernel, with no per-run VM or sandboxed runtime (gVisor, Kata). **Network checks:** representative, not exhaustive (5i). |
| 3. Deadlines and interactive input | Enforced, tested | **Deadlines:** preparation 10 s, compilation 10 s, runtime 5 s (interactive 120 s), queue 30 s (5e–5q, 6d, 7c, `TracedRunLiveTest`). **Input bounds:** 4 KiB line, 64 KiB total, 16 KiB pending; sequence de-duplication, EOF, controller disconnect (`RunInputTest`, `RunApiLiveTest`, 8g). **Provided test input** (10a). **New in 6g:** verified input-wait limit, see below. | The input-wait evidence depends on kernel `wchan` names (`anon_pipe_read`, `pipe_read`, `pipe_wait`). On another kernel it may be unknown; then only the 120 s deadline applies. |
| 4. Output and transport | Enforced, tested | **Output:** shared 1 MiB output quota across compile and runtime with overflow stop (5r, 6d). **Bounded files:** 16 MiB raw-record and 4 MiB plan limits. **Trusted trace:** the trace is built outside the container from records the builder replays and checks. Forged or inconsistent records stop capture; they never reach playback (7b–10d, 136 tracer tests). **Slow clients:** WebSocket messages are capped and slow clients are disconnected (8g). | Authentication of the transport for remote users (TLS, origins beyond localhost). Accounting of backend memory per request beyond the fixed buffers (Part 4p). |
| 5. Capture and replay | Enforced, tested | **Contract:** trace contract 1.10.0, with every trace checked by the shared strict checker (contracts: 65 tests). **Fidelity:** every tracer test compares the original and traced program (output, exit, stack trace). The 10d problem suite runs 51 differently written programs with complete capture and no uncaptured statement (`ProblemSuiteTest`). **Replay:** backward replay from the accepted prefix only (frontend unit tests). | None specific to deployment. Coverage limits are listed in the [V1 support matrix](v1-support-matrix.md). |
| 6. Stop and cleanup | Enforced, tested | **Termination:** stop by killing the whole container, then verifying removal before the slot is released or a trace is built (6d–6e, 7c). **Unknown outcomes:** unconfirmed termination holds the slot and withholds the trace (`TracedRunLifecycleTest`, `FixedFixtureLiveTest`, 5t). **Recovery:** explicit recovery by exact container ID (6e). | **No startup clean-up:** stale `codeviz-run-*` containers survive a killed backend process. This review saw one: a test backend killed by a tool timeout left a container holding the run slot until it was removed by hand. A startup sweep of owned containers is needed. |

## Input-wait limit (implemented in 6g)

Part 4r requires **30,000 ms** of *verified* blocking on a standard-input read before a run
stops. Prompts or the absence of input must never be used as evidence.

- **Evidence.** Verified on 2026-10-23 with Docker Desktop 29.8.0 under the exact run profile:
  - A JVM blocked in `Scanner.nextInt` has a thread whose `/proc/<pid>/task/<tid>/wchan` is
    `anon_pipe_read`, and its `fd/0` is a `pipe:`.
  - Without ptrace rights `/proc/<pid>/syscall` is not readable, but `wchan` and `fd` are,
    for the same uid with all capabilities dropped.
  - After the read returns, the thread is no longer in a pipe read.
- **Probe.** Once a second during a live interactive run, the backend runs one fixed,
  read-only command in the container (`DockerControlAdapter.WAIT_PROBE`). It answers:
  - `waiting`: a thread of a process whose stdin is a pipe is in a pipe read;
  - `running`: readable, not waiting;
  - `unknown`.

  The probe runs with stdin `/dev/null`, so it never counts itself. A failed probe never
  quarantines the container.
- **Limit.** Only an unbroken run of `waiting` answers counts; any other answer starts over.
  - After 30 s the run stops with execution status `limited`, diagnostic
    `INPUT_WAIT_TIMEOUT` and the reason "Input wait exceeded 30000 ms". This uses the same
    termination and clean-up proof as a cancellation.
  - An earlier stop cause wins. The 120 s overall deadline still applies.
  - Accuracy: the count starts at the first observation, so the stop comes within about
    one second after 30 s of real waiting.
- **Console.** The console state message gains `waiting` (`true`, `false` or `null`). The
  page shows "Waiting for input (the run stops after 30 s of waiting)" only while it is `true`.
- **What it cannot prove.** A program could block on another pipe it created itself. That
  only stops its own run early, and only after 30 s. A program that hides its reads is
  still bounded by the 120 s deadline.
- **Tests.**
  - `TracedRunLifecycleTest`: unbroken waiting stops the run; interrupted or unknown
    waiting never does.
  - `TracedRunLiveTest`, live Docker: a blocked Scanner read is seen waiting and stops at
    the limit; input typed within the limit lets the run complete; a program that sleeps
    instead of reading is never stopped.
  - The 8g browser test now expects the waiting note before input is typed.

## Before any public deployment (not approved; each needs its own proposal)

1. User identity, authentication, per-user quotas and rate limits. TLS, and WebSocket origins
   for the real host.
2. A CPU limit in the run profile (for example `--cpus=1`), measured against the deadlines.
3. A startup sweep that removes owned `codeviz-run-*` containers left by a killed backend
   process, before the first slot is offered.
4. A decision on a stronger sandbox than a shared-kernel container (gVisor or a VM per run),
   and closing writable `/dev/mqueue`.
5. Capacity for more than one slot, and persistent run state (or a documented loss on
   restart).
6. A re-check of every Part 5 probe and of the input-wait evidence on the deployment host's
   own kernel and Docker version. Desktop results do not carry over.
