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
