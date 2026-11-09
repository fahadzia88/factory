---
feature: 006-feature-implementation
status: approved
approved: 2026-11-09
---

# Feature Implementation

## Problem

A feature stops once its task specs are approved (004). The chat agent can't implement tasks or
open pull requests, so the stacked-PR part of the workflow (`/feature-dispatch`, `/stacked-pr`)
still needs a Claude Code session. The agent's questions are also plain text, so choices,
recommendations and approvals are typed by hand.

## Users

- **People who sign in to the app.** They start implementation from a feature's chat, follow each
  task's PR and CI result, merge on GitHub, and confirm the acceptance criteria.
- **The agent.** It implements one task at a time in a task worktree, with file tools only.
- **The API.** It creates task worktrees, pushes, restacks, and opens, retargets and reads pull
  requests with its own token.

## Scope

### 1. Implementation step

- A new step type `implementation` in `feature/workflow.json`, backed by a new chat skill
  (the Claude Code skills `feature-dispatch` and `stacked-pr` stay as they are).
- The user starts it from the feature chat once every task spec is approved. Tasks run one at a
  time in stack order. Each task records a step with its task number, spec path, branch and PR.
- **One task per turn.** The agent implements a task, commits, pushes and opens its PR in one
  turn, reports, and stops. The next task starts only when the user says so.
- Each task gets its own worktree from the checkout routes (003), on
  `feat/<NNN>-<slug>/<NN>-<layer>-<slug>`, based on the branch of the task below it (the first on
  `00-docs`).
- File tools scoped to that task's worktree: read, list, write, edit, and commit with the agent's
  author. No Bash; the agent doesn't run tests or builds. Checks run in GitHub CI only.
- Pushes go through the API's worktree push route. When a lower PR merges or changes, restacking
  rebases the branches above it and pushes them with force-with-lease.
- **Worktrees** of merged tasks are kept until the feature is done (later tasks are stacked on
  them), then removed.
- The feature moves to `implementation` when the first task starts, and to `done` when every PR
  has merged and the user confirms the acceptance criteria.
- **Stop, retry, skip** are asked for in the chat; the agent handles them. The feature page has
  no controls for them.

### 2. Pull requests

- The API opens, retargets and reads pull requests with its own token: the existing `GIT_TOKEN`
  with Pull requests read and write added. The agent has no GitHub credentials; `GITHUB_TOKEN`
  is removed from the agent's service in `docker-compose.yml`.
- Creating a feature opens the `00-docs` PR to the default branch.
- Each task's PR targets the branch below it, titled `<NNN> <NN> <layer>: <task>`, with a body
  that links the spec and lists the ACs it covers.
- **Polling.** No webhooks. The API reads PR state and checks from GitHub when asked; the agent
  reads them through the API at the start of each turn about implementation and when the user
  asks. The agent tells the user when a PR is opened, when its CI fails (naming the failing
  check) and when a merge needs a restack. A failed check sends the agent back to fix it on the
  same branch.
- The feature page shows each task's PR with its state and checks, reusing the pull request view
  from 002 where it fits.

### 3. Questions in the chat

- A tool for structured questions (the chat version of AskUserQuestion): 1–4 questions, each with
  a short header, 2–4 options with descriptions, a recommended option first, multi-select when
  allowed, and always an "Other" free-text answer.
- The app shows them as selectable cards; the answers go back to the agent as the user's next
  message. While questions wait, the composer still works and a typed reply counts as the answer.
- Questions and answers stay in the chat history after a reload.
- Approval is unchanged: only the user's explicit yes approves a document; an "Approve" option
  counts as that yes.

## Non-goals

- Running tests or builds in the agent.
- Merging PRs (the user merges on GitHub).
- Auto-approving any document.
- Running several tasks at once.
- GitHub webhooks.
- Stop, retry or skip controls on the feature page.

## Assumptions

- Feature 003 gives the API worktree create, push and fetch; 002 gives a PR view and a GitHub
  pull client in the API.
- One checkout is shared, so one task is in progress at a time.
- The `GIT_TOKEN` can be given Pull requests read and write; without it PR calls fail with 502.

## Constraints

- agent: `agent/AGENTS.md` (narrow `mcp__app` tools, no built-in tools); locked files
  (`src/chat.ts`, `src/server.ts`) and `app/components/agent/agent-chat.tsx` need a skeleton
  change agreed first, or project-owned code around them.
- Base files `feature/rules.md`, `feature/_templates/*` and `.claude/skills/feature-*` are locked.
- Force pushes only with lease, only on task branches. Never commit to the default branch.

## Non-functional requirements

- Security: file tools can't leave the task worktree; no GitHub credentials in the agent; the
  token has only the scopes the API needs.
