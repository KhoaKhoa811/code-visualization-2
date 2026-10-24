# History from 2026-10-18

Append-only. One entry per finished task or material user decision. Summaries,
not verbatim transcripts, unless quoted.

## 2026-10-18 — Claude Code takeover and handoff-efficiency task (6e.0)

- Codex stopped at its usage limit during Part 6e. The user confirmed it stopped
  and said Claude Code and Codex will work sequentially from now on.
- Claude recovered from the handoff and Git. The branch was fast-forwarded to
  merged main fe68059 (PRs #80 and #81). Codex's uncommitted 6e work was kept.
- Claude's review found the instructions safe but slow for alternating work:
  large memory files, stale "6e unapproved" text despite approval in handoff.md,
  an appended handoff with stale lines, an ambiguous per-action confirmation
  rule, LF/CRLF noise, and missing referenced documents.
- User approved Claude's five-part fix: "I approve, and make sure Codex can
  understand and work with you efficiently". This also approved both recommended
  options: task-scoped permissions, and committing 6e.0 on the 6e branch first.
- 6e.0 delivered on `feat/part-6e-cancellation-recovery`: 90394c4 snapshot
  handoff and protocol; 0c02798 archive (byte-identical) and short memory
  files (~260 KB to ~22 KB); 32fc652 task-scoped permissions and absent
  references in AGENTS.md; 53c5543 `.gitattributes` (index already all LF, no
  renormalization). Documentation checks only. Part 6e code remains paused and
  uncommitted, waiting for the user to resume it.

## 2026-10-18 — Part 6e completed (Claude Code)

- User: "complete the part 6e to this branch first, then I'll merged and we will
  discuss this project".
- Fixed Codex's failing live test (`docker top -eo comm` needs a PID column;
  window raised to 15 s). Removed run-thread interrupt from cancellation because
  it quarantined the container and could hold the only slot forever; spec updated.
  Made runtime finality explicit, transports thread-safe, recovery runner
  injectable with new wrong-owner/absent/unknown-create unit tests.
- Commits 4649fa3 (6e.1 cancellation/lifecycle/recovery, builds alone, 33 unit
  tests) and 94bb1ac (6e.2 supervisor). Full live clean verify: 49 tests passed,
  0 owned containers left. Guide backend/CANCELLATION_RECOVERY.md.

## 2026-10-18 — Project review and Part 7a spike (Claude Code)

- Claude reviewed the project: product feasible, but the tracing engine was
  missing while effort went into isolation; recommended engine spike, Core V1 /
  V1.1 tiers, thin end-to-end slice. User approved all three recommendations.
- 9289e6c records tiers in requirements and the Part 7 roadmap.
- ddebca4 ASM bytecode tracer spike; later commit adds the AC-07 source-rewrite
  comparison, SPIKE_REPORT.md and ADR 0004 (Proposed). 20 tests passed: identical
  behavior for 9 programs; AC-01..05, AC-07, AC-10, recursion facts correct;
  naive source rewrite changes behavior. Recommendation: bytecode for facts,
  javac tree API for source ranges. Waiting for ADR acceptance and 7b approval.

## 2026-10-18 — Part 7b host trace-v1 producer (Claude Code)

