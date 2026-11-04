---
name: stacked-pr
description: Implement one approved feature task on its own branch stacked on the previous task, run the folder's checks, and open a PR against the previous branch; or restack a task branch after the PR below it changes or merges. Use in the api/, app/ or agent/ session when given a task spec path.
argument-hint: <spec path> | restack <spec path>
---

# Stacked PR

Step 4 of the workflow in `feature/rules.md`, run by the session that owns the task's folder.

## Preconditions

- The spec has `status: approved`, and its `layer` matches the folder this session owns. If
  not, stop and tell the coordinator.
- `gh auth status` succeeds.
- The working tree is clean (`git status`). If it isn't, stop and report what's there; another
  session may be mid-task.

## Implement

1. `git fetch origin`, then check out the base branch from the spec and pull it.
2. Create the task branch from the spec's `branch` field: `git checkout -b <branch>`.
3. Follow the spec's steps exactly, using the folder's `CLAUDE.md`. Stay in your folder. If the
   spec is wrong or the contract doesn't work, stop and report back instead of improvising.
4. Write the tests the spec lists.
5. Run the spec's checks until they pass. Don't open the PR with failing checks.
6. Commit with a message like `<NNN> <NN>-<layer>: <what it delivers>`, ending with the commit
   attribution from the session's instructions.
7. Push: `git push -u origin <branch>`.
8. Open the PR against the base branch, not `main`:
   `gh pr create --base <base> --head <branch> --title "<NNN> <NN> <layer>: <task>" --body ...`
   The body has: the goal, a link to the spec, the ACs covered, the checks run, and
   "Stacked on #<base PR>". End it with the PR attribution from the session's instructions.
9. Check out the base branch again so the next session starts clean, and report the PR link
   to the coordinator with SendMessage.

## Review fixes

Commit fixes on the task's own branch, push, then restack every branch above it (below).

## Restack

After the PR below has changed or merged:

1. `git fetch origin` and check out the task branch.
2. If the base PR merged: `git rebase --onto origin/main <old base branch> <branch>`, then
   `gh pr edit <branch> --base main`. Otherwise: `git rebase origin/<base branch>`.
3. Resolve conflicts inside your folder only, rerun the checks, then
   `git push --force-with-lease`.
4. Report back to the coordinator.

## Rules

- Commits, pushes and PRs are allowed only on this feature's task branches. Never commit to or
  push `main`, and never force-push without `--force-with-lease`.
- One task at a time; don't start the next task without a dispatch.
