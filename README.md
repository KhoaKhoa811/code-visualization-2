# Java Visualizer

Write a Java program, run it, and step through what it did, one operation at a time.
At each step you see the source expression being executed, the variables and call
stack, and pictures of the data: arrays, strings, lists, stacks, queues, maps, trees,
heaps, graphs, objects and dynamic-programming tables. Arrows show where each value
came from and what the step did.

Every picture comes from facts recorded while your program ran. The app never
recognises an algorithm by its name or shape and draws a prepared animation; it
shows what your code actually did.

Built as an educational tool for learning algorithms and data structures.
It runs locally on Windows, using Docker to execute programs in isolation.

## What you can do

**Write and run**
- Monaco editor with Java colouring, a "New program" template (imports and `main`
  ready) and 26 example programs: sorting, linked lists, trees, heaps, graphs,
  recursion, backtracking, dynamic programming, sliding windows, streams, exceptions,
  input reading and more.
- Live console: output streams while the program runs, and typed input reaches
  `Scanner` / `BufferedReader`. Or give fixed test input with the run.

**Step through the recording**
- Step forward and back, play at several speeds, or jump with the slider.
  One step is one observable operation, and the expression it executed is highlighted.
- Variables and the call stack, one frame per call (recursion included), with frames
  leaving when they return; a call tree with every call and the value it returned.
- Motion: values fly into cells, comparisons show their verdict, pushed and popped
  values move in and out.
- Step actions, at most three per step so the picture stays readable: value
  arrows, swaps, comparison brackets, rewired links, a variable moving between nodes,
  returned values, push/pop, hash slots, the overwritten value crossed out, and loop
  or branch jumps in the code margin. The Arrows switch turns them off.

**See the data**
- Arrays as cells (or bars and heat colours), with index variables as arrows under
  them and the range between two pointers shaded; grids with row and cell arrows.
- Strings as character boxes; library collections and maps; objects of your own
  classes as boxes with reference arrows (cycles and shared objects included).
- Structures detected from the recorded objects and drawn automatically when the
  evidence is strong: linked lists, binary trees, trees with child lists, tries,
  chained hash tables, heaps, graphs of neighbour lists. Each comes with a
  Strong/Possible/Weak ranking that explains why. Anything ambiguous can be mapped by
  hand (for example an adjacency matrix as a graph, or your own fields as a tree).
- Heap view (array and tree in sync, with the heap boundary and sorted part); graph
  view laid out by levels from a start node, lighting each edge a step uses.
- Dynamic-programming tables: which cells are computed, in what order, what each
  came from and how often it was reused (bottom-up and memoized recursion).
- Sliding windows: the cells currently inside a window, with + and − for the cell
  that entered or left.
- An estimated time complexity for each method, from the code's shape, next to the
  calls and steps the run actually took.

**Arrange the screen**
- Three columns: program state on the left, the pictures in the wider centre, and
  the editor and console on the right. Drag panels to reorder them, drag boxes and
  nodes anywhere, and close any panel you don't need.

## How it works

```
 Browser (React + TypeScript)                Spring Boot backend (127.0.0.1:8080)
 ┌───────────────────────────┐   submit    ┌──────────────────────────────────────┐
 │ editor, playback, views   │ ──────────▶ │ run API: one run at a time + a queue │
 │ rebuilds every state from │ ◀────────── │ live console over WebSocket          │
 │ the recorded trace        │ trace JSON  └───────────────┬──────────────────────┘
 └───────────────────────────┘                             │ docker
                                            ┌──────────────▼──────────────────────┐
                                            │ short-lived Linux container per run │
                                            │ javac → bytecode instrumentation    │
                                            │ (ASM tracer) → run → raw records    │
                                            └─────────────────────────────────────┘
```

1. **Run.** The backend never compiles or runs your code itself. Each run gets a fresh
   container from a pinned Java 21 image: no network, an unprivileged user, a
   read-only filesystem except a small work area, and limits on time, memory,
   processes, output and trace size. Cancelling or a timeout removes the container.
2. **Record.** The tracer instruments the compiled bytecode so each observable
   operation (reads, writes, calls, returns, comparisons, library calls, exceptions)
   is recorded in order. The program's own output and behaviour are unchanged; tests
   compare traced and untraced runs. Code outside the supported subset still runs,
   just without pictures for those steps.
3. **Play back.** The trace follows a versioned JSON contract (`contracts/trace/`).
   The frontend rebuilds the program state at any step, forward or backward, without
   rerunning Java. Every view is a projection of the facts up to the current step,
   so nothing from the future leaks into an earlier picture.

