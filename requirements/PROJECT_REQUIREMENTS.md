# Java Code Visualization — V1 Requirements

## 1. Purpose

Build an educational website where a user writes Java code and watches its execution through visual representations of variables and data structures. For example, a variable appears as a labeled box, an array appears as indexed boxes, and a user-written sorting algorithm animates comparisons and changes step by step.

The frontend must use TypeScript, React, Vite, TanStack Query, Axios, and TanStack Router. The backend must use Java 21, Spring Boot, and Maven. Execution uses Docker Desktop with WSL2 and Linux runner containers for Windows-local V1. Development will be assisted by Codex in VS Code. The selected frontend baseline is Node.js 22, React/React DOM 19.3, and Vite 8. Exact patch versions and remaining dependencies must be pinned and installation-tested during setup.

The audience includes Java beginners and algorithm students. The primary learning outcome is understanding algorithm behavior by observing synchronized code and diagram changes. V1 runs locally on Windows; server deployment is a future extension. There is no fixed deadline. Establish correct variables and arrays before adding the other structure categories.

**Core principle: write code that can be expanded and updated.** Maintain clear module boundaries, explicit contracts, and testable behavior. Adding a supported data structure should normally require an adapter, renderer, and tests rather than changes throughout the system.

## 2. Requirement status

- **Confirmed:** Java-only V1; TypeScript, React, Vite, TanStack Query, Axios, and TanStack Router frontend; Java, Spring Boot, and Maven backend; Docker execution; code visualization, variables, arrays, strings, lists, stacks, queues, maps, sets, trees, heaps, methods/recursion, and extensibility.
- **Confirmed playback behavior:** recorded diagram playback after execution terminates, using available validated facts.
- **Implementation baseline:** Monaco remains proposed; bounded source instrumentation and JavaParser/SymbolSolver are verified in the prototype. This does not establish general Java tracing or application integration.
- **Needs technical validation:** composable language coverage, broader instrumentation, library operation coverage, and tree/heap representation.

### Confirmed clarification decisions

- Latest scope clarification, 2026-10-06: all discussed product features belong to V1. This includes broader array/string/map/set/tree/heap coverage, separate test inputs, automatic structure discovery, confirmed custom mappings, object-graph fallback, synchronized grid panels and evidence-based suggested-view rankings. Earlier V2 deferrals for these features are superseded. Deliver incrementally; V1 is not complete until the agreed coverage and workflows are verified. Exact supported operations and limits still require specification; this is not arbitrary-Java support or a claim of implementation.

- On 2026-10-04/05, the user clarified that differently written supported Java must not depend on recognizing an exact algorithm template, and merged the [composable tracing review](../specs/composable-java-tracing.md) in PR #11. Build coverage around statements, expressions and verified combinations; use bubble sort, quicksort and other programs as acceptance examples. This does not promise arbitrary Java or immediate quicksort support. Detailed implementation still requires approval.

- The September 22 split between V1 and broader V2 structure coverage is historical. Section 15 now lists that coverage as V1 work, following the latest scope clarification.
- V1 retains Main.java with Main.main for both live Scanner input and a separate test-input workflow. Define input format, case management and delivery semantics before implementation; do not change to a solution-class invocation model or resubmit input during replay.

- On 2026-09-18, the user confirmed ordinary single-file Java authoring with imports, a class, static main, helper methods, and common standard-library calls. Java 21 compilation/runtime determines language behavior. Tracing coverage is separate: valid code beyond that coverage may execute in isolation and show output with a clear visualization limitation. Playback stops before missing facts could make state misleading. Existing execution restrictions and later V1 visualization requirements remain. See [ADR 0002](../specs/decisions/0002-java-execution-and-visualization-coverage.md); this supersedes blanket rejection solely for unsupported tracing.

