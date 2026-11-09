---
feature: 006-feature-implementation
task: 07-api-feature-complete
layer: api
status: draft
approved:
branch: feat/006-feature-implementation/07-api-feature-complete
base: feat/006-feature-implementation/06-api-task-restack
covers: [AC-10, AC-18]
---

# 07 — Complete a feature

## Goal

`POST …/complete` finishes a feature once every PR has merged: it sets the status `done` and removes the
task worktrees. Whether the user confirmed the ACs is for the agent to ask; the API only checks the PRs.

## Contract

From task-breakdown.md:

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST …/complete` | — | `200` Feature | `409 tasks_unfinished` (`unfinished`: task numbers and reasons: spec folder without a task, no PR, PR not merged), `409 invalid_status` (workflow has no `done`) |

Every spec folder has a row and every row has a merged PR. Sets the feature to `done` and removes the worktrees of
rows 1… (`WorktreeService.remove`, no force); one it can't remove stays and is named in the response's `keptWorktrees`.
The docs worktree stays.

## Files

Under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `service/FeatureCompleter.kt` — new `@Service`: `complete(projectId, featureId): CompletedFeature(view, kept)`.
- `controller/FeatureController.kt` — change: `@PostMapping("/{featureId}/complete")`; `FeatureResponse` gets
  `val keptWorktrees: List<KeptWorktreeResponse>? = null`, `KeptWorktreeResponse(branch, reason)` (set only here).
- `exception/FeatureExceptions.kt`, `controller/FeatureExceptionHandler.kt` — change: `TasksUnfinishedException(unfinished)`
  → `409 tasks_unfinished` with `setProperty("unfinished", [{ "task": 2, "reason": "pull request not merged" }, …])`.

## Steps

1. Load the feature; `TaskSpecs.scan` over the docs worktree (when it is missing, use only the rows).
2. Unfinished list: for each spec folder number without a row → `"no task started"`; for each row (0 included) without a
   `pull_number` → `"no pull request"`; with a PR whose live state isn't `merged` → `"pull request not merged"` (state
   named: open/closed). A GitHub read failure on any row propagates as `502`/`504` (never treated as merged).
   Non-empty → `TasksUnfinishedException`.
3. `codec.read(feature.workflow).featureStates` must contain `done` else `InvalidStatusException` (via
   `featureProgress.setStatus`, called before any removal).
4. Remove each row ≥ 1's worktree (found by `worktreeForBranch`; missing rows are skipped) with
   `worktreeService.remove(projectId, id, force = false)`; catch `Exception` per worktree and record
   `KeptWorktree(branch, message)`; log at info.
5. Return the feature view and `kept`; the controller puts `kept` in the response (empty list when none).

## Tests

`FeatureCompleteTests` (service, local bare remote, scripted `PullStatus` mock):

- rows 0, 1, 2 all merged, specs 01–02 → status `done`, worktrees of 01 and 02 gone (folders and rows), docs worktree
  and all branches' rows kept, `keptWorktrees` empty (AC-10);
- row 2 open → `tasks_unfinished` listing `{task: 2, reason: "pull request not merged"}`, status and worktrees unchanged;
- a spec folder 03 with no task → `{task: 3, reason: "no task started"}`; a task with no PR → `"no pull request"`;
- a workflow without `done` → `409 invalid_status` and worktrees intact;
- `WorktreeService.remove` throwing for one worktree → the feature is `done`, that worktree in `keptWorktrees`;
- GitHub failing for one row → `502`, nothing changed.
- `FeatureControllerTests` — `401`; `tasks_unfinished` body has `unfinished`; `200` body has `keptWorktrees` (AC-18).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.

## Out of scope

- Asking the user to confirm the ACs (agent, 14/15). Deleting `feature_task` rows or remote branches.
