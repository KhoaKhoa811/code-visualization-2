# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-23 (Asia/Saigon)
- Owner: Claude Code
- Status: 6g done on `feat/part-6g-isolation-review` (on main 1978853, which has all of 10d).
  Awaiting review; the V1 support matrix awaits the user's agreement.
- Milestone: V1. All 36 acceptance cases Done (AC-20 closed by 6g). Public deployment not approved.

## Task: 6g isolation review + V1 closure (user 2026-10-23: "I merged all the branches into main.
Please complete the rest")

- [x] Spike (no product change): under the exact run profile a JVM blocked in Scanner shows a
      thread with wchan `anon_pipe_read`, fd/0 `pipe:`; /proc/<tid>/syscall denied without ptrace.
- [x] Input-wait limit (Part 4r values): `WorkspaceControl.stdinWaiting`, `DockerControlAdapter.WAIT_PROBE`
      (fixed /proc script, no double quotes: BoundedCommandRunner rejects them), lifecycle monitor
      (1 s poll, unbroken waiting 30 s -> cancellation with cause INPUT_WAIT_TIMEOUT, `limited`),
      console `state.waiting`, page note "Waiting for input". AC-20 Done.
- [x] specs/isolation-review.md: gates 1-6 with evidence; gaps before public deployment
      (auth/quotas/TLS, no `--cpus`, startup sweep of orphaned containers, shared-kernel sandbox and
      /dev/mqueue, single slot and in-memory state, re-verify on the deployment host).
- [x] specs/v1-support-matrix.md: draft for the user's agreement (java-support section 7).
- [ ] user agreement on the matrix; review/merge of 6g

## Git

- Branch `feat/part-6g-isolation-review`.
- PR (manual): https://github.com/KhoaKhoa811/code-visualization-2/compare/main...feat/part-6g-isolation-review?expand=1

## Verification (6g, 2026-10-23, Claude)

- Root `clean verify` live Docker: tracer 136, backend 102 (new: 2 lifecycle unit, 3 live input-wait),
  0 failures (`.tools/part-6g-verify.log`). Frontend 45 unit, build, 17 Playwright passed (`.tools/pw-6g.log`); no containers left.
- Lesson: the spike container `codeviz-spike-6g` was left after a removal was dropped from a script;
  removed by its literal name. Always remove experiment containers by literal name.

## Next action

Ask the user to agree the V1 support matrix (or name changes) and to choose whether to start any
public-deployment item from the isolation review. Do not start them without approval.