- Layout: question cards and the PR list work at 375 px without horizontal scrolling.
- Accessibility: question cards are keyboard operable with labelled options.

## Open questions

- None.

## Acceptance criteria

### Implementation step

- **AC-1** — Given `feature/workflow.json`, then it lists an `implementation` step type with
  states, and its skill folder has a `SKILL.md`.
- **AC-2** — Given a feature where some task spec isn't approved, when the user asks the chat to
  start implementation, then the agent refuses, names the unapproved specs, and creates no
  worktree, branch or step.
- **AC-3** — Given all specs approved, when the user starts implementation, then the feature
  status becomes `implementation` and the agent starts task 01 only.
- **AC-4** — Given a task starts, then it has its own worktree on
  `feat/<NNN>-<slug>/<NN>-<layer>-<slug>` created through the API, based on the branch of the
  task below it (`00-docs` for the first), and a step recording the task number, spec path,
  branch and PR.
- **AC-5** — Given a task in progress, when the agent writes code, then it reads, lists, writes
  and edits files only inside that task's worktree and commits with the agent's author. A path
  outside the worktree, or in another worktree, is refused and nothing is written.
- **AC-6** — Given the agent, then it has no Bash and never runs tests or builds; checks are
  those of GitHub CI.
- **AC-7** — Given a task is implemented, when the agent finishes the turn, then the commits are
  pushed through the API, the PR is open, the agent reports it, and the next task doesn't start
  until the user says so.
- **AC-8** — Given a lower PR merged or its branch changed, when the user asks or the agent reads
  PR state, then the agent says a restack is needed; on the user's go-ahead the API rebases the
  branches above, pushes them with force-with-lease, and retargets their PRs.
- **AC-9** — Given a restack hits a conflict, then nothing is pushed, the branch is left as it
  was, and the agent tells the user which task and files conflict.
- **AC-10** — Given a task's PR merged, then its worktree is kept; given every PR merged and the
  user confirms the acceptance criteria, then the feature status becomes `done` and the task
  worktrees are removed.
- **AC-11** — Given the user asks in the chat to stop, retry or skip a task, then the agent does
  so and updates the task's step; the feature page offers no such control.

### Pull requests

- **AC-12** — Given the agent's service in `docker-compose.yml`, then it has no `GITHUB_TOKEN`
  and the agent has no way to call GitHub.
- **AC-13** — Given a feature is created, then its `00-docs` PR to the default branch is opened
  by the API; if opening fails, the feature is created and the failure is shown with a retry.
- **AC-14** — Given a task's PR is opened, then it targets the branch below it, its title is
  `<NNN> <NN> <layer>: <task>`, and its body links the spec and lists the ACs it covers.
- **AC-15** — Given a task's CI check fails, when the agent reads the PR state, then it tells the
  user the failing check by name and, on the user's go-ahead or by the skill's rule, fixes it with
  new commits on the same branch and pushes.
- **AC-16** — Given a GitHub failure or missing token scope, when the API opens or reads a PR,
  then it answers 502 (504 on timeout) and the agent reports it without retrying silently.
- **AC-17** — Given a feature with PRs, when its page opens, then each task's PR is listed with
  its state (open, merged, closed) and its checks, using the 002 pull request view where it
  fits; a task with no PR yet shows none.
- **AC-18** — Given a request without a valid bearer token, when it calls any PR endpoint, then
  it gets 401.

### Questions in the chat

- **AC-19** — Given the agent calls the question tool with 1–4 questions, each with a header and
  2–4 options with descriptions, then the chat shows selectable cards with the recommended
  option first and an "Other" free-text answer on each. More questions, fewer than 2 or more than
  4 options, or a missing header is refused to the agent.
- **AC-20** — Given a multi-select question, then several options can be chosen; otherwise only
  one.
- **AC-21** — Given the user answers the cards, then the answers are sent as the user's next
  message and the agent continues from them.
- **AC-22** — Given questions are waiting, when the user types a reply in the composer and sends
  it, then the reply counts as the answer and the cards are marked answered.
- **AC-23** — Given a chat with questions and answers, when the page reloads, then both are shown
  in the history, answered cards read-only with the chosen options.
- **AC-24** — Given a document waiting for approval, when the user picks "Approve" on a question
  card, then it counts as an explicit yes; if the user doesn't, the agent doesn't record approval.
- **AC-25** — Given a 375 px wide screen, when the question cards and the feature's PR list are
  shown, then nothing scrolls horizontally, and the cards work with the keyboard.

## Changelog

- 2026-11-09 — Created.
- 2026-11-09 — Draft filled from the request; open questions listed.
- 2026-11-09 — Open questions settled with the user (GIT_TOKEN with PR scope, polling, one task per
  turn, keep worktrees until done, no page controls); acceptance criteria written.
- 2026-11-09 — Approved.