- The frontend is dark-only for V1: visualization left, Java editor upper right, live console lower right. The user's screenshot is a layout/style reference, not a requirement to reproduce its operation boxes. Show only supported structures from submitted code which exist at the recorded step. Drag arrays as groups; list/tree/heap nodes may move individually without changing logical order or relationships. See `specs/frontend-design.md`.
- Diagrams support dragging existing boxes/nodes to improve readability. Dragging changes presentation coordinates only; values, identities, and connections remain determined by execution. Users cannot draw new program structures or edit program data through diagrams.
- V1 includes live console input through a documented subset of `Scanner` over `System.in`. Output appears during execution; the user types a line and presses Enter to send it to the running program. Diagram playback begins after execution terminates, using the available recorded trace. Replay must not request input again.
- Java 21 is the backend and runner JDK target. The user's earlier reference to "React 22" meant Node.js 22. On 2026-09-15, local `node --version` returned `v22.23.2`; retain it for initial setup. React and React DOM must use matching 19.3 patch versions with Vite 8. These are selected version lines, not an installed or tested dependency set. See the compatibility references in `specs/architecture.md`.
- The frontend/backend stack above was confirmed on 2026-09-15. Axios provides HTTP transport; TanStack Query manages server request state and caching; TanStack Router handles navigation. Keep execution playback state separate from server state. Styling, diagram-rendering tools, testing tools, and exact versions remain undecided. Stack selection does not imply installed dependencies or verified compatibility.
- Starter templates remain acceptable for V1, but they must not define permanent algorithm-specific tracing eligibility. User-defined methods and recursion remain required within documented coverage.
- Each Step click advances one observable operation and highlights its source expression. Several clicks may remain on the same source line.
- Tree support focuses on binary-search-tree sort, including construction and traversal. Heap support focuses on heap sort, including comparisons, swaps, and sift operations, with synchronized array and tree views.
- Use Docker Desktop with the WSL2 backend and Linux runner containers for Windows-local execution. Verify host prerequisites during setup and isolation during the prototype. Docker installation is not evidence that isolation requirements have been met.
- Broader Java analysis and server deployment are future directions. Preserve the analysis/execution, trace, playback, and rendering boundaries so these upgrades can reuse existing modules. Do not promise arbitrary Java support or zero future rewrites.

These decisions supersede conflicting proposed choices below. Exact node templates, supported method signatures, event details, resource limits, and dependency versions remain implementation specifications to validate.

Use the proposed baseline to plan and prototype. Record changes to technical decisions with reasons. Do not silently reduce the full V1 data-structure scope: milestones are implementation increments, not separate definitions of V1.

## 3. User experience

1. The user writes or edits a Java program in Monaco Editor.
2. The user selects Run.
3. The system validates the source and reports syntax errors or unsupported features with source locations.
4. An isolated runner compiles and executes admitted code, recording a bounded trace when tracing is eligible or showing output with an explicit visualization limitation otherwise.
5. The frontend displays the trace as synchronized source highlighting and data-structure visualizations.
6. The user can play, pause, move forward/backward one step, restart playback, and adjust playback speed.
7. Execution errors and resource limits appear as clear terminal run states, with available partial trace data.

Confirmed desktop layout: visualization left, Java editor upper right, and live console lower right, using a dark theme. Provide playback controls; exact placement remains a design detail. Handle empty, loading, running, completed, failed, cancelled, and limited states explicitly. Keep a trace associated with the exact source version that produced it; editing source must not silently remap old events to new lines. See `specs/frontend-design.md` for interactions and the screenshot reference.

During Run, the console displays stdout/stderr incrementally, including prompts without a trailing newline, and accepts line input for supported `Scanner` reads. Show when execution is waiting for input. Cancellation remains available while waiting. Define end-of-input behavior and input-wait limits before implementation. This console is program input/output, not a host shell.

During recorded playback, users may drag diagram elements for readability. Their connected arrows/lines follow the elements. Layout is separate from execution state and trace data; dragging must not alter values, relationships, or the playback cursor. The exact layout persistence policy remains to be specified.

## 4. Execution pipeline

The initial proposed pipeline is:

1. Monaco Editor produces Java source.
2. Backend parsing and semantic analysis validate supported syntax, types, and operations.
3. Instrumentation adds trace recording around supported operations.
4. An isolated worker compiles and runs the instrumented program.
5. The trace engine emits versioned events.
6. A frontend state reducer reconstructs execution state.
7. Structure-specific renderers animate that state.

AST means Abstract Syntax Tree. Tree-sitter builds a concrete syntax tree and does not execute Java or determine runtime values. It is optional for editor-side incremental analysis. Evaluate JavaParser or an equivalent Java-aware tool for backend parsing and symbol resolution; parsing alone does not replace Java compilation or runtime execution.

Source instrumentation is the recommended prototype direction. Alternatives include JDI-based tracing or a custom interpreter for a documented Java subset. Compare alternatives only as needed to resolve prototype findings; avoid building multiple complete engines.

Instrumentation must preserve program semantics: evaluation order, side effects, short-circuit behavior, scope, exceptions, and return values. It must never evaluate an expression twice to capture its value.

Example semantic risk:

```java
values[i++] = calculate();
```

A correct transformation increments `i` once and calls `calculate()` once. If faithful instrumentation is unavailable, reject that transformation and report the tracing limitation. The original program may use the isolated output-only path if execution admission succeeds; never produce misleading visualization or silently rerun after partial execution.

## 5. Java support policy

V1 accepts ordinary single-file Java under the entry convention and execution restrictions. Visualization supports an incrementally verified subset; compiler acceptance does not imply full visualization or unrestricted execution.

