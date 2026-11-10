---
feature: 006-feature-implementation
task: 15-infra-implementation-skill
layer: infra
status: approved
approved: 2026-11-10
branch: feat/006-feature-implementation/15-infra-implementation-skill
base: feat/006-feature-implementation/14-agent-task-flow
covers: [AC-1, AC-2, AC-3, AC-7, AC-11, AC-15]
---

# 15 — Implementation step type and chat skill

## Goal

The factory's workflow gets an `implementation` step type backed by a chat skill, so implementation can be started from a
feature's chat. This is the last task that changes behaviour: until it merges, the agent says implementation isn't available.

## Contract

From task-breakdown.md: step type `implementation` with states `in-progress`, `pr-open`, `merged`, `stopped`, `skipped` (first = initial),
skill folder `feature-implementation`. The skill tells the agent how to use the tools of tasks 12–14 and the API routes of 03–07.

## Files

- `feature/workflow.json` — change: add `{ "key": "implementation", "title": "Implementation", "skill": "feature-implementation",
  "states": ["in-progress", "pr-open", "merged", "stopped", "skipped"] }` after `spec`. Valid by 004's rules (the skill folder must have a
  `SKILL.md`, so both land together).
- `.claude/skills/feature-implementation/SKILL.md` — new (project-owned; not in `.claude/base-files`). Frontmatter `name`,
  `description`, `argument-hint: [task number]`. Sections:
  1. **Preconditions** — the user asked to start or continue; every `tasks/*/spec.md` is `approved` (the API checks; relay its
     `unapproved` list); the docs PR exists (open it with `task_open_pull` number 0 if not); the user's GitHub token scopes are the
     API's problem — on `502`/`503` from a PR route, say what the API said.
  2. **Start** — `feature_get`; `feature_tasks`; pick the lowest-numbered spec folder without a task; `task_start`; add nothing else (the
     API records the step and sets the feature to `implementation`). Update the step to `in-progress` if it isn't.
  3. **Implement** — read the spec (`task_read_file` or `feature_read_file` on the docs branch), the folder's `CLAUDE.md`/`AGENTS.md`
     and the files to imitate; follow the spec's steps exactly; write the tests it lists; stay inside the task's layer; if the spec or contract is
     wrong, stop and tell the user instead of improvising. Never run tests, builds or any command; checks run in GitHub CI.
  4. **Commit and PR** — `task_commit` (message `<NNN> <NN>-<layer>: <what it delivers>`), then `task_open_pull` with the task name and the ACs
     from the spec's `covers`; set the step `pr-open` (the API does it; verify with `feature_get`); report the PR link, the base, and stop.
     One task per turn.
  5. **CI** — at the start of every turn call `feature_tasks`; on a failing check, name it and its summary, fix on the same branch, `task_commit`,
     report. Pending checks: say so, don't wait.
  6. **Merges and restack** — when `feature_tasks` shows a merged PR or `needsRestack`, tell the user and, on their go-ahead, `task_restack`
     bottom-up, one task at a time; a `restack_conflict` is reported with its files and left to the user. Set steps to `merged` when their PR merged.
  7. **Stop, retry, skip** — stop: leave the work, set `stopped`; retry: set `in-progress` and continue on the same branch (`task_start` is not
     called again); skip: set `skipped` after the user confirms what that means for the tasks above it.
  8. **Finish** — when every PR is merged, ask with `ask_questions` whether the acceptance criteria were checked; only on a yes call
     `feature_complete` (sets `done`, removes task worktrees).
  9. **Rules** — never merge, never approve a document, only the user's explicit yes counts; no Bash; no GitHub credentials; work only on this
     feature's task branches.

## Steps

1. Write the skill file in the sections above, in plain imperative sentences, using the tool names exactly as registered (`feature_tasks`, `task_start`,
   `task_read_file`, `task_write_file`, `task_edit_file`, `task_commit`, `task_open_pull`, `task_restack`, `feature_complete`, `ask_questions`).
2. Edit `feature/workflow.json`.
3. `feature/rules.md`, `_templates/*` and the Claude Code skills `feature-dispatch` / `stacked-pr` are locked and stay as they are; the Claude Code workflow
   keeps working.

## Tests

Manual (AC-1, AC-2, AC-3, AC-7, AC-11, AC-15), on a feature **created after this task is on `main`** (the workflow is copied into a feature when it is created):

- AC-1: `feature/workflow.json` parses and lists `implementation`; creating a feature on the factory project succeeds (the API validator checks the skill folder).
- AC-2: with a spec still `draft`, asking the chat to start implementation is refused with the unapproved specs named; no worktree, branch or step.
- AC-3: with all specs approved, "start implementation" starts task 01 only and the feature shows `implementation`.
- AC-7: the turn ends with the PR link; task 02 does not start until asked.
- AC-11: asking to stop, retry and skip updates the step and the page offers no control.
- AC-15: a failing check is named by the agent and fixed on the same branch.

## Checks

- `python3 -c "import json;json.load(open('feature/workflow.json'))"` or any JSON parser; the file lists a `SKILL.md` for every `skill`.

## Out of scope

- Making features created earlier (including 006) pick up the new step type: a feature keeps the workflow it was created under (004). Where that blocks
  an older feature, the agent says implementation isn't available for it.
- Editing locked files.
