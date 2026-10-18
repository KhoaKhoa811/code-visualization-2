# Sequential Codex / Claude Code handoff

Approved by the user on 2026-10-18: Codex works until its usage limit, then Claude
Code takes over until its limit, then Codex resumes. Either can hand off earlier.
One active agent at a time; no simultaneous editing, testing, or Git operations
in this checkout. This replaces the proposed implementer/reviewer pairing.

## Shared context

- `AGENTS.md`: common instructions. `CLAUDE.md` points here rather than duplicating rules.
- `requirements/PROJECT_REQUIREMENTS.md`: product intent and confirmed decisions.
- Relevant `specs/` and `specs/decisions/`: behavior and architectural decisions.
- `memory/handoff.md`: **authority for the active task.** A short snapshot of
  owner, approvals, branch, checklist, dirty files, evidence, resources and the
  exact next action. Replace the whole file on every update; never append.
- `memory/current-state.md`: short current summary (milestone, verified
  implementation, standing decisions). Updated when a task finishes.
- `memory/next-steps.md`: short roadmap and approval boundaries. Updated when a
  task finishes or the roadmap changes.
- `memory/archive/`: older conversation history, handoffs and approval records,
  moved unchanged. Read only when a question needs history; append new history
  to `memory/archive/history-current.md` when a task finishes.
- Git commits, working changes, and test results: evidence of actual work.

During an active task, current-state and next-steps may lag behind the handoff.
That lag is expected, not a conflict: for the active task, handoff.md wins.
At task end, fold its outcome into those files and the archive.

The handoff is an index, not a new requirements document. If files disagree
outside the active-task rule above, apply AGENTS.md's source-of-truth rules. Git establishes what code exists, not
whether it is correct or authorized. Flag unresolved conflicts before dependent
work. Private chat/history, local agent memory, and hidden reasoning do not
transfer automatically; record all material user decisions and evidence in Git.
Never promise identical internal context or reconstruct unavailable conversations.

## First takeover and session differences

Start the incoming agent in the same repository directory. If the handoff files
are not on main yet, give it the published handoff branch or commit from the
outgoing message. Inspect the current checkout before switching branches; do
not overwrite unfinished files or assume "not on main" means the files are missing.
For a different machine, verify that the required branch/checkpoint is pushed
and available before claiming to have recovered it.

User task approval transfers with its recorded scope; execution permissions,
sandbox approvals, credentials, installed tools, MCP connections, skills and
agent session IDs do not. Check capabilities in the incoming session. Follow its
own permission controls and report blockers; never copy credentials into memory
or bypass restrictions because the previous agent ran a command. An unavailable
local skill is not part of the shared contract: preserve the task's recorded
requirements and explain any necessary workflow difference.

An explicit user statement that the previous agent stopped is sufficient for
ownership confirmation unless current evidence contradicts it. Do not repeatedly
ask for the same confirmation. Otherwise resolve uncertain ownership before edits.
There is no automatic notification, usage-limit detector, or cross-agent lock.
The user starts the next session and provides the takeover request.

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
5. Replace handoff.md with a fresh snapshot each time; delete superseded lines
   instead of adding corrections below them. Record every test result, including
   failures, before starting the next step. At task end, update current-state,
   next-steps and the archive. Commit and push completed, buildable pieces under
   the existing delivery rules. Include PR link or publication blocker. Never
   merge automatically.
6. Preserve unfinished files. On the same machine, list their state and leave
   them intact. Avoid hidden stashes; if a stash was necessary, record its exact
   identity and purpose. On another machine, push an explicitly labelled draft
   checkpoint branch before switching; disclose failing/unrun checks. Do not
   present a draft checkpoint as a completed or buildable delivery commit.
7. End the outgoing session before another agent writes. A note saying "ready"
   is not an automatic lock or proof that another agent has stopped.

## Near-limit checkpoint (required when a limit is visible)

