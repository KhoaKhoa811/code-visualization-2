# Frontend (Part 7e slice)

The first visible end-to-end slice: write Java in a Monaco editor, run it through
the [run API](../backend/RUN_API.md) (isolated Docker container, bytecode
tracer), then step through the recorded execution with synchronized source
highlighting, variables and arrays. Views come from the recorded data shape, never
from algorithm or example recognition ([coverage roadmap](../specs/coverage-roadmap.md)).

## Review pieces

| Part | Change | Verification |
| --- | --- | --- |
| 7e.0 | [Coverage roadmap](../specs/coverage-roadmap.md) and requirements clarification | Paths checked |
| 7e.1 | Pinned Vite/React/TypeScript scaffold; trace-v1 types and playback reducer with checkpoints and consistency checks | 7 reducer tests |
| 7e.2 | Run flow (Axios, TanStack Query polling with revision rule), Monaco editor, playback controls, variable/array/string views, console, statuses | Type check and production build |
| 7e.3 | Playwright browser check against the real backend and Docker | 2 browser tests |
| 7e.4 | This guide and memory | Paths checked |
| 8a | Trace 1.1.0: uncaptured steps, unknown values (`?`), re-read arrays, warning markers, helper-and-library example ([partial coverage](../specs/partial-coverage.md)) | 9 reducer tests, 3 browser tests |
| 8b | Trace 1.2.0: all primitive types, casts, bitwise operators; number-types example ([primitive types](../specs/primitive-types.md)) | 10 reducer tests, 4 browser tests |
| 8c | Trace 1.3.0: `SWITCH` steps, arrays of every type, arrays of arrays drawn as rows with shared rows marked, element paths such as `grid[1]`; grid-and-switch example ([statements and arrays](../specs/statements.md)) | 13 reducer tests, 5 browser tests |
| 8d | Call stack (innermost first, running frame marked), call and return descriptions; recursion example ([methods](../specs/methods.md)) | 15 reducer tests, 6 browser tests |
| 8e | Trace 1.4.0: Objects panel (boxes, reference arrows, cycles), field steps, field paths such as `head.next`; linked list and tree example ([objects](../specs/objects.md)) | 17 reducer tests, 7 browser tests |
| 8f | Trace 1.5.0: Collections panel (list, stack, deque, set, priority queue in poll order, map, StringBuilder), library calls as steps, one-step concatenation; strings and collections example ([library](../specs/library.md)) | 19 reducer tests, 8 browser tests |
| 8g | Trace 1.6.0: live console over WebSocket (streamed output, input line, EOF, controller state), `Scanner` reads as "Read input" steps; read-input example ([live console](../specs/live-console.md)) | 20 reducer tests, 9 browser tests |

## Stack and pins

Node 22.23.2 and npm 10.9.8 (`engines`, `engine-strict`). Exact versions, each
published at least two weeks before 2026-10-18: React and React DOM 19.3.0,
Vite 8.3.2, `@vitejs/plugin-react` 6.1.1, TypeScript 6.0.3, TanStack Query
5.104.1, TanStack Router 1.170.41, Axios 1.20.0, `monaco-editor` 0.57.0,
`@playwright/test` 1.63.0. The lockfile is committed; `.npmrc` saves exact versions.

- TypeScript 6.0.3 rather than 7.x keeps the established compiler line.
- Vitest was dropped: npm 10.9.8 crashed resolving its optional Playwright peer.
  Reducer tests use Node's built-in test runner, which runs TypeScript directly;
  `tsc` does not type-check `*.test.ts` (no `@types/node` installed).
- Monaco loads from the local package (editor core plus Java colouring); no CDN.
  The production bundle is about 3.1 MB; code splitting is later work.
- The Playwright check uses the installed Microsoft Edge (`channel: 'msedge'`);
  no browser is downloaded.

## Run it

Build the backend from the repository root first (see
[RUN_API.md](../backend/RUN_API.md)); Docker Desktop must be running.

```powershell
# Terminal 1: backend on 127.0.0.1:8080 (Docker found on PATH)
& "$env:JAVA_HOME\bin\java.exe" -jar backend/target/backend-0.0.1-SNAPSHOT.jar
# Terminal 2: frontend on http://127.0.0.1:5173 (proxies /api to the backend)
cd frontend; npm ci; npm run dev
```

## Checks

```powershell
cd frontend
npm test            # playback reducer: Node test runner
npm run build       # tsc --noEmit, then vite build
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
npx playwright test # starts the backend jar and Vite, drives Edge
```

On 2026-10-18: 7 reducer tests passed, the build succeeded, and both browser
tests passed with live Docker. They cover bubble sort (complete trace, console
`1..5`, stepping to the first write `a[0]: 5 → 2`, step back restoring the array,
the end state, source edits invalidating playback), a second program, and a
compile error with an editor marker.

## Behavior

- **Run flow:** Run submits the exact editor text with its SHA-256. The snapshot
  is polled every 250 ms until `finished`; older revisions are ignored. Cancel
  stops a queued or running program. Output is fetched after the run finishes.
- **Live console (8g):** each accepted run opens one WebSocket, from the submit
  handler rather than an effect, so React StrictMode cannot open a second
  connection that would take the controller role. Output streams while the run
  executes. For a program that reads `System.in`, Enter sends the line plus LF and
  "Send EOF" closes input; sent lines are echoed. Leaving the page while such a
  run executes cancels it. After the run, the console shows the recorded output
  and the lines that were sent.
- **Playback:** cursor k is the recorded initial state plus events up to
  `stepEnds[k-1]` (trace-format section 10). Checkpoints every 256 steps are
  equivalent caches. Back, restart and the slider never rerun Java.
- **Validation before playback:** version, sequence and step numbering, step
  boundaries, source ranges, fresh identities, references, active frames and
  recorded previous/read values against the reconstructed state. A failure shows
  "Trace rejected" instead of playing. Not yet checked: source hash and integer ranges.
- **End of a complete run:** the final cleanup removes main's frame. The last
  cursor then also shows the recorded values main ended with, labelled as such.
- **Stale source:** editing the text after a run hides the old playback and asks
  for a new run; old events are never mapped onto new text.
- **Statuses:** execution and capture status with reasons, diagnostics (compile
  errors also become editor markers), infrastructure failures and API errors.

## Limits

- Live input covers `Scanner(System.in)`; other ways of reading `System.in` run
  without capture. Input waits are not detected (the console says the program
  reads input, not that it is waiting).
- Views: variables per frame, arrays (read/write/length/allocate highlights),
  strings. Call-stack, object-graph and collection views follow Part 8.
- Not a design-complete UI: no draggable grid, layout persistence or keyboard shortcuts.
