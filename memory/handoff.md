# Current agent handoff

<!-- Snapshot file: REPLACE the whole content on every update; never append.
     Keep it under ~100 lines. At task end, fold outcomes into current-state.md,
     next-steps.md and archive/history-current.md.
     This file is the authority for the active task (see agent-handoff-protocol.md). -->

## Status

- Updated: 2026-10-18 (Asia/Saigon)
- Active owner: Claude Code
- Status: active, Part 7a
- Milestone: moving from backend orchestration to the trace engine; Milestone 0,
  full isolation and V1 remain incomplete

## Approved task

**Part 7a trace-engine spike.** After Claude's project review, the user approved
on 2026-10-18 ("1. I approve 2. I approve 3. I approve"):
(1) Core V1 / V1.1 tiers, recorded in requirements; (2) engine spike comparing
ASM bytecode instrumentation with source instrumentation, including trying the
ASM dependency; (3) skip 6g, shrink 6f until the first visible slice exists.
Excludes: 7b trace-v1 producer, run API, frontend.

Checklist:
- [ ] 1. Record decisions (requirements tiers, next-steps roadmap, this handoff)
- [ ] 2. ASM bytecode tracer prototype in `runner/tracer-spike/`
- [ ] 3. Acceptance programs AC-01..05, AC-07, AC-10, recursion; differential tests
      (original vs instrumented output) and expected-event checks
- [ ] 4. Minimal source-rewrite comparison on hard cases
- [ ] 5. Spike report, ADR 0004, memory, commit, push

Spike programs are trusted repository fixtures run as separate host JVM
processes (not the Spring JVM). Container execution is 7b work.

## Git

- Branch `feat/part-7a-engine-spike`, stacked on `feat/part-6e-cancellation-recovery`
  5f52d8f (6e PR not merged yet; main is fe68059). Rebase onto main after 6e merges.
- Part 6e PR: manual link
  https://github.com/KhoaKhoa811/code-visualization-2/pull/new/feat/part-6e-cancellation-recovery

## Verification

- None yet for 7a. Last verified baseline: 6e full live verify, 49 tests passed.

## Resources

- No commands running; no owned containers.

## Next action

Commit decision docs, then build the ASM prototype with Maven 3.9.16 at
`.tools/apache-maven-3.9.16/bin/mvn.cmd` and JDK 21.0.12.1.
