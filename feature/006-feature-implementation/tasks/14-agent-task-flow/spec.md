---
feature: 006-feature-implementation
task: 14-agent-task-flow
layer: agent
status: approved
approved: 2026-11-10
branch: feat/006-feature-implementation/14-agent-task-flow
base: feat/006-feature-implementation/13-agent-task-files
covers: [AC-2, AC-3, AC-4, AC-7, AC-8, AC-10, AC-11, AC-15, AC-16]
---

# 14 — Task flow tools and instructions

## Goal

The agent can read the tasks and their PRs, start a task, open its PR, restack and complete the feature through the API, and its
instructions describe how implementation runs.

## Contract

From task-breakdown.md. Every tool takes `projectId` and `featureId`:

| Tool | Input | Calls |
| ---- | ----- | ----- |
| `feature_tasks` | ids | `GET …/tasks` |
| `task_start` | ids, `dir` | `POST …/tasks` `{ dir }` |
| `task_open_pull` | ids, `number`, `task?`, `acs?` | `POST …/tasks/{n}/pull` `{ task, acs }` (also row 0, no body) |
| `task_restack` | ids, `number` | `POST …/tasks/{n}/restack` |
| `feature_complete` | ids | `POST …/complete` |

API errors surface as the problem's `detail` and `code`; `specs_not_approved` / `tasks_unfinished` / `restack_conflict` also carry their
lists (`unapproved`, `unfinished`, `files`) in the tool error text. Step statuses used: `in-progress`, `pr-open`, `merged`, `stopped`,
`skipped`.

## Files

Under `agent/src/`:

- `api.ts` — change (not a base file): `ApiError` gets an optional `extras` (the problem body without `detail` and `code`, e.g.
  `unapproved`, `unfinished`, `files`), set by `parseProblem`/`request`. Existing behaviour is unchanged.
- `task-api.ts` — change: `describeApiError(error)` → `"<detail> (<code>) <extras as JSON>"`, used by the new tools.
- `tools/feature-tasks.ts`, `task-start.ts`, `task-open-pull.ts`, `task-restack.ts`, `feature-complete.ts` — new.
- `agent.ts` — change (`shared`): register them.
- `instructions.md` — change (`shared`): replace the last bullet ("Implementation … isn't available yet") with an "Implementation" section.
- Tests: `tools/task-flow.test.ts`.

## Steps

1. Tools as in the table; long calls use a 150 000 ms timeout (`task_start`, `task_restack`, `feature_complete`, and `task_open_pull`).
   `task_open_pull` with `number` ≥ 1 requires `task` (1–100 chars) and `acs` (1+ values like `AC-3`) in the schema check inside the
   handler; with `number` 0 it sends no body.
2. Extend `ApiError` with `extras` and `describeApiError` (see Files). Existing tests for `ApiError` keep passing.
3. Instructions, section "# Implementation":
   - Only when the user asks to start or continue implementation: `feature_get` to check status and that the workflow has an
     `implementation` step type — if it doesn't, say implementation isn't available for this feature and stop; otherwise read its skill
     with `feature_read_skill` (`step: "implementation"`) and follow it.
   - One task per turn: `task_start` → write code with the `task_*` file tools following the task's spec → `task_commit` → `task_open_pull` → record
     the step (`feature_update_step`: `in-progress`, then `pr-open`) → report the PR and stop. The next task starts only when the user says so.
   - Never run or ask to run tests or builds; checks run in GitHub CI. Never merge; the user merges on GitHub.
   - At the start of every implementation turn call `feature_tasks`: tell the user when a PR is opened, merged or closed, when a check fails (name the
     failing check and its summary), and when `needsRestack` is true. A failed check: fix on the same branch, `task_commit`, report. A merge or
     moved lower branch: offer a restack, and on the user's go-ahead call `task_restack` bottom-up, one task at a time; on `restack_conflict` report
     the files and stop.
   - Stop, retry and skip are done in chat: update the task's step (`stopped`, `skipped`, back to `in-progress`); a skipped or stopped task stays
     in the stack as the user decides.
   - When every PR has merged, ask the user with `ask_questions` to confirm the acceptance criteria ("Confirmed" / "Not yet"); only on "Confirmed" call
     `feature_complete`.
4. Register tools; keep `chat.ts` untouched.

## Tests

`task-flow.test.ts` (`node:test`, stubbed `fetch`):

- each tool sends the right method, path, body and bearer token; `task_open_pull` for row 0 sends no body (AC-7).
- `specs_not_approved` is returned as an error naming the specs (AC-2); `task_out_of_order`, `previous_task_unfinished` are shown with their detail (AC-3, AC-4).
- `restack_conflict` error text includes the files; `tasks_unfinished` includes the unfinished list (AC-8, AC-10).
- `feature_tasks` returns the checks so a failing check's name and summary are visible to the model (AC-15); a `502 github_error` becomes a tool error
  with its detail and is not retried (AC-16).
- `instructions.md` mentions `feature_read_skill`, one task per turn, no tests or builds, `needsRestack`, `stopped`/`skipped`, and confirming ACs before
  `feature_complete` (AC-7, AC-10, AC-11).

## Checks

- `docker exec factory-agent npm run typecheck`, `docker exec factory-agent npm test`.

## Out of scope

- The implementation skill itself and the workflow entry (15). Merging PRs. Any GitHub credential in the agent.
