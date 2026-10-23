# Current state

Last updated: 2026-10-23 (Asia/Saigon). Short summary only; keep it under about
120 lines. For the active task, [handoff.md](handoff.md) is authoritative.
History: [archive/](archive/README.md).

## Milestone and progress

V1 is functionally complete for Windows-local use: all 36 acceptance cases in
[V1 scope](../specs/v1-scope.md) are Done and the
[V1 support matrix](../specs/v1-support-matrix.md) was agreed on 2026-10-23.
Public multi-user deployment is **not** ready and is on hold by the user; its
gaps are listed in [isolation review](../specs/isolation-review.md). Parts 11
(motion) and 12 (more library calls, grid arrows, frame exit) are post-V1
improvements.

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
| 7d | Minimal run API: submit, poll, cancel, recover, output through one `RunSupervisor` ([guide](../backend/RUN_API.md)); 118 tests with live Docker | Merged (PR #86) |
| 7e | Coverage roadmap ([roadmap](../specs/coverage-roadmap.md)); frontend slice: Monaco, run flow, playback reducer, variable/array views ([guide](../frontend/README.md)); 7 unit + 2 browser tests with live Docker | Merged (PR #87) |
| 8a | Graceful partial coverage: unsupported statements run uncaptured, trace contract 1.1.0 ([spec](../specs/partial-coverage.md)); 126 Java tests with live Docker, 3 browser tests | Merged (PR #88) |
| 8b | All primitive types, casts, bitwise/shift operators, compound assignment, conditional operator, uninitialized declarations; trace contract 1.2.0 ([spec](../specs/primitive-types.md)); 132 Java tests with live Docker, 4 browser tests | Merged (PR #89) |
| 8c | do/while, labeled jumps, switch statements and expressions, for-each over arrays, arrays of every type and arrays of arrays; trace contract 1.3.0 ([spec](../specs/statements.md)); 141 Java tests with live Docker, 5 browser tests | Merged (PR #90) |
| 8d | Static methods of Main and recursion: CALL/RETURN steps, distinct frames, silent helpers under uncaptured code, unwinding, 64-frame limit ([spec](../specs/methods.md)); 145 Java tests with live Docker, 6 browser tests | Merged (PR #91) |
| 8e | Objects of user classes: fields, constructors, instance methods, cycles, object graph view; trace contract 1.4.0 ([spec](../specs/objects.md)); 149 Java tests with live Docker, 7 browser tests | Merged (PR #92) |
| 8f | Strings and library collections as recorded calls replayed on trusted mirrors, collections panel; trace contract 1.5.0 ([spec](../specs/library.md)); 152 Java tests with live Docker, 8 browser tests | Merged (PR #93) |
| 8g | Live console over WebSocket (ADR 0001), bounded ordered input, interactive runs, Scanner reads replayed over delivered input; run request 1.1.0, trace contract 1.6.0 ([spec](../specs/live-console.md)); 161 Java tests with live Docker, 9 browser tests | Merged (PR #94) |
| 8h | Exceptions: throw, try/catch (multi-catch), finally on every path, mid-trace unwinding, exception objects and getMessage; trace contract 1.7.0 ([spec](../specs/exceptions.md)); 170 Java tests with live Docker, 10 browser tests | Merged (PR #95) |
| 8i | Hardening: program-only stack traces, BufferedReader input object (trace contract 1.8.0), editor scrolls each step into view ([live console](../specs/live-console.md)); 171 Java tests with live Docker, 10 browser tests | Merged (PR #96) |
| 9.0, 9a | V1 scope checklist ([v1-scope](../specs/v1-scope.md)); grid of panels and layout-only dragging ([frontend design](../specs/frontend-design.md)); 23 unit, 11 browser tests | Merged |
| 9b | Tree view of user objects via a confirmed value/left/right mapping, tree-sort example ([tree view](../specs/tree-view.md)); 26 unit, 12 browser tests | Merged |
| 9c | Heap view: array confirmed as heap, synchronized strip and tree, recorded boundary, heap-sort example ([heap view](../specs/heap-view.md)); 30 unit, 13 browser tests | Merged |
| 9d | Deque/LinkedList shown as stack or queue by explicit choice; Core V1 parts complete; 31 unit, 13 browser tests | Merged (PRs #97-#100 for 9a-9d) |
| 10a | Separate test inputs: run request 1.2.0 provided input, snapshot 1.1.0 input association ([test inputs](../specs/test-inputs.md)); 175 Java tests with live Docker, 14 browser tests | Done, pushed, awaiting review |
| 10b | Custom mappings: list, array stack, circular queue views of user classes; revisions; revalidation ([custom mappings](../specs/custom-mappings.md)); 35 unit, 15 browser tests | Done, pushed, awaiting review (stacked on 10a) |
| 10c | Evidence-based view rankings rank-1 ([rules](../specs/visualization-rules.md)); 41 unit, 16 browser tests | Merged |
| 10d | Problem suite: for-each over collections and map views (1.9.0), lambdas and streams (1.10.0), 51 suite programs, examples; AC-36 ([problem suite](../specs/problem-suite.md)) | Merged |
| 6g | Isolation review, input-wait limit (AC-20), V1 support matrix agreed | Merged |
| 11a-11d | Step motion: value flights, badges, speed, Motion toggle, index arrows, call/return, collection/object/tree/heap motion ([motion](../specs/motion.md)); 50 unit, 21 browser tests | Merged (PRs #109-#112) |
| 12a-12d | `Arrays.fill`, `Math.pow`, `String.format`, `Stream.of`, iterators used directly (trace 1.11.0, plan 11) ([library](../specs/library.md)); grid index arrows and returning-frame ghost ([motion](../specs/motion.md)); tracer 139 + backend 102 Java tests with live Docker, 66 contract, 53 unit, 24 browser tests | Done, pushed, awaiting review |
| 13a-13c | Call relationships: call tree panel, the line each frame waits on, returned values kept until used ([call tree](../specs/call-tree.md)); frontend only; 55 unit, 25 browser tests | Done, pushed, awaiting review (stacked on 12) |

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
