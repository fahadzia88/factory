---
feature: 006-feature-implementation
task: 13-agent-task-files
layer: agent
status: draft
approved:
branch: feat/006-feature-implementation/13-agent-task-files
base: feat/006-feature-implementation/12-agent-ask-questions
covers: [AC-5, AC-6]
---

# 13 — Task file tools

## Goal

File tools scoped to a task's worktree: list, read, write, edit and commit with the agent's author and push through the API.
There is still no tool that runs a command.

## Contract

From task-breakdown.md, "Agent tools". Every tool takes `projectId`, `featureId` and `number` (the task number, 1 or more):

| Tool | Input | Does |
| ---- | ----- | ---- |
| `task_list_files` | ids, `number`, `dir?` | Files in that task's worktree (≤ 500, `.git` excluded). |
| `task_read_file` | ids, `number`, `path` | A text file, ≤ 200 KB. |
| `task_write_file` | ids, `number`, `path`, `content` | Writes a file anywhere in the task's worktree. |
| `task_edit_file` | ids, `number`, `path`, `oldText`, `newText` | One exact unique replacement. |
| `task_commit` | ids, `number`, `message` | Checks `HEAD` is the task's branch, `git add -A`, `git commit` (agent author), push via the checkout push route. Returns the SHA or "nothing to commit". |

The task and its worktree come from `GET /api/projects/{projectId}/features/{featureId}/tasks`, never from the model's paths. Path rules
(code, not prompt): relative; the real path (symlinks followed) is inside the task's worktree; never in `.git`; a path matching a `locked`
pattern in the worktree's `.claude/base-files` (and not listed in `.claude/base-overrides`) is refused for writes. Row 0 is not
writable with these tools. Writes and commits are refused for a task whose PR is merged or closed, and for a task that is not the
highest-numbered one unless its PR is open.

## Files

Under `agent/src/`:

- `task-api.ts` — new: `TaskRow` type (as the Task JSON), `loadTasks`, `findTask`, `requireTaskWorktree` (same message as
  `requireWorktree`: "The worktree for <branch> is missing. Check it out again on the project page."), `assertWritable(task, tasks)`.
- `locked-files.ts` — new: `readLockedPatterns(worktree)` (parses `locked <glob>` lines of `.claude/base-files`, minus globs listed in
  `.claude/base-overrides`), `globToRegExp` (`*` matches anything including `/`, `?` one character, the rest literal), `isLocked`.
- `feature-api.ts` — change: export `resolveInside` through a new `resolveTaskPath(worktree, rel)` (read/write guard with the
  `.git` rule), and move the file walk of `feature-list-files.ts` into `listFiles(root, dir)` used by both tools.
- `feature-git.ts` — change: add `commitAll(worktree, message)` (`git add -A`, staged check, `git commit -m`, short SHA) and
  `currentBranch(worktree)`.
- `tools/task-list-files.ts`, `task-read-file.ts`, `task-write-file.ts`, `task-edit-file.ts`, `task-commit.ts` — new, one per tool,
  `tool()` + Zod, ids from `featureIds`, plus `taskNumber = z.number().int().min(1)`.
- `agent.ts` — change (`shared`, additive): register the five tools.
- Tests: `tools/task-files.test.ts`, `locked-files.test.ts`.

## Steps

1. `task-api.ts`: `loadTasks` = `apiGet<TaskRow[]>(…/tasks)`; `findTask` throws "Task <n> hasn't been started. Start it with task_start."
   when missing and "Task 0 is the docs branch; use the feature_* file tools." for 0.
2. `assertWritable`: refuse when `task.pull?.state` is `merged` or `closed`; refuse unless `task.number` is the highest row number
   or `task.pull?.state === "open"`; the message says why and which task is active.
3. Read tools: `requireTaskWorktree`, `resolveTaskPath`, same limits as the `feature_*` read tools (200 KB, 500 files).
4. Write/edit: `assertWritable`, `resolveTaskPath`, then the locked check: `relative` path (posix, no leading `./`) against
   `readLockedPatterns(worktree)` → "<path> is a locked skeleton file; the spec can't change it. Tell the user." Edit keeps `feature_edit_file`'s
   semantics (missing text, more than one match, `$` literal).
5. `task_commit`: `serialFor(worktree, …)`; `currentBranch` must equal `task.branch` else error and nothing committed; `commitAll`;
   nothing to commit and nothing unpushed → "Nothing to commit in task <n>."; then `apiPost(…/checkout/worktrees/<id>/push, { force: false }, 150_000)`
   exactly like `feature_commit` (a failed push keeps the commit and says so; a retry pushes it). Env/author from `gitIn`.
6. Register in `agent.ts`. Nothing else in `chat.ts`: `tools: []` stays.

## Tests

`task-files.test.ts` (as `feature-write.test.ts`: temp bare remote + worktree, stubbed `fetch` serving `GET …/tasks` and the push route):

- write, edit, read and list work inside the worktree, including new directories (AC-5).
- Refused and nothing written: `../x`, `/tmp/x`, `.git/hooks/pre-commit`, a symlink to outside, a path in another task's worktree,
  `number` 0, an unstarted task (AC-5).
- A locked path (`agent/src/chat.ts` with `locked agent/src/chat.ts` in `.claude/base-files`) is refused; listed in `base-overrides` it
  is allowed; a `shared` path is allowed (AC-5).
- Merged/closed task refused; lower task with no open PR refused when a higher one exists; lower task with an open PR allowed (fix a failed check).
- `task_commit`: commits all changes as `GIT_USER_NAME <GIT_USER_EMAIL>`, pushes once with `{ force: false }`, no `user.*` config written;
  wrong branch checked out → refused; nothing to commit → says so; rejected push keeps the commit and a retry pushes it.
- Missing worktree → the standard message for every tool.
- AC-6: the exported tool names contain no command-running tool, and `chat.ts` is unchanged (`git diff` empty in review).
- `locked-files.test.ts` — glob → regexp cases (`dir/*`, `*.ts`, `?`), comments and blank lines, overrides.

## Checks

- `docker exec factory-agent npm run typecheck`, `docker exec factory-agent npm test`.

## Out of scope

- Starting tasks, PRs, restack, completion (14). Deleting or moving files. Running tests or builds (never).
