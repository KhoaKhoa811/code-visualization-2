# Current state

Last updated: 2026-10-18 (Asia/Saigon). Short summary only; keep it under about
120 lines. For the active task, [handoff.md](handoff.md) is authoritative.
History: [archive/](archive/README.md).

## Milestone and progress

The project is still in the pre-application phase. Milestone 0 (specifications and
instructions), full isolation verification, Milestone 1 tracing and V1 are **not
complete**. There is no frontend, Java instrumentation/tracing engine, run API,
or live input yet.

| Part | What it delivered | Status |
| --- | --- | --- |
| 1-3 | Conversation memory, requirements clarifications, observable-step and Java-support specs, trace contract v1 (`contracts/trace/`) with validation tests | Merged (PRs #1-#8) |
| 4a-4u | Execution-isolation specification: timeouts, memory, cancellation, process/thread, output, trace, source, analysis, input, traversal limits, termination/cleanup confirmation, run capacity ([execution-isolation.md](../specs/execution-isolation.md)) | Merged |
| 5a-5u | PowerShell runner probes proving the restricted Docker profile, limits, output quota, run isolation, stop failures; gate `runner/test-prototype.ps1` ([review](../runner/PROTOTYPE_REVIEW.md)) | Merged |
| 6a | Java 21 / Spring Boot 4.1.1 / Maven 3.9.16 backend ([setup](../backend/README.md)) | Merged |
| 6b | Run request/snapshot contracts v1 (`contracts/run/`, [spec](../specs/run-lifecycle.md)) | Merged |
| 6c | Bounded Docker control adapter, owned container identity ([guide](../backend/DOCKER_CONTROL.md)) | Merged |
| 6d | Fixed-fixture compile/run lifecycle, deadlines, shared 1 MiB output quota ([guide](../backend/FIXTURE_LIFECYCLE.md)); 36 tests passed with live Docker | Merged (PR #80) |
| 6e.0 | Handoff-efficiency docs for Codex/Claude cooperation | Merged (PR #82) |
| 6e | Cancellation, one run slot + queue, explicit recovery ([guide](../backend/CANCELLATION_RECOVERY.md)); 49 tests passed with live Docker | Merged (PR #82) |
| 7a | Trace-engine spike: ASM bytecode tracer, 20 tests, report and ADR 0004 ([report](../runner/tracer-spike/SPIKE_REPORT.md)) | Merged (PR #83) |
| 7b | Host trace-v1 producer for the first increment in main ([guide](../runner/tracer/README.md)); 27 tests | Done, pushed, awaiting review |

## Verified environment

- Workspace `C:\Workspace\code-visualization-2`, Windows 11, PowerShell 5.1 and Git Bash.
- Repository https://github.com/KhoaKhoa811/code-visualization-2, default branch `main`.
- Oracle JDK 21.0.12.1 at `C:\Program Files\Java\jdk-21.0.12.1`; Maven via
  wrapper with caches under ignored `.tools/`.
- Docker Desktop 29.8.0, `desktop-linux` context; pinned runner image in
  [runner/image.json](../runner/image.json).
- Node.js 22 for `contracts/` tests (`npm test`).
- `gh` is not installed in either agent's session so far; PRs are opened manually from pushed branches.

## Standing decisions and user preferences

- Sequential Codex / Claude Code ownership, one active agent, per
  [agent-handoff-protocol.md](agent-handoff-protocol.md) (2026-10-18).
- Task-scoped permissions inside an approved task (2026-10-18); see AGENTS.md.
- No specialized sub-agent configuration for now (2026-10-17).
- Each major part starts with its full roadmap; each small part with its own
  checklist (2026-10-14). Branch names `<type>/part-<id>-<purpose>`.
- After each approved task: update memory, commit, push, open/update PR; never
  merge automatically (2026-09-29).
- Product goal: visualize any user-written Java as correctly as possible from
  captured execution facts, never from algorithm templates (2026-10-06).
- All discussed features are V1 scope (2026-10-06), delivered as Core V1 then V1.1 (2026-10-18); see requirements.
- Fixed-fixture backend code lives in `backend/.../runner`; root `runner/` holds
  probes and fixtures. No Dockerfile yet; the pinned image is used directly.

## Known gaps

- Several documents referenced by the requirements do not exist yet; see
  "Known absent references" in [AGENTS.md](../AGENTS.md). The requirements'
  claim that JavaParser instrumentation was verified in a prototype has no
  artifact in this repository.
- Results from 6d/6e are internal probe outcomes, not public trace/API envelopes.
  Nonzero Docker exec exit is not yet trusted attribution of a Java error.