- User merged 6e (PR #82) and 7a (PR #83): "please continue implement part 7b".
  Treated as accepting ADR 0004; split 7b host / 7c container / 7d API / 7e UI.
- 7de00b5 spec + ADR acceptance; 27722f4 shared contracts/trace/check.cjs;
  42bc5f6 runner/tracer module. Design: javac plan in code-layout order matched
  one-to-one to bytecode; recorder writes site + values; trusted builder emits
  trace-v1 with derived scopes, short-circuit operands, exceptions, limits and
  tamper detection. 27 tests passed; all traces pass the shared checker and all
  traced programs match the original's behavior. Host-only, trusted programs.

## 2026-10-18 — Part 7c container tracing (Claude Code)

- User: "I approve" for 7c. Stacked on unmerged 7b.
- 1a4387c Prepare CLI + plan file + stop causes; 99a83d2 shaded tracer-runtime.jar
  and root reactor pom; d1682ad TracedRunLifecycle (stdin staging, plan read
  before runtime, kill-then-read raw records, trace after confirmed
  termination); 239a39a live tests. First live timeout run lost its prefix
  (records still buffered at kill); recorder now flushes every 100 ms.
- Root reactor clean verify with live Docker: 98 tests passed (tracer 33,
  backend 65). Traces pass the shared checker; no containers left.

## 2026-10-18 — Part 7d minimal run API (Claude Code)

- User merged 7b (PR #84) and 7c (PR #85): "please review and continue implement" 7d.
- c0e2988 lifecycle uses run-v1 failure codes, reserved preparation deadline and
  explicit recovery; 0dc80a1 RunSupervisor (1 slot, 2 FIFO queued, 30 s expiry,
  revisions, retention 4 runs / 10 min); 8cd5e4a HTTP routes with a strict
  Jackson streaming parser and fixed error codes; 593c6ce contracts/run/check.cjs
  and live API tests (bubble sort, busy, queued and active cancellation, FIFO).
- Root reactor clean verify with live Docker: 118 tests passed (tracer 33,
  backend 85); contracts 52 passed; no containers left. All passed first run.

## 2026-10-18 — Coverage roadmap and Part 7e frontend slice (Claude Code)

- User asked whether the path still fits "visualize all the code as many as
  possible". Answer: yes in architecture (facts from bytecode, views by data
  shape), not yet in breadth. User: "ok, please write this up and then starting 7e".
- 6874f7a specs/coverage-roadmap.md: 8a graceful partial coverage first, then
  types, statements, methods, objects + object graph (moved into Core V1),
  collections, live input, exceptions.
- ab1a22b pinned scaffold + playback reducer; bea73ed UI; 9626c7d Playwright on
  installed Edge with the real backend and Docker. Vitest dropped after an npm
  arborist crash. First browser run showed main's variables vanish at the final
  cursor (correct FRAME_END cleanup); the last cursor now also shows the recorded
  pre-cleanup state, labelled. 7 unit and 2 browser tests passed.

## 2026-10-18 — Part 8a graceful partial coverage (Claude Code)

- User: "ok, continue part 8a". Contract 1.1.0 (0470f06): unknown state value,
  UNCAPTURED step, array OBJECT_CAPTURE; 1.0.0 retired.
- 433c3b1 tracer: per-statement rollback to uncaptured regions with effect
  analysis; javac -Xjcov CharacterRangeTable gives exact statement bytecode ranges
  (tabs verified); recorder re-reads arrays after writing regions and captures
  arrays first seen from uncaptured code; integrity guard keeps reflection/file/
  process/net/thread programs output-only; helper classes now shipped.
- ec73605 frontend, af58047 live/browser tests. First live verify: API test hit
  RUNNER_UNAVAILABLE because idle Docker answered the 5 s startup inventory late;
  15 s was rejected by the 10 s control cap, so 10 s. Final: 126 Java tests,
  54 contract, 9 reducer, 3 browser tests passed; no containers left.

## 2026-10-18 — Part 8b primitive types and operators (Claude Code)

- User: "ok" to the 8b proposal. 689d883: contract 1.2.0 and tracer for all
  primitive locals, javac-exact constant folding and implicit conversions, casts,
  bitwise/shift, long/float/double comparisons (NaN/-0.0 verified), compound
  assignment (incl. iinc and int[] elements), ++/-- on all numeric locals,
  conditional operator, declarations without initializer. e8acf40 frontend,
  2653f6c browser test.
- Mapping fixes found by tests: javac only special-cases constants on the right of
  a comparison (left-constant restriction lifted); constant long shift distances
  are folded (no l2i).
- Final: 132 Java tests (tracer 46, backend 86) with live Docker, 54 contract,
  10 reducer, 4 browser tests passed; no containers left.

## 2026-10-19 — Part 8c statements and arrays (Claude Code)

- User: "Please continue implementing part 8c" (2026-10-18). User merged 7d-8b
  (PRs #86-#89); the branch was rebased onto main 234a586.
- 4dc320a: contract 1.3.0 (SWITCH step, nested array element types) and tracer
  for do/while, labeled jumps, switch statements and top-level switch
  expressions on int-like selectors, for-each over arrays (javac's hidden copy,
  length and index matched, not shown), arrays of every primitive type, String
  and arrays of those (newarray/anewarray/multianewarray helpers, typed
  load/store helpers, initializer expressions). Uncaptured for-each loops map
  through javac's range-table entry at the iterated expression; inner arrays are
  captured before references, and before an uncaptured step that revealed them.
- a7fb958 frontend (rows for arrays of arrays, element paths, SWITCH text);
  0a128aa browser test. The browser test found a waiting declaration attached to
  the first case of a following switch; declarations now attach from their own
  plan position (regression test added).
- Live tests need `-Dcodeviz.docker.live=true` and `-Dcodeviz.docker.executable`.
  Final: 141 Java tests (tracer 55, backend 86) with live Docker, 56 contract,
  13 reducer, 5 browser tests; no containers left.

## 2026-10-19 — Part 8d static methods and recursion (Claude Code)

- User: "ok" to the 8d proposal. e8ac8a2: static methods of Main with traced
  types are planned and instrumented like main (one plan per method). Call sites
  announce themselves before invokestatic; entries without an announcement run
  silently (uncaptured callers, static initializers, callbacks). Every return
  instruction records its value (explicit, inside an uncaptured statement, or
  javac's implicit void return); a catch-all handler ends silent invocations that
  unwind. Builder keeps one frame (scopes, bindings, condition state) per
  invocation; uncaught failures unwind every frame; more than 64 traced frames
  halts with CALL_DEPTH_LIMIT. Contract unchanged (1.3.0); plan version 5.
- 0f5baec frontend call stack and descriptions; eb5fb0d browser test.
- Final: 145 Java tests (tracer 59, backend 86) with live Docker, 56 contract,
  15 reducer, 6 browser tests; no containers left.

## 2026-10-19 — Part 8e objects and fields (Claude Code)

- User: "ok" to the 8e proposal. 55f713d: contract 1.4.0 (object values,
  FIELD_READ/FIELD_WRITE, object re-reads in UNCAPTURED) and tracer for plain
  classes of the file (top level or static members of Main, no inheritance):
  new with ALLOCATE plus traced constructor (entered after super(), simple field
  initializers), instance and static methods with this, field reads/writes
  (compound and ++ too), objects first seen from uncaptured code captured
  breadth-first with cycles within one step, recorder null checks for field
  access, 1,024-identity limit (TRAVERSAL_LIMIT). Plan format 6.
- 1ef0197 frontend Objects panel (columns by reference distance, SVG arrows),
  field paths; aaca97d browser test.
- Final: 149 Java tests (tracer 63, backend 86) with live Docker, 58 contract,
  17 reducer, 7 browser tests; no containers left.

## 2026-10-19 — Part 8f strings and library collections (Claude Code)

- User: "ok" to the 8f proposal. 69b14bc: contract 1.5.0 (collection, map,
  builder objects; LIBRARY step; flattened concatenation; generic javaTypes) and
  tracer: javac inline concatenation as one step, String/StringBuilder methods,
  lists, deques, stacks, queues, maps, sets, priority queues and pure statics as
  recorded calls (receiver spilled, failures recorded by a one-instruction
  handler); boxing matched. The builder replays every call on mirrors of the
  same JDK classes and verifies results; PriorityQueue in poll order, HashSet/
  HashMap ascending. Fix found by tests: a first-seen receiver is now recorded
  before its call.
- bccb59c frontend Collections panel; 40888f0 browser test.
- Final: 152 Java tests (tracer 67, backend 86) with live Docker, 60 contract,
  19 reducer, 8 browser tests; no containers left.

## 2026-10-19/20 — Part 8g live console and Scanner input (Claude Code)

- User: "ok" to the 8g proposal; approved adding spring-boot-starter-websocket.
  b530c33: ADR 0001, live-console spec, contract 1.6.0 (`input` object), tracer
  Scanner reads replayed over the delivered transcript (plan format 7).
- d32776c backend: run request 1.1.0 (`inputMode` none/live), bounded ordered
  input (Part 4r), interactive runs (`docker exec -i`, 120 s), WebSocket console
  with controller, disconnect cancel (`INPUT_DISCONNECTED`).
- 029e08c frontend live console, "Read input" steps, AC-19 browser test.
- A first verify failed only because Docker Desktop was stopped.
- Final: 161 Java tests (tracer 69, backend 92) with live Docker, 61 contract,
  20 reducer, 9 browser tests; no containers left.

## 2026-10-20 — Part 8h exceptions (Claude Code)

- User: "ok, implement part 8h". 46df1b6: contract 1.7.0 (exception object,
  CATCH step, EXCEPTION objectId, union catch types) and tracer: throw,
  try/catch/finally in javac's layout with finally copies, in-flight
  exception tracking, mid-trace helper unwinding (`w`), getMessage replay,
  forged catch records rejected. Fixes found by tests: library failure
  handlers must sit after the call (a user catch never caught them) and
  uncaptured try-with-resources must start at its first resource.
- 751d0f5 frontend playback, exceptions example, container and browser tests.
- Final: 170 Java tests (tracer 77, backend 93) with live Docker, 62 contract,
  21 reducer, 10 browser tests; no containers left.

## 2026-10-20 — Part 8i hardening (Claude Code)

- User merged 8g/8h and said "continue implement" after Claude's 8i recommendation.
- 23212c6: recorder helpers drop their own frames from JDK exceptions; tracer
  tests compare full uncaught stack traces (3 tests had shown Recorder frames).
- 8b62621: BufferedReader over System.in as the input object (contract 1.8.0),
  readLine/read/close replayed; container live input test.
- 5ab7451: editor scrolls back horizontally for each step (browser check fails
  without it).
- Final: 171 Java tests (tracer 78, backend 93) with live Docker, 62 contract,
  21 reducer, 10 browser tests; no containers left.

## 2026-10-20 — Part 9.0 and 9a (Claude Code)

- User merged 8i and asked to start V1.1; Claude found Core V1 gaps (tree,
  heap, grid/dragging, Deque mode) and proposed Part 9 (Core V1 completion)
  then Part 10 (V1.1). User: "I approve".
- c2b1fd0: specs/v1-scope.md maps every acceptance case to status and part.
- dd459be: grid of panels, panel reorder, layout-only dragging (arrows follow),
  specs/frontend-design.md. 23 unit, 11 browser tests.
- 9b (user "ok"): tree view through a suggested-then-confirmed value/left/right
  mapping, Trees panel (in-order layout, dashed conflict links), tree-sort
  example, specs/tree-view.md. 26 unit, 12 browser tests.
- 9c (user "ok"): heap view of an array through a confirmed mapping (array,
  indexing, size variable, order); synchronized strip and tree, recorded
  boundary, observed order violations; heap-sort example, specs/heap-view.md.
  30 unit, 13 browser tests.
- 9d (user "ok"): ArrayDeque/LinkedList Deque/Stack/Queue view switch per
  variable; AC-12 done; Core V1 parts 9a-9d complete. 31 unit, 13 browser tests.

## 2026-10-21 — Part 10a separate test inputs (Claude Code)

- User merged 9a-9d and asked to continue with the next part.
- 794bfc4: run request 1.2.0 (inputMode provided with input text and hash),
  snapshot 1.1.0 input association, RunStdin/RunInput.provided in the
  backend, "Use test input" in the frontend. First live verify failed only
  because Docker Desktop was stopped.
- Final: 175 Java tests (tracer 78, backend 97) with live Docker, 62 contract,
  31 unit, 14 browser tests; no containers left.
- 10b (user "ok"): list, array stack and circular queue mappings of user
  classes, suggested from field types and confirmed; revisions per source;
  renamed/retyped fields stop the view (AC-27, AC-30). 35 unit, 15 browser tests.

## 2026-10-22 — Part 10c view rankings (Claude Code)

- User: "ok" to 10c (2026-10-21); the rank-1 rules draft was reviewed and
  approved ("ok, please implement it", 2026-10-22).
- 716026e: rankings.ts (pure, prefix-only), Suggested views card; AC-32..34 done.
  Committed before the full browser run by mistake; two drag tests failed
  (boxes below the fold) and a follow-up commit scrolls targets into view.
- Final: 41 unit, 16 browser tests; Java unchanged since 10a's 175-test verify.

## 2026-10-23 - Parts 10d, 6g, 11 and 12 (Claude Code)

- 10d.3-10d.5: lambdas and method references run by covered library calls,
  sequential streams (trace 1.10.0), problem suite and examples; AC-36 done.
- 6g: isolation review (specs/isolation-review.md), 30 s input-wait limit
  from /proc wchan, AC-20 done. V1 support matrix agreed ("1. I agree"); V1
  functionally complete (36/36 ACs). Public deployment on hold.
- Part 11 motion (PRs #109-#112): value flights, comparison badges, speed and
  Motion toggle (11a); index arrows (11b); call/return motion, example picker
  and icon fixes (11c); collection, object, tree and heap motion (11d).
- Part 12 (user: "I only need you to implement more library calls and
  Visual"), branch feat/part-12-library-visual: 12a Arrays.fill, Math.pow;
  12b String.format and Stream.of (varargs, plan format 11, traced JVM locale
  en-US); 12c iterators used directly (trace 1.11.0 `iterator` value); 12d grid
  row/column index arrows, writes indexed by their target, returning-frame
  ghost. Verify: tracer 139 + backend 102 with live Docker, 66 contract,
  53 unit tests.- Part 13 (user: "I want to know the relationship between callstack ... go ahead with all of
  them"), branch feat/part-13-recursion stacked on Part 12: call tree panel (13a), the line a
  frame waits on (13b), returned values kept until the caller's step that contains the call
  (13c; the first rule "next caller step" was wrong for fib(n - 1) + fib(n - 2), caught by the
  browser test). Frontend only. 55 unit, 25 browser tests.- Part 14 graph view (user: "how about we implement graph? we can starting with 2-dimension
  array"; -1 is no edge; layered tree-from-start layout), branch feat/part-14-graph: 14a square
  matrix, 14b neighbour lists (jagged int[][], List<List<Integer>>, List<Integer>[]), 14c index
  markers (adj.get(u) now counts as indexing) and per-node arrays (visited fills, others labels),
  14d spec specs/graph-view.md and examples "Graph BFS (matrix)", "Graph DFS (lists)".
  Frontend only. 62 unit tests, 3 new browser tests.- Part 15 complexity (user: "I don't need it to be exactly correct. I just want a result so
  that I can compare those algos"), branch feat/part-15-complexity stacked on Part 14: 15a
  Complexity.java (javac trees in the container; loops, nesting, calls, library costs, recursion
  by master theorem; guesses marked approximate), carried in the plan (format 12) and trace
  1.12.0 `complexity`; 15b editor badges and a Complexity panel ranking methods with measured
  calls/steps. Tracer 143 tests (ComplexityTest 4), contract 66, frontend 64 unit.