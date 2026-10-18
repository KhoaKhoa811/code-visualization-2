# Sequential Codex / Claude Code handoff

Approved by the user on 2026-10-18: Codex works until its usage limit, then Claude
Code takes over until its limit, then Codex resumes. Either can hand off earlier.
One active agent at a time; no simultaneous editing, testing, or Git operations
in this checkout. This replaces the proposed implementer/reviewer pairing.

## Shared context

- `AGENTS.md`: common instructions. `CLAUDE.md` points here rather than duplicating rules.
- `requirements/PROJECT_REQUIREMENTS.md`: product intent and confirmed decisions.
- Relevant `specs/` and `specs/decisions/`: behavior and architectural decisions.
- `memory/current-state.md`: current milestone, progress, conversation summaries,
  approval evidence, decisions, and verified implementation.
- `memory/next-steps.md`: roadmap, remaining work, and approval boundaries.
- `memory/handoff.md`: concise latest stopping point and next concrete action.
- Git commits, working changes, and test results: evidence of actual work.

The handoff is an index, not a new requirements document. If files disagree,
apply AGENTS.md's source-of-truth rules. Git establishes what code exists, not
whether it is correct or authorized. Flag unresolved conflicts before dependent
work. Private chat/history, local agent memory, and hidden reasoning do not
transfer automatically; record all material user decisions and evidence in Git.
Never promise identical internal context or reconstruct unavailable conversations.

## Outgoing agent: checkpoint continuously

1. Update the handoff after every coherent implementation step, decision, test
   result, or blocker. Before a long command, record what is about to run and how
   to inspect its result. Do not wait for the usage limit warning.
2. Include approval wording/date, scope, checklist, branch, base/checkpoint commit,
   changed/untracked files, and the exact next action. Distinguish complete,
   implemented-but-unverified, in progress, and proposed work.
3. Record commands with working directory, tool versions when relevant, outcomes,
   timestamp and tested commit or description of the working changes. Preserve
   important failure evidence and checks still needed. Do not copy secrets or
   entire generated logs into memory; summarize evidence and locate local logs.
4. Record running commands, owned process/container IDs, artifacts, and cleanup
   status. Prefer a safe stopping point. Never claim an unfinished operation ended.
5. Update current-state and next-steps alongside the handoff. Commit and push
   completed, buildable pieces under the existing delivery rules. Include PR
   link or publication blocker. Never merge automatically.
6. Preserve unfinished files. On the same machine, list their state and leave
   them intact. Avoid hidden stashes; if a stash was necessary, record its exact
   identity and purpose. On another machine, push an explicitly labelled draft
   checkpoint branch before switching; disclose failing/unrun checks. Do not
   present a draft checkpoint as a completed or buildable delivery commit.
7. End the outgoing session before another agent writes. A note saying "ready"
   is not an automatic lock or proof that another agent has stopped.

## Incoming agent: recover before editing

1. A user-requested takeover authorizes context recovery for that request. Confirm
   the previous agent is stopped; if ownership is unclear, ask before mutation.
2. Read AGENTS.md, this protocol, handoff, current-state, next-steps, requirements,
   applicable directory instructions, and only the specs needed for the active task.
3. Inspect `git status --short --branch`, unstaged/staged diffs, untracked files,
   and recent commits. Compare them with the checkpoint. Never reset, clean,
   overwrite, or discard another agent's unfinished work to make notes match Git.
4. Fetch when remote access is available. Check whether the user merged branches.
   Incorporate newest main without losing local changes; do not blindly pull or
   assume a stale merge note is current. Report conflicts or remote blockers.
5. Inspect relevant running work and owned resources before rerunning commands.
   Do not start duplicate tests/services or delete resources of unknown ownership.
6. Review unfinished code and evidence. Treat missing evidence as unverified.
   Repeat checks when changes, failures, stale results, or uncertainty justify it;
   do not rerun every expensive suite solely because the agent changed.
7. Announce recovered scope, material uncertainty, and the next action. Set yourself
   as active owner in the handoff before mutations. Continue the approved task;
   existing approval follows the task across agents. A handoff does not approve
   later parts, scope expansion, merging, new dependencies, or unrelated work.
8. If work stopped abruptly with stale notes, reconstruct from files/Git/results,
   mark unknowns explicitly, and repair shared notes before progressing.

## Handoff fields (maintain these in handoff.md)

- Updated date/time and timezone; outgoing/current owner; ready/active status.
- Approved task, approval evidence, exclusions, current milestone.
- Branch, remote/base/checkpoint commits, PR/publication status.
- Small checklist with completed/pending steps and relevant code/spec paths.
- Dirty/staged/untracked files, draft commits/stashes, unfinished edits.
- Verification command, working directory, evidence, failures and unrun checks.
- Running processes/containers, ownership, cleanup, local-only artifacts.
- Important decisions, uncertainties, blockers, exact next action.

Use actual hashes for known checkpoints. A file cannot contain the hash of its
own future commit; label that distinction and re-read Git HEAD on takeover.
Keep the handoff concise; preserve historical discussion in current-state.md.
