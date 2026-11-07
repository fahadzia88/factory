---
feature: 004-features
task: 03-api-feature-read
layer: api
status: approved
approved: 2026-11-06
branch: feat/004-features/03-api-feature-read
base: feat/004-features/02-api-feature-foundation
covers: [AC-21, AC-24, AC-25, AC-26, AC-27]
---

# 03 — Feature read routes

## Goal

`GET /api/projects/{id}/features` and `GET /api/projects/{id}/features/{featureId}`: the
Feature summary list and the full Feature JSON (steps, stored workflow, and the worktree found by
branch, `null` or `missing` when it's gone). Adds `FeatureExceptionHandler` with the codes every
feature route shares. AC-21, AC-24 and AC-25 are covered on the API side; the app shows them in
09 and 11.

## Contract

Provides, copied from task-breakdown.md:

Every route needs `Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as
`ProblemDetail` (`application/problem+json`) with a `code` property the app and agent switch on and
a `detail` they can show as is. On every route: `404 project_not_found`, `404 feature_not_found`
(no such feature, or it belongs to another project), `400 invalid_request` (non-numeric id,
unreadable body, missing required field).

### Feature (JSON)

```json
{
  "id": 21,
  "projectId": 12,
  "number": 4,
  "code": "004-features",
  "name": "Features",
  "slug": "features",
  "status": "requirements",
  "branch": "feat/004-features/00-docs",
  "folder": "feature/004-features",
  "worktree": { "id": 9, "path": "/repos/12.worktrees/feat__004-features__00-docs", "missing": false },
  "workflow": { "featureStates": ["…"], "steps": [{ "key": "…", "title": "…", "skill": "…", "states": ["…"] }] },
  "steps": [
    {
      "id": 5,
      "position": 1,
      "type": "requirements",
      "typeTitle": "Requirements",
      "title": "Requirements",
      "documentPath": "feature/004-features/requirements.md",
      "status": "draft",
      "states": ["draft", "approved"],
      "createdAt": "2026-11-06T10:00:00Z",
      "updatedAt": "2026-11-06T10:00:00Z"
    }
  ],
  "createdAt": "2026-11-06T10:00:00Z",
  "updatedAt": "2026-11-06T10:00:00Z"
}
```

- `worktree` is `null` when no worktree with `branch` exists; the app shows that as missing too.
  `missing` is 003's flag (folder not on disk).
- Feature summary (list rows): `id`, `number`, `code`, `name`, `status`, `updatedAt`.

### Routes (this task's rows)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET /api/projects/{id}/features` | — | `200` `[Feature summary]`, newest number first | — |
| `GET /api/projects/{id}/features/{featureId}` | — | `200` Feature | — |

There is no rename, archive or delete route for features or steps.

## Files

Main code under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `service/FeatureService.kt` — new: `list(projectId)`, `get(projectId, featureId)`, and the
  shared `load` and `view` used by later tasks.
- `service/FeatureView.kt` — new: `FeatureView(feature, workflow, steps, worktree: FeatureWorktree?)`,
  `FeatureWorktree(id, path, missing)`.
- `dto/FeatureDtos.kt` — new: `FeatureResponse`, `FeatureSummaryResponse`, `FeatureStepResponse`,
  `FeatureWorktreeResponse`, `WorkflowResponse`, `WorkflowStepResponse`, each with `from(...)`.
- `controller/FeatureController.kt` — new: `@RequestMapping("/api/projects/{id}/features")`.
- `controller/FeatureExceptionHandler.kt` — new:
  `@RestControllerAdvice(basePackageClasses = [FeatureController::class])`.
- `exception/FeatureExceptions.kt` — change: add `FeatureNotFoundException`.
- `checkout/domain/RepositoryWorktreeRepository.kt` — change: add
  `findByCheckoutIdAndBranch(checkoutId: Long, branch: String): RepositoryWorktree?` (project
  code, additive).

Tests under `api/src/test/kotlin/com/toptier/custom/feature/`: `FeatureReadTests.kt`,
`FeatureControllerTests.kt` (new).

## Steps

1. **`FeatureService`** (`@Service`, not `@Transactional`, like `WorktreeService`). Injects
   `ProjectService`, `FeatureRepository`, `FeatureStepRepository`, `WorkflowCodec`,
   `RepositoryCheckoutRepository`, `RepositoryWorktreeRepository`, `WorktreeInspector`.
   - `load(projectId, featureId): Feature`: `projects.get(projectId)` (throws
     `ProjectNotFoundException`), then `features.findByIdAndProjectId(featureId, projectId)` or
     `FeatureNotFoundException()` — another project's feature is not found too (AC-25).
   - `list(projectId): List<Feature>`: `projects.get(projectId)`, then
     `findByProjectIdOrderByNumberDesc` (AC-24).
   - `get(projectId, featureId): FeatureView = view(load(projectId, featureId))`.
   - `view(feature): FeatureView`: decode `feature.workflow` with `WorkflowCodec`; steps
     `findByFeatureIdOrderByPositionAsc`; worktree via `worktreeOf(feature)`.
   - `worktreeOf(feature): FeatureWorktree?`: the project's checkout
     (`checkouts.findByProjectId`); none, or `state != READY` → `null`; else
     `worktrees.findByCheckoutIdAndBranch(checkout.id, feature.branch)`; none → `null`; else
     `FeatureWorktree(row.id, row.path, inspector.missing(Path.of(row.path)))` (AC-21). No git
     commands run here beyond what `missing` does (a file check).
2. **DTOs** (`FeatureDtos.kt`), shaped exactly as the contract:
   - `FeatureResponse.from(view)`: `code = FeatureNaming.code(number, slug)`; `steps` map with
     `typeTitle` and `states` from `workflow.step(typeKey)` (`typeTitle = typeKey`, `states =
     emptyList()` if the stored workflow somehow lacks the key); times as `Instant` (serialized
     ISO-8601 like the checkout DTOs).
   - `FeatureSummaryResponse.from(feature)`: `id`, `number`, `code`, `name`, `status`,
     `updatedAt`.
3. **`FeatureController`**: `GET ""` → `featureService.list(id).map(FeatureSummaryResponse::from)`;
   `GET "/{featureId}"` → `FeatureResponse.from(featureService.get(id, featureId))`. No other
   mappings on the feature resource itself (AC-26).
4. **`FeatureExceptionHandler`**, modelled on `CheckoutExceptionHandler` with the same private
   `problem(status, code, detail)` helper:
   - `ProjectNotFoundException` → `404 project_not_found`, "Project not found.";
   - `FeatureNotFoundException` → `404 feature_not_found`, "Feature not found.";
   - `MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException`,
     `MissingServletRequestParameterException` → `400 invalid_request` with the same details as
     `CheckoutExceptionHandler`;
   - `InvalidFeatureNameException` → `400 invalid_name` and `WorkflowInvalidException` → `409
     workflow_invalid` are added in 04; put the handler for the sealed `FeatureException` in
     place now with a `when` that later tasks extend.
5. **`RepositoryWorktreeRepository.findByCheckoutIdAndBranch`**: a derived query; no other change
   in the checkout package.

## Tests

- `FeatureReadTests` (`@DataJpaTest`, importing `FeatureService`, `WorkflowCodec`,
  `ProjectService`, `WorktreeInspector`, `GitRunner`, `CheckoutTestConfig`; a `@MockitoBean
  GitHubRepositoryChecker` as `WorktreePushTests` does). Rows are inserted directly:
  - `list` returns the project's features newest number first, not another project's (AC-24);
  - `get` returns steps in position order, `typeTitle`/`states` from the workflow, and the stored
    workflow (AC-1 storage);
  - a feature whose branch has a worktree row whose folder holds a `.git` → `worktree` with
    `missing = false`; folder deleted → `missing = true`; row deleted → `worktree == null`; no
    checkout or a `FAILED` checkout → `null` (AC-21);
  - `get` of another project's feature, and of an unknown id, throws `FeatureNotFoundException`
    (AC-25); unknown project → `ProjectNotFoundException`.
- `FeatureControllerTests` (`@WebMvcTest(FeatureController::class)`, `@Import(SecurityConfig::class,
  FeatureExceptionHandler::class)`, `@TestPropertySource(properties = ["app.security.api-token=test-token"])`,
  `@MockitoBean FeatureService`):
  - both routes answer `401` without a token (AC-27);
  - list answers `200` with summary rows (`code` is `004-features`) (AC-24);
  - get answers `200` with the contract's JSON shape, `worktree` `null` serialized as `null`
    (AC-21);
  - `feature_not_found` and `project_not_found` → `404` with `code` (AC-25); `/features/abc` →
    `400 invalid_request`;
  - `DELETE /{featureId}`, `PUT /{featureId}` and `PATCH /{featureId}` → `405` (AC-26).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.
- The app starts against MySQL; `curl -H 'authorization: Bearer 123456' localhost:8080/api/projects/<id>/features`
  answers `[]`.

## Out of scope

- Create (04), status and step writes (05), messages (06).
- The `409 project_archived`, `checkout_not_ready`, git and workflow codes (04).