When a usage/context warning appears, remaining capacity is visibly low, or the
user warns of an imminent limit, stop starting new implementation, broad edits,
or long tests. Use remaining capacity to preserve context first. Do not invent a
remaining percentage or assume either tool can reliably predict its usage limit.

1. Save `memory/handoff.md` immediately with status `checkpointing: near limit`.
   Record the approved task, owner, branch/HEAD, exact stopping point, dirty and
   untracked files, last completed step, unfinished edits, and next concrete action.
2. Record passed/failed/unrun/in-progress checks with commands, working directory,
   and evidence paths. List known problems, pending decisions, and any fragile
   assumptions the incoming agent must verify. Never turn an unrun check into a pass.
3. Identify running commands and owned processes/containers, their results/logs,
   and cleanup state. Reach a safe pause when possible within approved scope;
   otherwise explicitly mark what is still active. Never claim a limit stops it.
4. Update current-state and next-steps only if capacity remains; handoff.md is
   already authoritative for the active task, so a lag there is acceptable.
5. Commit/push completed buildable pieces if time and existing permissions permit.
   Preserve unfinished changes; do not force an unverified completion commit.
   For a cross-machine transfer, follow the draft-checkpoint procedure above and
   explicitly identify anything not yet pushed. Saving the handoff comes first.
6. Once work has stopped, mark `ready for takeover` and tell the user the branch,
   latest checkpoint, unfinished scope, and next action. If forcibly interrupted
   sooner, the incoming agent uses the abrupt-stop recovery procedure; a stale
   `checkpointing` or `active` label is not proof that work is still running.

This rule applies equally to Codex and Claude Code. Keep normal continuous
checkpoints because an abrupt limit may arrive without any warning.

## Incoming agent: recover before editing

1. A user-requested takeover authorizes context recovery for that request. Confirm
   the previous agent is stopped; if ownership is unclear, ask before mutation.
2. Read AGENTS.md, this protocol, handoff, current-state, next-steps, requirements,
   applicable directory instructions, and only the specs needed for the active task.
   Do not read `memory/archive/` unless a question needs history.
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

Before implementation, give the user a short recovery acknowledgment containing:
active owner; branch and actual HEAD; approved task; completed versus unfinished
work; latest relevant test evidence; unknowns/blockers; and the next action.
This is a comprehension check, not a new approval request for already approved
work. If anything material cannot be recovered, identify it rather than guessing.

## Handoff fields (maintain these in handoff.md)

- Updated date/time and timezone; outgoing/current owner; ready/active status.
- Approved task, approval evidence, exclusions, current milestone.
- Branch, remote/base/checkpoint commits, PR/publication status.
- Small checklist with completed/pending steps and relevant code/spec paths.
- Dirty/staged/untracked files, draft commits/stashes, unfinished edits.
- Verification command, working directory, evidence, failures and unrun checks.
- Running processes/containers, ownership, cleanup, local-only artifacts.
- Important decisions, uncertainties, blockers, exact next action.
- Session-specific tool/permission limitations and unresolved user questions.

Use actual hashes for known checkpoints. A file cannot contain the hash of its
own future commit; label that distinction and re-read Git HEAD on takeover.
Keep the handoff under about 100 lines; preserve historical discussion in
`memory/archive/`. Follow the section layout of the current handoff.md.

## Permissions inside an approved task

AGENTS.md defines the task-scoped permission rule (approved 2026-10-18). Both
agents apply it identically: inside an approved task, act without per-action
confirmation; ask before scope changes, the next task, merges, deletions of
resources not owned by the task, or new dependencies.

## User's takeover message

Use only after the outgoing agent has stopped. It works for either agent:

> The previous agent has stopped. In this repository, read AGENTS.md and
> memory/agent-handoff-protocol.md, then recover from memory/handoff.md and the
> actual Git state. Report your recovery acknowledgment, then continue the
> approved work recorded in the handoff.

Shared files provide the same recorded project context, not identical private
chat history or guaranteed identical reasoning.
