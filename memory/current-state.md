# Current state and conversation memory

Last updated: 2026-10-06 (Asia/Saigon).

## Recovery instructions

Read this file when resuming this project, then read `../AGENTS.md`,
`../requirements/PROJECT_REQUIREMENTS.md`, and `next-steps.md` before planning work.
Requirements define intent; specifications define intended behavior; this file
records conversation and verified local progress. Memory does not override either.

Append future user requests, decisions, assistant outcomes, verification results,
and blockers here after each approved task. Preserve chronological history while
keeping the current-state sections accurate. This record covers the conversation
available in this session; it cannot reconstruct unavailable earlier chats.
Long project instructions remain in their source files rather than being duplicated.

## Current scope

- Active milestone: Milestone 0, repository and specifications; incomplete.
- Approved task: commit and push all current project files as the initial
  repository baseline, following the user's latest request. The companion
  `next-steps.md` records remaining work under the project delivery instructions.
- No application implementation, dependency installation, or additional milestone
  work has been approved in this conversation.
- Use the caveman skill for concise chat replies. Repository documentation stays
  in normal prose. The user also explicitly requested applying project AGENTS.md.
- Discuss proposed scope and obtain confirmation before new work. Complete only
  approved scope. Project delivery requires verification, both memory updates,
  commits, pushes, and a PR where access and repository state permit. Never merge
  a PR or start the next task without authorization.

## Verified local state

- Workspace: `C:\Workspace\code-visualization-2`; shell: PowerShell.
- Repository: https://github.com/KhoaKhoa811/code-visualization-2.git
- Git initialized on `main`; `origin` fetch and push URLs match that repository.
- Remote access succeeded through an approved command outside the network sandbox.
  The remote HEAD query returned no references; the repository appeared empty.
- Before this task, the only project files found were `AGENTS.md` and
  `requirements/PROJECT_REQUIREMENTS.md`, both untracked. Preserve their contents.
- This task adds `memory/current-state.md` and `memory/next-steps.md`.
- No local `specs/`, application modules, build files, tests, or prototype were found.
  Requirements describe historical prototype verification and PR #11, but those
  artifacts are absent here. Those historical claims are not locally verified.
- Git author identity is now configured locally as `khoakhoa811`
  <nguyenvoanhkhoa9487@gmail.com> and verified with `git var GIT_AUTHOR_IDENT`.
  GitHub CLI (`gh`) is unavailable. The user authorized an initial direct push
  to establish `main`; the previously empty remote has no base for a bootstrap
  PR. Confirm publication from Git and the remote when resuming.

## Product context for recovery

The product is an educational Java execution visualization application. Confirmed
stack: Java 21/Spring Boot/Maven, React/TypeScript/Vite, TanStack Query/Router,
Axios, and Windows-local Docker Desktop/WSL2 Linux runners. Exact dependency
installation and compatibility are not verified in this workspace.

Recorded diagram playback starts after execution terminates. Each Step advances
one observable operation with synchronized source-expression highlighting.
Runtime facts must come from isolated execution and preserve Java semantics,
object identity, aliases, frames, and deterministic backward replay.

All discussed structure families, methods/recursion, live Scanner input, separate
test inputs, custom mappings, factual object graphs, synchronized grid panels,
and evidence-based view rankings remain V1 requirements. Deliver approved
increments, starting with variables and arrays. Missing later coverage is not
optional. Consult requirements for full scope and confirmed clarifications.

## Conversation history — 2026-10-06

1. The user supplied project AGENTS.md instructions and workspace context. The
   instructions are preserved in `../AGENTS.md`. No application task was requested.
2. User: "use caveman"
   Assistant loaded the caveman skill and replied: "Short replies. Technical
   detail stays." Concise chat style applies until changed by the user.
3. User: "apply AGENTS.md for this project"
   Assistant: "AGENTS.md applies. Confirm scope before project work; follow
   requirements, verify changes, update both memory files, commit, push, and
   create/update PR."
4. User: "here is the git" followed by
   `https://github.com/KhoaKhoa811/code-visualization-2.git`, then
   "please connect to this project".
   Assistant inspected local Git state and project instructions. The directory
   was not a Git repository. A sandbox network attempt failed; approved remote
   access succeeded with no remote HEAD references. Git was initialized on
   `main`. Setting `origin` initially failed because `.git/config` was protected;
   the approved retry succeeded. Existing files were preserved and uncommitted.
   Final response confirmed connection, remote access, and the empty repository.
5. User: "please create memory/current-state.md. this file will contain all our
   conversation and you can use it to recover memory when you forgot something"
   Assistant scoped one documentation part, inspected requirements and existing
   files, and created this recovery record plus `next-steps.md`. It distinguished
   historical requirements claims from verified local state and identified
   missing Git identity and GitHub CLI as delivery blockers.
6. User: "push the current files to git first"
   This authorizes including the existing AGENTS.md and requirements alongside
   both memory files in the initial baseline. The assistant checked Git status,
   origin, and author identity. Git reported "Author identity unknown". The
   assistant requested the user's author name and email for repository-local
   configuration. Commit and push remain pending that required information.
7. User: "khoakhoa811 nguyenvoanhkhoa9487@gmail.com"
   The assistant configured and verified this author identity only in this
   repository. The approved delivery is one initial commit containing all four
   project files, pushed to origin/main, followed by remote-hash verification.
   No application work or PR merge is authorized by this delivery.

## Verification and handoff

This is documentation-only work. Verified both new files and referenced recovery
paths exist, no conflict markers appear, and Git still reports no commits with
the existing instructions/requirements and new memory directory untracked.
No application tests or builds have been run or claimed. Author identity is
verified. This record accompanies the initial baseline commit; use Git status,
log, and remote refs to verify its publication rather than assuming a pending
push succeeded. Subsequent approved tasks should use the documented branch/PR
workflow after this baseline exists.
