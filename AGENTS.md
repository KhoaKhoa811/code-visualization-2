# Project instructions

## Context and source of truth

- Read `requirements/PROJECT_REQUIREMENTS.md` before changing project behavior. Its confirmed clarification decisions take precedence over its proposed choices.
- Inspect existing implementation and applicable directory instructions before editing. Preserve unrelated user changes.
- Read relevant files in `specs/` and concise notes in `memory/` when they exist. Load only the context needed for the task. Missing planned files are unfinished work, not a reason to invent their contents.
- Requirements define product intent; specifications define intended behavior; memory records implemented and verified progress. Memory must not override requirements or specifications.
- Distinguish confirmed requirements, proposed decisions, and verified implementation. Record material architectural decisions with reasons in `specs/decisions/`.

## Product and delivery priorities

- Build an educational Java visualization application using Spring Boot, React, and the confirmed TypeScript frontend. Recorded diagram playback after execution terminates is confirmed; Monaco and source instrumentation remain proposed implementation choices to validate through the prototype.
- Target Windows-local V1 with Docker Desktop, WSL2, and Linux runner containers. Future server deployment and broader Java support must be possible through explicit module boundaries.
- Deliver correct variables and one-dimensional arrays first, including a user-written sorting example. Then add lists, stacks, queues, and maps, followed by binary-search-tree sort and heap sort.
- User-defined methods and recursion are required in V1. Specify frame and return semantics early and implement them before tree/heap sorting.
- One Step click advances one observable operation with synchronized source-expression highlighting and visualization. Multiple clicks may remain on one line.
- Keep work scoped to the active milestone and its acceptance cases. Milestones are increments; do not silently remove later V1 requirements.
- The 2026-10-06 clarification places all discussed product features in V1, including broader array/string/map/set/tree/heap coverage, separate test inputs, structure discovery/mappings, object-graph fallback, grid panels and evidence-based view rankings. Earlier V2 deferrals for these features are superseded. Deliver in approved increments without treating unfinished implementation as optional scope.
- Keep the current milestone in `memory/current-state.md` once that file exists. Until then, the starting scope is Milestone 0: specifications and repository instructions, not application implementation.

## Architecture and extensibility

- Keep `frontend/` responsible for editing, playback state, and renderers; `backend/` for APIs and run orchestration; `runner/` for isolated compilation, execution, and trace production; and `contracts/` for machine-readable API and trace schemas.
- Separate source analysis and instrumentation from execution management, trace production, state reconstruction, and rendering. Use explicit, testable contracts between these responsibilities.
- Build new tracing coverage from supported Java statements/expressions and verified contexts, not algorithm names or exact whole-program templates. Algorithms are acceptance cases. Preserve legacy probes until a separately approved replacement is verified; follow the composable tracing direction in `specs/composable-java-tracing.md`.
- Prefer small composed modules and existing extension points. A new supported structure should normally add an adapter, renderer, and tests; document any necessary tracing or language-support changes.
- Keep runtime facts independent from presentation. Trace events must not contain screen coordinates, colors, animation timing, or React component names.
- Automatically show reliable supported structures; suggest and confirm ambiguous custom mappings. Use bounded graphs of captured objects/references when meaning is unclear, never as a substitute for missing trace facts. Preserve aliases, cycles, nulls and shared identities across synchronized grid views.
- Keep mappings, candidate rankings and layout separate from raw traces. Revalidate mappings against source/type/declaration changes and run identity. Heap projections require confirmed indexing and captured active boundaries; recursion requires distinct recorded frames. Do not infer JDK-private structure layouts.
- Rank suggested views with evidence-based Strong/Possible/Weak and explicit insufficient/incompatible states. Separate capture coverage, representation fit, user confirmation, invariants and algorithm correctness. No invented probabilities or correctness scores; backward playback must restore assessments from the same accepted prefix and rule version.
- Define or update contracts before incompatible producer/consumer changes. Deliberately version incompatible trace changes.
- Avoid speculative abstraction, microservices, brokers, databases, authentication, streaming, and other deferred features without a current requirement.
- Extensibility means preserving useful boundaries and verified behavior; it does not promise arbitrary Java support or eliminate every future refactor.

## Java and trace correctness

