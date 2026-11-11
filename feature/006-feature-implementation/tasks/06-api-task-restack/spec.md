---
feature: 006-feature-implementation
task: 06-api-task-restack
layer: api
status: approved
approved: 2026-11-10
branch: feat/006-feature-implementation/06-api-task-restack
base: feat/006-feature-implementation/05-api-task-pull
covers: [AC-8, AC-9, AC-18]
---

# 06 — Restack a task

## Goal

`POST …/tasks/{n}/restack` rebases a task's branch onto its current effective base, pushes it with
force-with-lease, and retargets its PR. A conflict aborts and leaves everything as it was.

## Contract

From task-breakdown.md:

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST …/tasks/{n}/restack` | — | `200` Task | `404 task_not_found`, `409 restack_not_needed`, `409 task_dirty` (uncommitted changes), `409 task_closed` (PR merged or closed), `409 restack_conflict` (`files`: conflicting paths; nothing pushed, branch as before), `409 worktree_missing`, git codes |

Behaviour: fetch; new base = effective base; `git rebase --onto origin/<newBase> <base_head> <branch>` in the task's
worktree; on conflict `git rebase --abort`. Then push with force-with-lease (`WorktreeService.push(force = true)`),
retarget the PR when the base changed (`PATCH …/pulls/{n}` `base`), store `base_branch` and `base_head`.

## Files

Under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `service/TaskRestacker.kt` — new `@Service`: `restack(projectId, featureId, number): TaskView`.
- `controller/FeatureTaskController.kt` — change: `@PostMapping("/{n}/restack")` → `200`.
- `exception/FeatureExceptions.kt`, `controller/FeatureExceptionHandler.kt` — change: `RestackNotNeededException`,
  `TaskDirtyException`, `TaskClosedException`, `RestackConflictException(files)` with `setProperty("files", files)`.

## Steps

1. Task row (`task_not_found`; row 0 → `restack_not_needed`: nothing is below it). `statuses` for the feature. Own PR
   missing → `restack_not_needed`; merged or closed → `task_closed`.
2. `StackRules.needsRestack` false → `restack_not_needed`. (A GitHub error reading the lower PR propagates.)
3. The task's worktree must exist (`worktree_missing`). `git status --porcelain` in it, untracked files included, not
   empty → `task_dirty`.
4. `locks.withLock(checkout.id)`: `fetchUnderLock`; `newBase = StackRules.effectiveBase(...)`; the ref
   `origin/<newBase>` must exist.
5. `git -c user.name=… -c user.email=… -c commit.gpgsign=false rebase --onto origin/<newBase> <base_head>` in the worktree,
   with the identity from `FeatureProperties.commitName/commitEmail` (as `FeatureCreator`). Non-zero exit: list
   `git diff --name-only --diff-filter=U`, run `git rebase --abort`, throw `RestackConflictException(files)`. Any other
   failure also aborts a rebase in progress (`rebase --abort`, errors ignored) before rethrowing.
6. `worktreeService.push(projectId, worktreeId, force = true)`.
7. If `newBase != task.baseBranch`: `GitHubPullWriteClient.setBase(owner, repo, pull_number, newBase)`. A failure here is
   rethrown (`502`); the branch is already pushed and the row is not updated, so the next restack replays only what is
   not applied yet (git skips patches already in the new base).
8. Save `base_branch = newBase` and `base_head = rev-parse origin/<newBase>`; touch `updated_at`.
9. Return `featureTasks.get(...)`.

## Tests

`TaskRestackTests` (service; local bare remote with `main`, a docs branch, task 01 and task 02 branches created through
the real services; `@MockitoBean GitHubPullWriteClient` returning scripted `PullStatus`):

- lower branch (01) gets a new commit and its PR head SHA changes → restack 02: 02's commits replayed on 01's new tip,
  remote 02 updated with a lease push, `setBase` not called, `base_head` = 01's tip, `needsRestack` false afterwards (AC-8);
- 01's PR merged (squash-style: main gets a different commit with the same changes) → restack 02 with
  `--onto origin/main`: `setBase(…, "main")` called, `base_branch = main` (AC-8);
- a conflicting change on both sides → `RestackConflictException` listing the file; the branch tip, the worktree
  (clean, no `.git/rebase-merge`) and the remote are unchanged; `setBase` not called (AC-9);
- uncommitted file in the worktree → `task_dirty`; merged or closed PR → `task_closed`; nothing to do →
  `restack_not_needed`; worktree removed → `worktree_missing`;
- remote moved by someone else between fetch and push → the push is refused (`PushRejectedException`) and nothing is
  retargeted.
- `FeatureTaskControllerTests` — each exception's status and code, `files` in `restack_conflict`; `401` (AC-18).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.

## Out of scope

- Restacking several tasks in one call (the agent calls it bottom-up, one task at a time). Resolving conflicts.
