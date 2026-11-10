---
feature: 006-feature-implementation
task: 04-api-task-start
layer: api
status: approved
approved: 2026-11-10
branch: feat/006-feature-implementation/04-api-task-start
base: feat/006-feature-implementation/03-api-task-foundation
covers: [AC-2, AC-3, AC-4, AC-18]
---

# 04 — Start a task

## Goal

`POST …/tasks` checks that every task spec is approved and the order is right, creates the task's
worktree and branch from its effective base, records the task row and an `implementation` step, and
moves the feature to `implementation`.

## Contract

From task-breakdown.md:

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST …/tasks` | `{ "dir": "02-api-task-start" }` | `201` Task | `400 invalid_task` (dir not `NN-<layer>-<slug>` or no `spec.md` there), `409 specs_not_approved` (`unapproved`: spec paths), `409 task_exists`, `409 task_out_of_order` (`expected`: the next number), `409 previous_task_unfinished` (the task below has no PR), `409 worktree_missing`, plus the checkout's git codes |

Behaviour: reads the docs worktree; every `tasks/*/spec.md` must have `status: approved` in its frontmatter.
Needs the next number (rows 0…N-1 exist and N-1 has a PR). Fetches, adds the worktree on
`feat/<code>/<NN>-<layer>-<slug>` from the effective base (`WorktreeService.add`), stores `base_head`, adds a step of
type `implementation` titled `Task <NN>: <layer> <slug>` with `documentPath` = the spec, and sets the feature to
`implementation` unless it is already `implementation` or `done`. A failure after the worktree exists removes the
worktree and branch and the row.

## Files

Under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `service/TaskSpecs.kt` — new: `TaskDir.parse(name)` (`^(\d{2})-(api|app|agent|infra)-([a-z0-9]+(-[a-z0-9]+)*)$`),
  `TaskSpecs.scan(worktreeDir, folder)` → `List<TaskSpec>(dir, number, layer, slug, specPath, status)` reading the
  `status:` line of each `tasks/*/spec.md` frontmatter (no frontmatter → status null).
- `service/TaskStarter.kt` — new `@Service` with `start(projectId, featureId, dir): TaskView`.
- `controller/FeatureTaskController.kt` — change: `@PostMapping` → `201`.
- `dto/FeatureDtos.kt` — change: `StartTaskRequest(val dir: String? = null)`.
- `controller/FeatureExceptionHandler.kt`, `exception/FeatureExceptions.kt` — change: `InvalidTaskException`,
  `SpecsNotApprovedException(unapproved)`, `TaskExistsException`, `TaskOutOfOrderException(expected)`,
  `PreviousTaskUnfinishedException`, `FeatureWorktreeMissingException`.

## Steps

1. `start`: `load` the feature; the docs worktree (`FeatureService.view(...).worktree`) must exist and not be missing →
   `FeatureWorktreeMissingException` (`409 worktree_missing`, "The worktree for <branch> is missing. Check it out again on the
   project page.").
2. `TaskDir.parse(dir)` else `InvalidTaskException`; the `spec.md` must exist → `invalid_task`.
3. `TaskSpecs.scan`: any spec whose status isn't `approved` → `SpecsNotApprovedException` with the paths in the problem's
   `unapproved` (AC-2). Nothing is created before this check.
4. `FeatureTasks.ensureDocsRow`; `expected = max(number) + 1`. A row with the parsed number → `task_exists`; a different
   number → `TaskOutOfOrderException(expected)`. The row `number - 1` must have `pull_number` → `previous_task_unfinished`.
5. Check the workflow before touching git: `codec.read(feature.workflow)` has step type `implementation` and, unless the
   feature is already past it, the feature state `implementation`; otherwise throw the same `InvalidStepTypeException` /
   `InvalidStatusException` that `FeatureProgress` throws (their `allowed` lists tell why).
6. `FeatureTasks.statuses` → base branch = `StackRules.effectiveBase` (GitHub failure propagates as `502`/`504`).
7. `locks.withLock(checkout.id)`: `checkoutService.fetchUnderLock`; `worktreeService.add(projectId, branch, base)`;
   `base_head` = `rev-parse origin/<base>` (else the local ref) in the clone. Save the row; then
   `featureProgress.addStep(…, "implementation", "Task NN: layer slug", specPath)`; save `step_id`; then
   `setStatus("implementation")` if needed (AC-3).
8. On any exception after `add`: delete the step and row if saved, `worktreeService.remove(force = true)`,
   `branch -D`, as `FeatureCreator.undo` does (copy its `attempt` helper into a small shared function if it can be
   reused without touching `FeatureCreator`'s behaviour; otherwise duplicate it). Rethrow.
9. Return `featureTasks.get(...)`.

## Tests

- `TaskSpecsTests` — dir parsing (valid, bad layer, bad slug), frontmatter status read, missing frontmatter.
- `TaskStartTests` (service, local bare remote seeded like `FeatureFixtures.seededRemote` plus a feature created through the
  fixtures of `FeatureCreateTests`; `@MockitoBean GitHubPullWriteClient`):
  - one spec `status: draft` → `SpecsNotApprovedException` listing it; no worktree, branch, row or step (AC-2);
  - all approved → task 01 started: branch `feat/<code>/01-api-x` exists in the clone, worktree on disk based on `00-docs`,
    row, step with the spec path, feature status `implementation`; task 02 does not exist (AC-3, AC-4);
  - task 02 before 01 has a PR → `previous_task_unfinished`; skipping to 03 → `task_out_of_order` with `expected = 2`;
    starting 01 twice → `task_exists`;
  - lower PR merged (mock status) → base is the default branch (AC-4);
  - a push-free failure after `add` (force `addStep` to throw) → worktree, branch, row removed;
  - workflow without `implementation` → `InvalidStepTypeException`, nothing created.
- `FeatureTaskControllerTests` additions — `401`; each exception → its status and code (`unapproved`, `expected` present);
  `dir` missing → `400 invalid_request` (AC-18).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.

## Out of scope

- Opening the PR (05), restack (06), completion (07). Making older features' stored workflow gain the new step type.
