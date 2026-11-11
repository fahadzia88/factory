---
name: feature-implementation
description: Implement a feature's approved tasks from its chat, one task per turn - start the task's branch, write its code, commit, open its stacked pull request, follow CI and restacks, and finish the feature once everything merged. Use in the feature chat when the user asks to start or continue implementation.
argument-hint: [task number]
---

# Feature implementation

The implementation step of the workflow in `feature/rules.md`, run by the agent in a feature's
chat. The API owns branches, worktrees, pull requests and restacks; you write code in the task's
worktree with the `task_*` file tools.

## 1. Preconditions

- The user asked to start or continue implementation. Never start on your own.
- Every `tasks/*/spec.md` must be `approved`. The API checks this when a task starts; if it
  refuses with `specs_not_approved`, name the unapproved specs and stop.
- The docs pull request must exist. If `feature_tasks` shows task 0 without a pull request, open
  it with `task_open_pull` (number 0, no task or ACs).
- GitHub access is the API's: when a pull request route answers `502` or `503`, tell the user what
  the API said and stop. Never ask for a token.

## 2. Start

- Call `feature_get`, then `feature_tasks`.
- Pick the lowest-numbered spec folder in `tasks/` that has no task yet, and call `task_start` with
  its folder name. The API records the step and moves the feature to `implementation`; add nothing
  else.
- If the step isn't `in-progress`, set it with `feature_update_step`.

## 3. Implement

- Read the task's spec (`task_read_file` on the task's worktree, or `feature_read_file` on the docs
  branch), the folder's `CLAUDE.md` or `AGENTS.md`, and the files the spec says to touch or imitate.
- Follow the spec's steps exactly, and write the tests it lists. Stay inside the task's layer.
- If the spec is wrong or its contract can't work, stop and tell the user what you found. Don't
  improvise around it.
- Never run tests, builds or any command, and never ask to: checks run in GitHub CI.
- `task_write_file` and `task_edit_file` refuse locked skeleton files. When a spec needs one changed,
  stop and tell the user.

## 4. Commit and pull request

- `task_commit` with a message like `<NNN> <NN>-<layer>: <what it delivers>`.
- `task_open_pull` with the task's name and the ACs from the spec's `covers`.
- Check with `feature_get` that the step is `pr-open` (the API sets it).
- Report the pull request link and its base, then stop. One task per turn: start the next one only
  when the user asks.

## 5. CI

- At the start of every turn, call `feature_tasks`.
- For a failing check, name it and give its summary, fix it on the same branch, `task_commit`, and
  report.
- For pending checks, say they're running; don't wait for them.

## 6. Merges and restacks

- When `feature_tasks` shows a merged pull request or `needsRestack`, tell the user.
- On their go-ahead, call `task_restack` bottom-up, one task at a time.
- A `restack_conflict` is reported with its files and left to the user.
- Set a task's step to `merged` once its pull request merged.

## 7. Stop, retry, skip

- Stop: leave the work as it is and set the step to `stopped`.
- Retry: set the step back to `in-progress` and continue on the same branch. Don't call
  `task_start` again.
- Skip: set the step to `skipped`, after the user confirms what it means for the tasks above it.

## 8. Finish

- When every pull request has merged, ask with `ask_questions` whether the acceptance criteria were
  checked against the running app ("Confirmed" / "Not yet").
- Only on "Confirmed", call `feature_complete`. It sets the feature `done` and removes the task
  worktrees; report any it kept.

## 9. Rules

- Never merge a pull request and never approve a document. Only the user's explicit yes counts.
- No Bash, no commands, no GitHub credentials.
- Work only on this feature's task branches, through the tools.
