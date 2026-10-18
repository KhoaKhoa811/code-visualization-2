# Current state

Last updated: 2026-10-18 (Asia/Saigon). Short summary only; keep it under about
120 lines. For the active task, [handoff.md](handoff.md) is authoritative.
History: [archive/](archive/README.md).

## Milestone and progress

The project is still in the pre-application phase. Milestone 0 (specifications and
instructions), full isolation verification, Milestone 1 tracing and V1 are **not
complete**. A first-increment tracer, a minimal noninteractive run API and a
first frontend slice exist; there is no live input yet.

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
| 7b | Host trace-v1 producer for the first increment in main ([guide](../runner/tracer/README.md)); 27 tests | Merged (PR #84) |
| 7c | Tracer inside the container lifecycle ([guide](../backend/TRACED_RUNS.md)); root reactor verify 98 tests with live Docker | Merged (PR #85) |
| 7d | Minimal run API: submit, poll, cancel, recover, output through one `RunSupervisor` ([guide](../backend/RUN_API.md)); 118 tests with live Docker | Done, pushed, awaiting review |
| 7e | Coverage roadmap ([roadmap](../specs/coverage-roadmap.md)); frontend slice: Monaco, run flow, playback reducer, variable/array views ([guide](../frontend/README.md)); 7 unit + 2 browser tests with live Docker | Done, pushed, awaiting review |
| 8a | Graceful partial coverage: unsupported statements run uncaptured, trace contract 1.1.0 ([spec](../specs/partial-coverage.md)); 126 Java tests with live Docker, 3 browser tests | Done, pushed, awaiting review |
| 8b | All primitive types, casts, bitwise/shift operators, compound assignment, conditional operator, uninitialized declarations; trace contract 1.2.0 ([spec](../specs/primitive-types.md)); 132 Java tests with live Docker, 4 browser tests | Done, pushed, awaiting review |

## Verified environment

- Workspace `C:\Workspace\code-visualization-2`, Windows 11, PowerShell 5.1 and Git Bash.
- Repository https://github.com/KhoaKhoa811/code-visualization-2, default branch `main`.
- Oracle JDK 21.0.12.1 at `C:\Program Files\Java\jdk-21.0.12.1`; Maven via
  wrapper with caches under ignored `.tools/`.
- Docker Desktop 29.8.0, `desktop-linux` context; pinned runner image in
  [runner/image.json](../runner/image.json).
- Node.js 22 for `contracts/` tests (`npm test`). Frontend: Node 22.23.2, npm 10.9.8; Playwright uses
  installed Microsoft Edge.
- `gh` is not installed in either agent's session so far; PRs are opened manually from pushed branches.
- Build from the repository root pom (tracer, then backend) since Part 7c; see backend/TRACED_RUNS.md.

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
- Results from 6d/6e fixtures are internal probe outcomes; only 7d snapshots are API envelopes.
  Nonzero Docker exec exit is not yet trusted attribution of a Java error.