## Running it locally

**Prerequisites (Windows):** Docker Desktop (WSL2, Linux containers),
JDK 21 (developed with Oracle JDK 21.0.12.1), Node.js 22.23.2 with npm 10.9.8.
Microsoft Edge is used for the browser tests.

```powershell
# 1. The pinned runner image (see runner/image.json)
docker pull eclipse-temurin@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87

# 2. Build the tracer and the backend (Maven wrapper; caches stay in the ignored .tools/ folder)
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
$env:MAVEN_USER_HOME = "$PWD\.tools\maven-home"
.\backend\mvnw.cmd -B -ntp '-Dmaven.repo.local=.tools/m2' -DskipTests package

# 3. Start the backend (terminal 1)
& "$env:JAVA_HOME\bin\java.exe" -jar backend/target/backend-0.0.1-SNAPSHOT.jar

# 4. Start the frontend (terminal 2), then open http://127.0.0.1:5173
cd frontend
npm ci
npm run dev -- --host 127.0.0.1 --port 5173
```

Docker Desktop must be running before you press Run. The backend finds `docker` on
`PATH`, or set `codeviz.docker.executable` to its full path.

## Tests

```powershell
# Java: tracer and backend, including live container tests (Docker running)
$dockerPath = (Get-Command docker).Source
.\backend\mvnw.cmd -B -ntp '-Dmaven.repo.local=.tools/m2' '-Dcodeviz.docker.live=true' "-Dcodeviz.docker.executable=$dockerPath" clean verify

# Trace and run contracts
cd contracts; npm ci; npm test; cd ..

# Frontend: unit tests (84), type check and build, browser tests against the real backend (39)
cd frontend
npm test
npm run build
npx playwright test   # starts its own backend and dev server; stop yours first
```

## Repository layout

| Folder | Contents |
| --- | --- |
| `frontend/` | React + TypeScript app: editor, playback, all views ([guide](frontend/README.md)) |
| `backend/` | Spring Boot run API, Docker control, run lifecycle ([guide](backend/README.md), [run API](backend/RUN_API.md)) |
| `runner/tracer/` | ASM bytecode tracer and trace producer ([guide](runner/tracer/README.md)) |
| `runner/` | Isolation probes, pinned image, fixtures ([review](runner/PROTOTYPE_REVIEW.md)) |
| `contracts/` | JSON schemas and checks for runs and traces |
| `requirements/` | [Project requirements](requirements/PROJECT_REQUIREMENTS.md) and confirmed clarifications |
| `specs/` | One specification per feature, plus architecture decisions in `specs/decisions/` |
| `memory/` | Progress notes and handoffs between the agents that built the project |

Good starting points in `specs/`: [V1 scope](specs/v1-scope.md),
[V1 support matrix](specs/v1-support-matrix.md) (which Java is supported),
[trace format](specs/trace-format.md), [execution isolation](specs/execution-isolation.md),
[visualization rules](specs/visualization-rules.md) and
[structure detection](specs/structure-detection.md).

## Status and limits

V1 is complete for local use on Windows: all 36 acceptance cases in the
[V1 scope](specs/v1-scope.md) pass. Later parts (motion, call tree, graphs,
complexity, structure detection, DP tables, step actions, layout, sliding windows)
were added on top.

Known limits:
- **Not for public hosting.** It runs one program at a time for one local user;
  multi-user deployment needs the work listed in the
  [isolation review](specs/isolation-review.md).
- **A subset of Java.** Single-file programs: a `Main` class with static methods and
  nested classes. Primitives, arrays, strings, the common `java.util`
  collections, streams and lambdas, exceptions and input are covered; see the
  [support matrix](specs/v1-support-matrix.md). Unsupported code still runs, without
  pictures.
- Static fields of `Main` are not traced, so a memo table kept in one is not shown.
- An adjacency matrix is drawn as a graph only after you confirm it under
  "Choose fields yourself"; a square number table could just as well be a DP table
  or a grid.
- A structure is drawn automatically only when the evidence is strong. For example, a
  tree appears once it branches or is two levels deep, and a sliding window once its
  first cell leaves.
- Complexity is an estimate from the code's shape, meant for comparing algorithms,
  not a proof.

## How it was built

The project was built in numbered parts by two AI coding agents, Claude Code and
Codex, working one at a time and handing over through the notes in
[`memory/`](memory/), with the project owner approving and merging each part.
Each part came with a specification, tests and its own pull request. The
[history](memory/archive/) records every part.
