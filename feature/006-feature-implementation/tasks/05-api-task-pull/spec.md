---
feature: 006-feature-implementation
task: 05-api-task-pull
layer: api
status: approved
approved: 2026-11-10
branch: feat/006-feature-implementation/05-api-task-pull
base: feat/006-feature-implementation/04-api-task-start
covers: [AC-7, AC-13, AC-14, AC-16, AC-18]
---

# 05 — Open a task's pull request

## Goal

`POST …/tasks/{n}/pull` opens the PR for a task (row 0 included) with the agreed title and body, and
creating a feature opens its `00-docs` PR without failing the create when GitHub refuses.

## Contract

From task-breakdown.md:

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST …/tasks/{n}/pull` | `{ "task": "Start a task", "acs": ["AC-3", "AC-4"] }`; none for `n` = 0 | `201` Task | `404 task_not_found`, `409 pull_exists`, `409 task_not_pushed` (branch not on `origin`), `400 invalid_request` (blank `task`, no ACs, a value that isn't `AC-<n>`), `409 worktree_missing` |

Title `<NNN> <NN> <layer>: <task>` (row 0: `<NNN> <feature name>: docs`); head = branch, base = effective base; body =
the task line, a link to the spec on the docs branch on GitHub (row 0: to the feature folder), `ACs covered: …`, and
"Stacked on #<lower PR>" when the base is a task branch. Sets the task's step to `pr-open`. `POST /api/projects/{id}/features`
answers `201` Feature with `docsPullError` (the problem's `detail`, or null) and no PR when the docs PR failed.

## Files

Under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `service/TaskPulls.kt` — new `@Service`: `open(projectId, featureId, number, request): TaskView`, `openDocs(projectId, featureId): TaskView`.
- `service/FeatureStarter.kt` — new `@Service`: `start(projectId, name): StartedFeature(view, docsPullError)` calls
  `FeatureCreator.create`, then `openDocs`, catching `GitHubException`, `GitNotConfiguredException` and any other `Exception`
  into `docsPullError` via `PullProblems.describe`. `FeatureCreator` is not changed.
- `controller/FeatureController.kt` — change: `create` uses `FeatureStarter`; `FeatureResponse` gets
  `val docsPullError: String? = null` (set only by create).
- `dto/FeatureDtos.kt` — change: `OpenPullRequest(val task: String? = null, val acs: List<String>? = null)`.
- `controller/FeatureTaskController.kt` — change: `@PostMapping("/{n}/pull")` with `@RequestBody(required = false)`.
- `exception/FeatureExceptions.kt`, `controller/FeatureExceptionHandler.kt` — change: `PullExistsException`
  (`409 pull_exists`), `TaskNotPushedException` (`409 task_not_pushed`, "Push <branch> before opening its pull request."),
  and `400 invalid_request` for the body rules.

## Steps

1. `open`: the task row (or `TaskNotFoundException`); `pull_number != null` → `pull_exists`. For `n > 0`: `task` trimmed
   1–100 chars and `acs` non-empty, each matching `^AC-\d+$`, else `400 invalid_request` naming the field.
2. The task's worktree must exist and not be missing (`worktree_missing`); `git fetch` is not run, the branch must be on
   `origin`: `runner.refExists(clone, "refs/remotes/origin/<branch>")` after `checkoutService.fetchUnderLock` →
   else `task_not_pushed`.
3. Base = `StackRules.effectiveBase` from `statuses`; "Stacked on #n" when the base is a task row with a PR.
4. Title/body from `PullText` (02); spec URL = `PullText.blobUrl(owner, repo, feature.branch, task.specPath)`.
5. `GitHubPullWriteClient.create(owner, repo, title, head = branch, base, body)`; save `pull_number`; for `n > 0` call
   `featureProgress.updateStep(…, status = "pr-open")`, ignoring (with a warn log) an `InvalidStatusException` so a workflow
   without that state still works.
6. Return the task with its live status (`featureTasks.get`).
7. `openDocs` = `open` for row 0 (`ensureDocsRow` first) with no request body.

## Tests

- `TaskPullTests` (service, local bare remote, `@MockitoBean GitHubPullWriteClient` capturing `create`):
  - task 01 pushed → `create` called with title `006 01 api: <task>`, head the branch, base the docs branch, body containing
    the spec URL on the docs branch and `ACs covered: AC-3, AC-4`; `pull_number` saved; step `pr-open` (AC-14);
  - task 02 with 01 open → base is 01's branch and body has `Stacked on #<n>`; with 01 merged → base is the default branch;
  - second call → `pull_exists`; branch not on origin → `task_not_pushed`; blank task, no ACs, `ac-1`, `AC-` →
    `invalid_request` and no GitHub call (AC-14);
  - `create` throws `GitHubRefusedException(403)` → propagates (`502 github_error`), no `pull_number` saved (AC-16).
- `FeatureStarterTests` — docs PR opened after create with title `<NNN> <name>: docs` (AC-13); client failure →
  feature exists, `docsPullError` set, no row 0 pull number; the create response is still `201`.
- `FeatureTaskControllerTests` / `FeatureControllerTests` additions — `401`; statuses and codes above; `docsPullError`
  present in the create JSON (AC-18, AC-13, AC-7).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.

## Out of scope

- Restack (06). Deciding when the agent opens the PR (14, 15). Retry UI (10).
