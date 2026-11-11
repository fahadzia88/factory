---
feature: 006-feature-implementation
task: 03-api-task-foundation
layer: api
status: approved
approved: 2026-11-10
branch: feat/006-feature-implementation/03-api-task-foundation
base: feat/006-feature-implementation/02-api-github-pull-client
covers: [AC-15, AC-17, AC-18]
---

# 03 — Task records and `GET …/tasks`

## Goal

The `feature_task` table, the stack rules (effective base, needs-restack), and the two read routes
that return each task with its live pull request state and checks.

## Contract

From task-breakdown.md. Task JSON (abridged): `number`, `layer`, `slug`, `branch`, `baseBranch`,
`specPath`, `stepId`, `worktree` { `id`, `path`, `missing` } or null, `pull` (see 02) or null,
`pullError` or null, `needsRestack`, `createdAt`, `updatedAt`. Row 0 is the docs branch
(`layer: "docs"`, `slug: "docs"`, `specPath` and `stepId` null).

| Method & path | Response | Errors (`code`) |
| ------------- | -------- | ---------------- |
| `GET /api/projects/{id}/features/{featureId}/tasks` | `200` `[Task]`, number ascending, row 0 first | — |
| `GET …/tasks/{n}` | `200` Task; a GitHub failure on its own PR answers `502`/`504` | `404 task_not_found` |

- `pull` is null when no PR was opened or GitHub couldn't be read; then `pullError` is the problem's
  `detail`. The single-task route answers the error instead of `pullError`.
- `needsRestack` is true for an open task whose PR isn't merged or closed and either its stored
  `baseBranch` is no longer its effective base, or its base is a task branch whose PR head SHA differs
  from `base_head`.
- Effective base of task N: the branch of the nearest lower task whose PR isn't merged (a task with no
  PR counts as not merged), else the default branch.
- GitHub failures: `502 github_error`, `502 github_rate_limited`, `503 github_not_configured`/`git_not_configured`,
  `504 github_timeout`, as 002's handler maps them.

## Files

Under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `domain/FeatureTask.kt`, `domain/FeatureTaskRepository.kt` — new: table `feature_task` (`id`, `feature_id`,
  `number`, `layer` ≤ 10, `slug` ≤ 60, `branch` ≤ 200, `base_branch` ≤ 200, `base_head` ≤ 64 null, `spec_path` ≤ 300 null,
  `step_id` null, `pull_number` null, `created_at`, `updated_at`); unique `(feature_id, number)`.
  Repository: `findByFeatureIdOrderByNumberAsc`, `findByFeatureIdAndNumber`.
- `service/StackRules.kt` — new: pure `effectiveBase(number, rows, merged: (Int) -> Boolean, defaultBranch)` and
  `needsRestack(task, lower, pull, lowerPull)`.
- `service/FeatureTasks.kt` — new `@Service`: `ensureDocsRow(feature)`, `list(projectId, featureId): List<TaskView>`,
  `get(projectId, featureId, number): TaskView`, `statuses(...)` (all rows' pull status, used by 04–07).
  Parses owner/repo with `GitHubRepositoryUrl.parse(project.repositoryUrl)` like `PullRequestService`.
- `service/FeatureService.kt` — change: `worktreeOf(feature)` delegates to a new public
  `worktreeForBranch(projectId, branch): FeatureWorktree?`.
- `service/PullProblems.kt` — new object: `describe(ex: Exception): Problem(status, code, detail)` for the GitHub and
  git-not-configured exceptions; used by the handler and by `pullError`.
- `dto/TaskDtos.kt` — new: `TaskResponse`, `PullResponse`, `ChecksResponse`, `CheckRunResponse`.
- `controller/FeatureTaskController.kt` — new: `@RequestMapping("/api/projects/{id}/features/{featureId}/tasks")`.
- `controller/FeatureExceptionHandler.kt` — change: maps `GitHubException` (via `PullProblems`), and the new
  `TaskNotFoundException`.
- `exception/FeatureExceptions.kt` — change: `TaskNotFoundException` (`404 task_not_found`, "Task not found.").

## Steps

1. Entity and repository (tabs, KDoc, `ddl-auto=update` creates the table). `Clock` for times.
2. `StackRules`: walk down from `number - 1` to 0; the first row whose pull is not merged gives its branch; none →
   default branch. `needsRestack`: task open, `baseBranch != effective`, or (`baseBranch` is a row's branch and that
   row's pull `headSha != baseHead`). No lower pull data (GitHub unreadable) → false.
3. `ensureDocsRow`: row 0 with `layer = "docs"`, `slug = "docs"`, `branch = feature.branch`, `baseBranch = checkout.defaultBranch`;
   a unique-key race re-reads the winner.
4. `statuses`: for each row with `pull_number`, `GitHubPullWriteClient.status`; catch `GitHubException` and
   `GitNotConfiguredException` per row and keep the error for that row.
5. `list` builds `TaskView`s with worktree (via `worktreeForBranch`), pull or `pullError`, `needsRestack`. `get` does the
   same and rethrows its own row's GitHub error.
6. Controller and DTO mapping; `PullResponse` has the fields listed in 02's `PullStatus`.
7. Handler: add the GitHub mapping (copy 002's mapping through `PullProblems`; the "refused" text names "Pull requests: read
   and write").

## Tests

- `FeatureTaskRepositoryTests` (`@DataJpaTest`) — unique `(feature_id, number)`.
- `StackRulesTests` — effective base with: no lower PR, lower open, lower merged, 00 merged and 01 open; `needsRestack`
  when the lower head moved, when the lower merged, and not for a merged/closed task (AC-17).
- `FeatureTasksTests` (service, `@MockitoBean GitHubPullWriteClient`) — row 0 created once on first read; list order; a
  failing check run name appears in `pull.checks.runs` (AC-15); a GitHub error on one task gives `pullError` and the others
  still load; a task with no PR has `pull = null` and no error (AC-17).
- `FeatureTaskControllerTests` (`@WebMvcTest`, `SecurityConfig`, `FeatureExceptionHandler`) — `401` without a token
  (AC-18); `404 task_not_found`, `404 feature_not_found`; GitHub errors → `502 github_error`, `504 github_timeout`,
  `503`; `abc` as number → `400 invalid_request`.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`; the app starts against MySQL and
  `curl` of `GET …/tasks` on a feature returns row 0.

## Out of scope

- Starting a task, opening PRs, restack, completion (04–07). Creating spec folders' rows.