The first engine milestone should support:

- A documented single-file entry-point convention, such as `Main.main`.
- Primitive values and strings, with an explicit supported-type matrix.
- Variable declarations and assignments.
- One-dimensional arrays, indexing, reads, and writes.
- Supported arithmetic and comparison expressions.
- `if`/`else`, `for`, and `while`.
- User-written sorting logic over arrays.

Subsequent V1 increments must add the supported collection methods and node operations needed for lists, stacks, queues, maps, trees, and heaps. User-defined methods and recursion are required in V1. Specify arguments, return values, frame lifetimes, recursive calls, and depth limits before implementation; deliver this support before the tree/heap sorting milestone.

Before implementation of each feature, document its exact supported syntax and semantics in `specs/java-support.md`. Define behavior for null values, numeric types, scope exit, aliasing, method frames, exceptions, and unsupported calls. Do not silently approximate unsupported Java behavior.

V1 includes a documented subset of `Scanner` methods for interactive standard input. Specify supported constructors and reads, token/line behavior, invalid input, end-of-input, blocking, encoding, and exceptions in `specs/java-support.md`. Use actual Java semantics; do not emulate reads with different behavior. Exact method coverage remains unresolved.

Initially exclude concurrency, reflection, native calls, external dependencies, file/network access, and arbitrary library internals. These are proposed scope boundaries, not a security mechanism. Mediated standard input is permitted and does not permit submitted code to access the network or host files.

Use JDK 21 for the backend and runner, with Java release target 21. This does not imply support for every Java 21 language feature. Pin the JDK distribution/patch and compatible dependency versions during setup. Document them and include reproducible build commands for Windows/PowerShell and the runner environment.

## 6. Data-structure visualization requirements

| Structure | Required logical visualization | Required behavior |
| --- | --- | --- |
| Variable | Named box containing type/value | Creation, assignment, and scope exit |
| Array | Indexed sequence of boxes | Allocation, access/write highlighting, length, and shared references |
| List | Ordered sequence of elements | Supported reads, additions, replacements, and removals |
| Stack | Vertical elements with top marker | Push, pop, peek, and empty state |
| Queue | Sequence with front/back markers | Enqueue, dequeue, peek, and empty state |
| Map | Key–value entries | Put/update, get, remove, and documented ordering behavior |
| Set | Logical elements without invented ordering | Supported add/remove/contains and empty state |
| String | Recorded text or indexed character view | Supported operations and source highlights |
| Object graph | Captured objects, fields and reference edges | Factual fallback with identity, aliases, nulls, cycles and explicit missing data |
| Call stack | Recorded invocation frames | Arguments, locals, returns and distinct recursive lifetimes |
| Tree | Supported custom tree nodes connected by captured edges | Tree sort and agreed broader tree operations, node/link changes, comparisons and traversal highlights |
| Heap | Synchronized tree and array views | Heap-sort construction, extraction, comparisons, swaps, and sift operations captured from user code |

Detection must rely on resolved supported types and explicit conventions. Do not assume arbitrary objects are recognizable data structures.

- A `Deque` can act as a stack or queue. Support an explicit display mode or documented annotation/convention when intent is ambiguous.
- Define a supported tree node class or mapping convention. Do not assume every class with `left` and `right` fields is a tree.
- For a heap, distinguish logical priority-queue operations from actual internal heap layout. Do not invent a layout from unspecified iteration order.
- Clearly label logical views; do not imply they display Java implementation internals unless those internals are actually captured.
- Render nulls, empty structures, repeated values, and shared references correctly. Handle cycles safely in object traversal.

### 6.1 Automatic discovery and confirmed interpretations

Automatically display reliable supported representations from resolved types, supported operations and actual recorded state. Do not identify an algorithm name to decide how to trace it. Bubble sort, quicksort, heap sort and other algorithms are acceptance programs for composable Java capabilities, not separate template engines.

For ambiguous library usage or custom classes, suggest a compatible view and let the user confirm or correct it. Examples include Deque used as a stack or queue, custom node fields, and an array used as a heap. Confirmation chooses a presentation; it cannot invent runtime facts or prove algorithm correctness.

Support mappings for list value/link/head fields, tree value/child/root fields, custom stack top/storage and queue front/rear/storage conventions, including supported circular layouts. For an array-backed heap, identify the same array object, indexing convention and recorded active boundary; synchronize the array and tree projections and sorted suffix. Do not infer PriorityQueue's internal layout from iteration order.

Reuse mappings while compatible. Revalidate relevant types, fields, declarations and anchors after source changes or a new run; ask again when changes invalidate the mapping. Never reuse runtime object IDs across runs. Old traces retain their own source/mapping association. A mapped boundary must come from captured bindings or an explicitly supported expression over available recorded facts, never executing user code during replay. Exact persistence storage and mapping-expression coverage remain design work.

