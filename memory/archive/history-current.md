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
