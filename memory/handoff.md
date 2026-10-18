# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-18 (Asia/Saigon)
- Active owner: Claude Code (took over after Codex stopped at its usage limit;
  user confirmed Codex stopped)
- Status: active
- Milestone: Part 6 backend run orchestration; Milestone 0, full isolation and
  V1 remain incomplete

## Approved tasks

1. **Done — 6e.0 handoff-efficiency docs** (commits 90394c4, 0c02798, 32fc652,
   53c5543, plus this closing commit). Checks: links/paths (only the known
   absent references and archived relative links break), whitespace, archive
   byte-identical by SHA-1. Documentation only; no application tests run.
   User approved on 2026-10-18
   ("I approve, and make sure Codex can understand and work with you efficiently")
   after Claude proposed five parts. Scope: (1) snapshot-style handoff and protocol;
   (2) archive memory history, shrink current-state/next-steps, fix stale 6e
   approval text; (3) task-scoped permission rule in AGENTS.md; (4) .gitattributes
   without renormalizing files; (5) record missing referenced documents without
   inventing them. Documentation only; commit on this branch before 6e code.
2. **Paused — Part 6e cancellation, run slots and recovery.** Approved by the user
   before this takeover ("is it enough for AIs work together, please review one
   more time. If it's good, then continue implement part 6e"). Spec:
   [fixture-cancellation-recovery.md](../specs/fixture-cancellation-recovery.md).
   Excludes 6f API, submitted-source execution and tracing.

## Git

- Branch: `feat/part-6e-cancellation-recovery`, fast-forwarded on 2026-10-18 to
  `origin/main` fe68059 (6d PR #80 and handoff docs PR #81 are merged).
  Pushed to origin with the 6e.0 commits only; 6e code is still uncommitted.
- PR: none yet. `gh` is unavailable in both agents' sessions so far; use the manual link
  https://github.com/KhoaKhoa811/code-visualization-2/pull/new/feat/part-6e-cancellation-recovery

## Part 6e checklist

- [x] Shared cancellation signal (`RunCancellation`), CANCELLED cause/outcome
- [x] Supervisor: one slot, two FIFO queued, 30 s expiry (`FixtureRunSupervisor`)
- [x] Known-ID recovery (`DockerControlAdapter.recover`, `FixedFixtureLifecycle.recover`)
- [ ] Live tests pass (one failure, see below)
- [ ] Review concerns resolved (see below)
- [ ] Full clean verify with live Docker; FIXTURE guide/spec/memory; commits; push

## Uncommitted 6e work (Codex, intact; do not discard)

Modified: runner `BoundedCommandRunner`, `CommandRunner`, `DockerControlAdapter`,
`FixedFixtureLifecycle`, `FixtureControl`, `PhaseGate`, `PhaseProcess`, test
`FixedFixtureLifecycleTest`. Untracked: `FixtureRunSupervisor`, `RunCancellation`,
tests `CancellationRecoveryLiveTest`, `FixtureRunSupervisorTest`,
`RunCancellationTest`, and `specs/fixture-cancellation-recovery.md`.

## Verification evidence (working tree above, 2026-10-18, Codex runs)

- Unit: 16 existing tests passed (`.tools/part-6e-tests.log`); 10 new
  cancellation/supervisor/lifecycle tests passed (`.tools/part-6e-new-tests.log`).
- Live (`.tools/part-6e-live.log`, UTF-16): 13 run, **1 failure**:
  `CancellationRecoveryLiveTest.cancelsRealCompilerAndRuntimeAndRemovesTheirContainers:36
  Actual isolated worker must start before cancellation`. It failed on the first
  fixture, COMPILE_TIMEOUT: `docker top ... -eo comm` never showed `javac` within
  5 s. RUNTIME_TIMEOUT case never ran. Recovery live test passed.
- Last full verified baseline: Part 6d clean verify, 36 tests, see
  [backend/FIXTURE_LIFECYCLE.md](../backend/FIXTURE_LIFECYCLE.md).
- Command (backend/, PowerShell):
  `$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12.1'; $env:MAVEN_USER_HOME=Join-Path (Split-Path $PWD) '.tools\maven-home'`
  then `.\mvnw.cmd -B -ntp '-Dmaven.repo.local=../.tools/m2' '-Dcodeviz.docker.live=true' "-Dcodeviz.docker.executable=$dockerPath" clean verify`
  with `$dockerPath=(Get-Command docker.exe -CommandType Application).Source`.

## Open review concerns in 6e code (Claude review, unverified)

1. `FixedFixtureLifecycle` decides "final phase" by `budget.equals(Duration.ofSeconds(5))`;
   fragile coupling to budget values.
2. `transports` is a plain `ArrayList` written by the run thread and read by `recover()`.
3. `RunCancellation.request()` interrupts the run thread; during `prepare` this may
   quarantine the adapter so stop/remove fail and the slot is HELD after a plain cancel.

## Resources

- Docker 29.8.0 running; `docker ps -a` showed 0 containers at takeover. No
  commands or tests running. Local logs/tools in ignored `.tools/`.

## Next action

6e.0 is complete and pushed; the user is reviewing it in conversation. Do not
start 6e work until the user says to continue. When resumed: diagnose the live
failure (start by checking the javac process name and the 5 s observation window
against preparation time), address the review concerns, run full live verify.