### 6.2 Factual object-graph fallback

When custom objects are captured but their structure is unclear, show boxes for objects, captured values/fields and arrows for actual references. Preserve object identity, aliases, nulls, cycles and shared children. Missing fields are unknown, not null. Bound traversal and clearly mark omitted data; never unfold cycles into an invented tree or silently discard conflicting edges.

This fallback resolves uncertainty about interpretation, not missing tracing support. If execution facts are unavailable or unsafe, show the coverage limitation and respect the safe playback boundary. Do not call getters, toString, user callbacks or arbitrary reflection to guess values. Supported allocation, field/reference mutation and object lifetime capture must be specified and implemented before enabling this view.

### 6.3 Grid of synchronized views

Within the left visualization area, use a grid of separate panels for the established structures and selected views present at the current step. The Java editor stays upper right and the live console lower right. All panels and source highlighting share one playback cursor; forward/backward/restart never reruns Java.

Show all reliably established relevant structures, not every hypothetical interpretation. Ambiguous objects use a graph plus suggestions until specialized mappings are confirmed. Allow multiple valid views of the same object, especially array and heap, without duplicating logical object identity. Group related objects/scalars by roots or frames rather than requiring a panel per field. Apply bounded rendering with visible truncation/scrolling as needed.

Dragging changes layout only. Rankings must not silently switch the user's selected view while stepping. Exact grid sizing, grouping controls and layout persistence remain UI design choices. Recursion has a call-stack panel based on recorded invocation IDs, arguments, locals and returns; same-named locals in different frames remain distinct while shared objects remain shared.

### 6.4 Suggested-view statistics and confidence

Rank applicable visualizations by evidence of representation fit, using Strong / Possible / Weak initially. Show the evidence, missing facts and contradictions behind each suggestion. If facts are insufficient or incompatible, say so rather than inventing a probability. Do not display uncalibrated percentages such as "92% correct" or an algorithm-correctness score.

Multiple views may fit; allow ties and simultaneous views. User confirmation is separate from measured evidence and does not automatically strengthen confidence. Separate capture coverage, view compatibility, data-structure invariant observations and algorithm correctness. A heap under construction may violate heap order while still being a valid heap projection; a conflicting tree edge must remain visible as a mismatch or in the graph fallback.

Assessment rules must be deterministic and versioned, tied to the selected run/source, mapping and accepted trace prefix. Rewinding restores the same assessment without using later events. Any evidence counts must state what captured subset they count. Define and test exact rules before implementing them; no guessed runtime facts or algorithm detection by an LLM is required.

### 6.5 V1 coverage and implementation status

V1 includes strings, sets, broader array/map/tree/heap problem coverage and priority-queue logical views in addition to the table above. Specify concrete operations/types and representative differently written programs before each implementation increment. General object graphs do not imply support for every possible graph algorithm. Methods and recursion, live input, separate test inputs, mappings, grid views and confidence suggestions are V1 completion requirements, not optional V2 additions.

These requirements describe the target system. The current prototype supports bounded scalar/array/loop/conditional traces only; it does not implement general structure discovery, custom-object capture, mapping controls, rankings or the frontend grid. Preserve verified code while extending its capabilities.

## 7. Meaning of a visualization step

A step represents a documented observable operation, not necessarily one source line.

The Step button advances exactly one observable operation and synchronizes the diagram with the highlighted source expression. Multiple operations on one line require multiple clicks. Internal bookkeeping events must not create unexplained extra clicks; define their mapping to observable steps in the trace specification. Grouped animations must not skip observable operations during manual stepping.

Candidate event kinds include declaration, assignment, array read/write, comparison, supported collection operation, node/link change, scope entry/exit, method entry/exit, exception, and run termination.

For a user-written sorting algorithm, preserve comparisons and writes in execution order. A comparison event records operands and result. A swap may consist of several assignments. A presentation layer may group operations for animation, but must preserve the original event sequence and allow correct stepping.

A call such as `Arrays.sort(values)` does not automatically expose its internal algorithm. Visualize it only through verified logical-operation capture or actual internal tracing. Otherwise report the limitation and allow the original program's isolated output-only path under execution policy. Do not fabricate internal steps or continue playback across uncaptured mutations.

Define whether each event represents state before or after the operation. Recommended baseline: successful mutation events represent committed state after that operation; failed operations produce an exception event without a successful mutation event.

## 8. Trace contract and state model

Create a versioned, machine-readable trace contract under `contracts/`, with semantics documented in `specs/trace-format.md`.

A trace must carry:

- Schema version, run identity, and source version/hash.
- Ordered event sequence and terminal run status.
- Source file and range for user-visible operations.
- Stable object identities and variable identities distinct from display names.
- Scope and call-frame identities where applicable.
- Typed values that distinguish primitives, strings, null, and object references.
- Sufficient state changes to reconstruct every supported playback step.
- Diagnostics and explicit limit/truncation information.

Define a numeric serialization policy that preserves Java values, including `long` values outside JavaScript's safe integer range and supported floating-point special values.

Illustrative event shape; finalize the schema before using it as an API contract:

```json
{
  "schemaVersion": 1,
  "runId": "run-1",
  "step": 12,
  "kind": "ARRAY_WRITE",
  "source": { "file": "Main.java", "line": 8, "column": 5 },
  "frameId": "frame-1",
  "targetId": "object-3",
  "index": 2,
  "previousValue": { "type": "int", "value": 7 },
  "value": { "type": "int", "value": 4 }
}
```

Trace events must not contain screen coordinates, colors, animation durations, or React component names. Keep runtime facts independent from presentation.

Aliasing must work correctly:

```java
int[] a = {1, 2};
int[] b = a;
b[0] = 9;
```

`a` and `b` refer to one array object. Both views must reflect `[9, 2]`.

Use an initial state plus ordered events, with optional periodic snapshots for seeking. Backward playback reconstructs recorded state; it does not execute Java backward. Replaying the same trace must reconstruct the same state.

## 9. Architecture and extensibility

Use one repository with these boundaries:

| Module | Responsibility |
| --- | --- |
| `frontend/` | React/TypeScript editor, playback, state reducer, renderer registry |
| `backend/` | Spring Boot API, validation orchestration, run lifecycle, trace delivery |
| `runner/` | Isolated compilation/execution and trace production |
| `contracts/` | Trace and API schemas plus representative examples |

Recommended extension points:

- Trace engine interface: source/run request to trace result.
- Structure adapters: supported runtime objects to logical structure state/events.
- State reducer: trace events to playback state.
- Renderer registry: logical structure kind to visual component.

Avoid a single large service or React component that handles parsing, execution, state, and rendering. Prefer composition and clear interfaces over speculative abstraction. Do not introduce microservices, a message broker, a database, authentication, or streaming unless a current requirement justifies them.

The first noninteractive tracing prototype may return a bounded completed trace. The end-to-end V1 API must support a run handle, incremental console output, input submission, cancellation, and terminal trace retrieval. Live console communication is required; live diagram-trace playback is deferred. On 2026-09-16, the user confirmed native browser WebSocket and Spring WebSocket handlers for console output/input/EOF and notifications, with Axios/TanStack Query over HTTP for run creation, cancellation, status, and completed results. See [the transport decision](../specs/decisions/0001-live-console-websocket.md). Message contracts, buffering, acknowledgements, and disconnect behavior remain to be specified and verified; no broker or durable job system is introduced.

## 10. Execution isolation and resource limits

Submitted source is untrusted input. Never compile or execute it inside the Spring Boot application JVM.

- Compile and execute within a dedicated isolated worker environment.
- Use an unprivileged identity and restrict filesystem and network access.
- Do not expose application secrets, host mounts, or container-management sockets to submitted code.
- Enforce compilation/execution timeout, memory, process/thread, output, and trace-size limits.
- Bound object traversal depth, collection size, and total serialized data.
- Terminate the entire run environment on timeout/cancellation and clean temporary resources.
- Return explicit reasons when a limit is reached; never present a truncated run as complete.
- Keep tracing data separate from user stdout so printed text cannot masquerade as trace events.
- Limit concurrent runs and isolate one run's files/data from another.
- Bound input line size, total input bytes, pending input, and input-wait duration. Define how waiting affects execution timeouts while retaining an overall run bound. Handle disconnects, EOF, cancellation, and writes to terminated processes explicitly. Interactive input must never leave an unlimited orphaned worker.

Select and document concrete limits during the runner prototype. Source validation and a separate JVM process alone are not sufficient isolation for public deployment. A local prototype must not be presented as ready for public untrusted execution before isolation has been verified.

## 11. Codex repository organization

Use the following path map when scaffolding the repository:

| Path | Purpose |
| --- | --- |
| `requirements/PROJECT_REQUIREMENTS.md` | This document; original project intent and baseline |
| `AGENTS.md` | Shared project instructions and context-loading rules |
| `.codex/agents/architect.toml` | Architecture and contract review role |
| `.codex/agents/trace-engineer.toml` | Java semantics, instrumentation, and runner role |
| `.codex/agents/frontend-engineer.toml` | Editor, playback, and rendering role |
| `.codex/agents/reviewer.toml` | Correctness, isolation, and regression review role |
| `specs/v1-scope.md` | Complete V1 boundaries and milestone mapping |
| `specs/java-support.md` | Supported syntax, types, and library methods |
| `specs/trace-format.md` | Event semantics and compatibility policy |
| `specs/visualization-rules.md` | Structure detection, display modes, animations |
| `specs/execution-isolation.md` | Isolation design and enforced limits |
| `specs/acceptance-cases.md` | Programs and expected outcomes |
| `specs/decisions/` | Accepted architectural decisions and rationale |
| `memory/current-state.md` | Actual implemented and verified state |
| `memory/next-steps.md` | Immediate prioritized work |
| `memory/known-issues.md` | Verified limitations and unresolved defects |
| `backend/AGENTS.md` | Backend-specific conventions |
| `frontend/AGENTS.md` | Frontend-specific conventions |
| `runner/AGENTS.md` | Execution and tracing invariants |
| `contracts/` | Machine-readable contracts |

`AGENTS.md` contains persistent instructions. Custom agents define focused responsibilities; an arbitrary directory of Markdown personas does not automatically register Codex agents. Verify custom-agent configuration against the installed Codex version before generating configuration. If unsupported, use documented role instructions without claiming automatic agent registration.

Specs describe intended behavior. Memory describes actual progress and must not override specs. Keep memory concise and move accepted architectural decisions into `specs/decisions/`. Do not claim incomplete work is finished. Optional reusable skills may be added later when repeatable workflows emerge.

## 12. Instructions for Codex development

When creating the actual `AGENTS.md`, incorporate these rules:

1. Read this document, relevant specs, and concise current-state notes before changing code.
2. Inspect existing repository instructions and implementation first. Preserve unrelated user changes.
3. Maintain Spring Boot and React as the required stack.
4. Keep changes scoped to the active milestone; do not silently drop later V1 requirements.
5. Preserve Java semantics and object identity. Distinguish invalid Java, execution-policy restrictions, and unsupported tracing. Allow admitted output-only execution for tracing limitations and report visualization completeness honestly.
6. Keep execution traces independent from UI rendering.
7. Keep submitted code isolated from the backend application.
8. Define or update contracts before implementing incompatible producer/consumer changes.
9. Add meaningful tests for semantic transformations, trace replay, adapters, and isolation limits.
10. Record material decisions and update memory after verified progress.
11. Report what changed, what was tested, and remaining limitations.
12. Do not claim tests passed unless they were actually run successfully.
13. Use focused agents only when the environment supports them and the task benefits from delegation; multiple agents are not required for every change.
14. Prefer maintainable, working increments over generating the entire application in one pass.

## 13. Implementation milestones

### Milestone 0 — Repository and specifications

Create the instruction/spec/memory structure. Record the Java support matrix, candidate trace schema, architecture decisions, and acceptance cases. Verify agent configuration compatibility. Document build/runtime versions without generating unnecessary application features.

### Milestone 1 — Trace feasibility prototype

Prove scalar declarations/assignments, arrays, conditions, loops, comparisons, and a user-written sorting algorithm. Check evaluation order and aliasing. Establish a real compilation/execution boundary and resource limits. Use findings to confirm or revise source instrumentation before expanding the engine.

### Milestone 2 — End-to-end editor and playback

Integrate Monaco, Spring Boot run API, worker, recorded trace, state reducer, and variable/array renderers. Deliver synchronized source highlighting, playback controls, console/errors, limit handling and the grid foundations for simultaneous views.

Include live console output and supported `Scanner` input, a separate test-input workflow, waiting/cancellation/EOF handling, and draggable diagram layouts. Prove an interactive worker round trip before UI integration; diagram playback still starts only after termination.

Verify variables and arrays before expanding to other structures. Then add and verify supported user-defined methods and recursion, including call-frame playback, before Milestone 4.

### Milestone 3 — Collection visualization

Add documented list, stack, queue, map and set adapters, broader string/array operations, renderers and acceptance cases. Deliver grid panels, supported custom-object graph fallback, confirmed mappings and evidence-based suggestions as their capture capabilities become available. Support explicit stack/queue view selection where needed.

### Milestone 4 — Tree and heap visualization

Implement binary-search-tree sort through supported node-field mappings and heap sort through a confirmed array-backed heap convention. Show tree construction/traversal and heap comparisons, writes and sift operations from executed code. Synchronize heap array/tree views, active boundary and sorted suffix. Extend to the agreed broader tree/heap problem and logical priority-queue coverage within V1; specify exact operations rather than assuming arbitrary library internals.

### Milestone 5 — V1 completion