- Document exact tracing semantics in `specs/java-support.md` before implementing each visualization feature. Use Java 21 compilation/runtime for ordinary single-file programs. Separate tracing eligibility from execution admission: allow isolated output-only execution for tracing limitations under the approved policy, with clear diagnostics. Stop partial playback before missing facts could invalidate state; never approximate Java behavior or bypass execution restrictions.
- Preserve evaluation order, side effects, short-circuiting, scope, exception behavior, and return values. Never evaluate an expression twice to capture its value.
- Preserve object identity and aliasing. Distinguish variable identities from names and recursive frames from one another. Handle nulls, empty structures, repeated values, and cycles safely.
- Keep traces tied to the exact submitted source version. Preserve ordered events and deterministic forward/backward reconstruction without rerunning Java.
- Specify observable-step boundaries, bookkeeping events, pre/post-operation semantics, numeric serialization, partial traces, and terminal statuses in `specs/trace-format.md`.
- Render algorithm internals only when captured from execution. For heap sort, synchronize the array and tree views, active heap boundary, and sorted suffix. Never infer internal heap layout from priority-queue iteration.
- Runtime visualization must use execution data, not generated guesses.

## Execution isolation

- Never compile or execute submitted Java inside the Spring Boot application JVM. Treat submitted source as untrusted input.
- Use isolated run environments with an unprivileged identity, restricted filesystem access, and no network access for submitted code. Do not expose secrets, host mounts, or container-management sockets to it.
- Enforce bounded compilation/execution time, memory, processes/threads, stdout/stderr, trace size, object traversal, collections, and recursion. Document concrete limits in `specs/execution-isolation.md` and validate them in the runner prototype.
- Separate trace transport from user stdout. Preserve available valid partial traces and distinguish completion, failure, cancellation, and limits.
- Terminate the entire run environment on cancellation or timeout, clean temporary resources, and limit concurrent runs. Keep files and data isolated between runs.
- Docker availability alone does not establish isolation. Verify the enforced restrictions; do not claim public deployment readiness from local execution tests.

## Verification and handoff

- Add meaningful tests for semantic transformations, trace contracts, intermediate replay states, adapters, and runner isolation. Compare original and instrumented supported programs for final values, output, exceptions, and side-effect counts.
- Include browser verification for sorting playback when the frontend exists. Check source edits, backward steps, failures, limits, and cleanup against relevant acceptance cases.
- Verify the requirements' grid/mapping/confidence acceptance cases as those capabilities are implemented: common cursor, shared identities, mapping invalidation, bounded factual graph fallback, ambiguity, ranking evidence and no future-event leakage. Keep required-but-unimplemented features clearly labeled; do not claim V1 completion from narrow prototype gates.
- Run checks appropriate to the change. For documentation-only work, check consistency and paths; do not invent application test results.
- Pin compatible build/runtime versions during setup and document reproducible Windows/PowerShell and runner commands. Until tools and build files exist, do not claim build commands are verified.
- Update concise progress notes after verified work. Report what changed, what was checked, and remaining limitations. Never mark incomplete milestones or unrun tests as complete.
- After each approved task, update both memory files, commit the task changes, push its branch and create a pull request (or update the existing pull request for that same task). The user authorized this delivery workflow on 2026-09-29. Keep build outputs, local tools, credentials and generated test artifacts out of Git. Report the commit/PR and verification results; if remote access or identity is missing, report the blocker without claiming publication. This does not authorize merging pull requests or starting an unapproved task.
- Divide approved work into small, numbered, reviewable parts before implementation and explain their purpose to the user. Commit each coherent part separately with a descriptive message, verify it, and push at meaningful checkpoints so the user can follow progress. Keep intermediate commits buildable and preserve behavior unless a change is explicitly approved. In the PR, list each part, its commit, what changed, verification results, and whether it is complete or pending. Use separate PRs for independently reviewable parts when appropriate; otherwise retain a clear checklist in the task's PR. A single atomic edit may remain one part. This breakdown does not expand the approved scope or authorize the next task.
- Always discuss proposed actions with the user and obtain explicit confirmation before taking action, including using tools, inspecting files, editing files, running commands or tests, installing dependencies, or delegating work. Explain the intended scope before requesting confirmation. Once confirmed, complete only that approved scope; discuss and obtain confirmation before expanding it or starting the next task.
- Use focused agents only when permitted by the session, supported by the environment, and useful for an independent task. Multiple agents are not required. Verify installed Codex configuration compatibility before creating custom-agent definitions; this file does not register agents.
