---
feature: 006-feature-implementation
status: draft
approved:
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
- Each task gets its own worktree from the checkout routes (003), on
  `feat/<NNN>-<slug>/<NN>-<layer>-<slug>`, based on the branch of the task below it (the first on
  `00-docs`).
- File tools scoped to that task's worktree: read, list, write, edit, and commit with the agent's
  author. No Bash; the agent doesn't run tests or builds. Checks run in GitHub CI only.
- Pushes go through the API's worktree push route. When a lower PR merges or changes, restacking
  rebases the branches above it and pushes them with force-with-lease.
- The feature moves to `implementation` when the first task starts, and to `done` when every PR
  has merged and the user confirms the acceptance criteria.

### 2. Pull requests

- The API opens, retargets and reads pull requests with its own token. The agent has no GitHub
  credentials; `GITHUB_TOKEN` is removed from the agent's service in `docker-compose.yml`.
- Creating a feature opens the `00-docs` PR to the default branch.
- Each task's PR targets the branch below it, titled `<NNN> <NN> <layer>: <task>`, with a body
  that links the spec and lists the ACs it covers.
- The agent tells the user when a PR is opened, when its CI fails (naming the failing check) and
  when a merge needs a restack. A failed check sends the agent back to fix it on the same branch.
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

## Assumptions

- Feature 003 gives the API worktree create, commit-less push and fetch; 002 gives a PR view and
  a GitHub pull client in the API.
- One checkout is shared, so one task is in progress at a time.

## Constraints

- agent: `agent/AGENTS.md` (narrow `mcp__app` tools, no built-in tools); locked files
  (`src/chat.ts`, `src/server.ts`) and `app/components/agent/agent-chat.tsx` need a skeleton
  change agreed first, or project-owned code around them.
- Base files `feature/rules.md`, `feature/_templates/*` and `.claude/skills/feature-*` are locked.
- Force pushes only with lease, only on task branches. Never commit to the default branch.

## Non-functional requirements

- Security: file tools can't leave the task worktree; no GitHub credentials in the agent; token
  scopes limited to what the API needs.
- Layout: question cards and the PR list work at 375 px without horizontal scrolling.
- Accessibility: question cards are keyboard operable with labelled options.

## Open questions

1. Which token the API uses for PRs: current `GIT_TOKEN` plus Pull requests read/write, or a new token.
2. How CI results and merges reach the agent: polling the API, GitHub webhooks, or the user telling the chat.
3. Whether a chat turn implements a whole task, or work is split across turns with check-ins.
4. What happens to a task's worktree after its PR merges.
5. Whether the user can stop, retry or skip a task from the feature page.

## Acceptance criteria

To be written once the open questions are settled.

## Changelog

- 2026-11-09 — Created.
- 2026-11-09 — Draft filled from the request; open questions listed.