Verify the full V1 support matrix, cross-module tests, failure states, resource cleanup and development instructions. Include all confirmed structure families, methods/recursion, live and separate test inputs, grid views, custom mappings, graph fallback and evidence-based rankings. Milestones are delivery increments, not reasons to defer these requirements to V2. Exact operation coverage and acceptance cases must be agreed and verified before declaring completion.

## 14. Acceptance cases

| ID | Case | Expected result |
| --- | --- | --- |
| AC-01 | `int x = 5; x = 8;` | A named value box shows 5, then 8 |
| AC-02 | `int[] a = {3, 1, 2}; a[1] = 9;` | Three indexed boxes; only index 1 changes |
| AC-03 | Array loop doubles each element | Trace shows each supported read/write in order; final state is correct |
| AC-04 | User-written bubble sort on `{3, 1, 2}` | Comparisons and writes are visible; final state is `{1, 2, 3}` |
| AC-05 | Two variables reference the same array | One shared object identity; mutation is visible through both references |
| AC-06 | Same variable name in different supported scopes/frames | Distinct identities and correct lifetimes |
| AC-07 | Supported expression with side effects | Same result and side-effect count as the uninstrumented program |
| AC-08 | Forward/backward/restart playback | Exact state restored without rerunning user code |
| AC-09 | Compilation error, execution-policy restriction, or tracing limitation | Distinct diagnostic with source location when available; only tracing limitations permit admitted output-only execution; no fabricated successful trace |
| AC-10 | Array out-of-bounds or other supported runtime error | Correct partial trace and error location; no false successful write |
| AC-11 | Infinite loop/excessive output/oversized trace | Enforced limit, explicit terminal status, worker cleanup |
| AC-12 | Supported list/stack/queue/map operations | Correct contents, markers, operation ordering, and empty states |
| AC-13 | Binary-search-tree sort with confirmed supported node mapping | Correct construction, edges, traversal highlights, and sorted output without duplicating shared nodes |
| AC-14 | User-written heap sort | Recorded comparisons, swaps, and sift operations; synchronized array/tree views, active heap boundary, and correct sorted output |
| AC-15 | Edit source after execution | Old trace remains tied to original source or is clearly invalidated |
| AC-16 | Supported method calls and recursion | Correct arguments, return values, distinct frames, scope lifetimes, and backward playback across calls; explicit depth-limit outcome |
| AC-17 | Multiple observable operations on one source line | Each Step click advances one operation with matching source-expression highlighting and diagram state |
| AC-18 | Drag an existing diagram node during playback | Only position changes; connected lines follow; values, identities, references, and cursor remain unchanged |
| AC-19 | Print a prompt, read an integer through supported `Scanner`, print twice its value | Prompt appears before input even without newline; Enter sends input; execution resumes and output is correct; recorded diagram playback follows termination |
| AC-20 | Wait for input, then cancel, disconnect, reach a wait limit, or send EOF | Documented outcome, available partial trace, bounded resource usage, and worker cleanup; EOF and invalid tokens follow supported Java semantics |
| AC-21 | Replay a run that consumed console input | Forward/backward steps restore input-dependent state without sending input to a worker or asking for input again |
| AC-22 | Program uses only certain supported structures | Only structures from that program which exist at the playback position appear; no unrelated demonstration structures |
| AC-23 | Drag arrays and individual list/tree/heap nodes | Arrays move as groups; individual nodes move visually without changing logical order, values, relationships, or heap index mapping |
| AC-24 | Valid admitted single-file Java exceeds tracing coverage | Execute the original program once in isolation; show output and a clear visualization-unavailable diagnostic; distinguish execution success from visualization completeness |
| AC-25 | Missing recording could invalidate later diagram state | Stop playback at the last safe recorded boundary, explain incomplete capture, and do not guess later values, automatically rerun the program, or resubmit input |
| AC-26 | Program contains several captured structures and recursive frames | Relevant grid panels follow the same cursor/source highlight; aliases share identities and frame-local bindings stay distinct |
| AC-27 | Custom nodes use nonstandard field names | Suggest and confirm field/root mappings; recorded link changes update the chosen view without requiring an algorithm template |
| AC-28 | Structure meaning is unclear but objects/references are captured | Factual bounded object graph shows values, nulls, aliases and cycles; no fabricated specialized structure |
| AC-29 | Capture is missing or traversal is truncated | Explain unavailable/partial data and safe boundaries; graph fallback does not invent unknown facts |
| AC-30 | Confirm a mapping, then rename/remove a field or change its type | Revalidate and require correction when incompatible; old traces remain tied to their source/mapping revision |
| AC-31 | Array is also used as a heap | Both panels reference the same array; use confirmed indexing and captured boundary; no guessed sorted suffix or PriorityQueue layout |
| AC-32 | Several candidate views fit | Evidence-based Strong/Possible/Weak ranking with ties/reasons; explicit unknown/incompatible states; confirmation does not certify correctness |
| AC-33 | Replay backward while suggestions or invariants change | Same facts/mapping/rule version restore the same assessment; no future-event leakage or automatic renderer switching |
| AC-34 | Heap order temporarily fails, or a tree projection has conflicting links | Preserve actual state; distinguish invariant observations from view fit and algorithm correctness; never hide real edges to repair the diagram |
| AC-35 | Provide test inputs separately from Main.java | Associate each input/run/source and result correctly; preserve actual Java input semantics and replay without resubmission |
| AC-36 | Broader string/set/array/map/tree/heap problems, including differently written sorts | Agreed operations work through shared Java capabilities and adapters; no exact algorithm-template dependency; library internals remain factual |

For instrumentation tests, compare original and instrumented execution for supported programs, including final values, output, exception behavior, and side-effect counts. Test the reducer against expected intermediate states, not only final state. Include a browser-level test of the sorting example and meaningful worker-limit/isolation checks.

## 15. V1 coverage and remaining deferred features

### Previously deferred coverage now required in V1

The latest user instruction supersedes the former V2 roadmap for all discussed product features. V1 retains ordinary Main.java/Main.main authoring and includes these capabilities, within explicit, verified operation coverage:

| V1 area | Required scope to specify and verify |
| --- | --- |
| Arrays | Broader array-problem coverage beyond the first one-dimensional prototype; agree exact representations/types and matrix cases |
| Strings | Supported string-problem operations and suitable character/string views |
| Maps and sets | Broader map operations and set visualization, including an agreed Set/HashSet subset |
| Trees | General tree-problem coverage beyond tree sort, with custom mappings and documented node operations |
| Heaps | Heap-sort projections and broader heap/priority-queue problem coverage; logical library views never invent internal layout |
| Methods and recursion | Recorded calls, arguments, returns, frames, shared references and bounded recursion |
| Input workflows | Live Scanner input and separate test inputs while retaining Main.java/Main.main |
| Structure interpretation | Reliable automatic discovery, confirmed custom mappings, factual object graphs and synchronized grid panels |
| Suggested-view statistics | Evidence-based confidence, reasons, ties and missing-fact states, separate from algorithm correctness |

Specify exact library methods, algorithm examples, types, bounds and acceptance cases before implementation. Supporting a family does not promise every Java construct or internal library representation. Missing implementations remain V1 work; do not relabel them V2 to claim completion.

Preserve analysis, instrumentation, isolated execution/input handling, trace reconstruction, interpretation and rendering boundaries. Algorithms remain acceptance cases rather than specialized engines. Preserve existing verified behavior, deliberately version incompatible contracts and keep input handling separate from transformation. Extensibility permits necessary verified refactors.

### Other deferred features

Multiple programming languages, AI-generated explanations, collaboration, authentication/accounts, saved cloud projects, algorithm complexity analysis, arbitrary third-party libraries, and public deployment are not required for the initial implementation. Future additions must reuse or deliberately version the established boundaries.

AI may assist development, but runtime visualization must be driven by verified execution data rather than an LLM guessing what the program does.

## 16. Historical initial setup prompt

The prompt below records repository initialization. It is not the current task or permission to restart Milestone 0. Resume from [current-state.md](../memory/current-state.md) and the user's approved scope.

```text
Read requirements/PROJECT_REQUIREMENTS.md and inspect this repository and any existing
AGENTS.md instructions. Start with Milestone 0 only: create the project
AGENTS.md, compatible focused agent definitions, specs, and concise memory
files described in the requirements. Distinguish confirmed requirements
from proposed technical decisions, and record unresolved choices.

Preserve existing files and unrelated changes. Do not implement the full
application yet. Plan the smallest Milestone 1 prototype that can validate
Java tracing correctness. Make extensibility, execution isolation, and
trace-contract consistency explicit in the instructions. Report the files
created and the next concrete implementation step.
```

## 17. Reference documentation

These references informed the architecture discussion. Recheck version-dependent configuration during implementation.

- [Codex AGENTS.md guidance](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Codex custom agents](https://learn.chatgpt.com/docs/agent-configuration/subagents)
- [Monaco Editor](https://github.com/microsoft/monaco-editor)
- [Tree-sitter](https://tree-sitter.github.io/tree-sitter/)
- [JavaParser](https://javaparser.org/)
- [Java Debug Interface](https://docs.oracle.com/en/java/javase/22/docs/api/jdk.jdi/module-summary.html)
- [Java PriorityQueue contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/PriorityQueue.html)
- [Docker Desktop WSL2 backend](https://docs.docker.com/desktop/features/wsl/)
