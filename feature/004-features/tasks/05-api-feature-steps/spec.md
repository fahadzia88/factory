---
feature: 004-features
task: 05-api-feature-steps
layer: api
status: approved
approved: 2026-11-06
branch: feat/004-features/05-api-feature-steps
base: feat/004-features/04-api-feature-create
covers: [AC-11, AC-12, AC-13, AC-14, AC-27]
---

# 05 — Feature status and steps

## Goal

The write routes the agent uses to record progress: set a feature's status, add a step, and update
a step's status, title or document path. Every state and step type is checked against the
workflow stored on the feature, and a refusal lists what is allowed.

## Contract

Provides, copied from task-breakdown.md:

### Routes (this task's rows)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `PUT /api/projects/{id}/features/{featureId}/status` | `{ "status": "breakdown" }` | `200` Feature | `409 invalid_status` (problem has `allowed`: the workflow's states) |
| `POST /api/projects/{id}/features/{featureId}/steps` | `{ "type": "spec", "title": "Spec: 01-api-x", "documentPath": "feature/004-features/tasks/01-api-x/spec.md" }` (`documentPath` optional) | `201` Step | `409 invalid_step_type` (`allowed`: step keys), `400 invalid_step` (blank or long title, path not under `folder`) |
| `PATCH /api/projects/{id}/features/{featureId}/steps/{stepId}` | any of `status`, `title`, `documentPath` | `200` Step | `404 step_not_found`, `409 invalid_status` (`allowed`: the type's states), `400 invalid_step` |

There is no rename, archive or delete route for features or steps. Status and step writes don't
need the project to be unarchived (the agent may record a finished state after archive).

On every route: `404 project_not_found`, `404 feature_not_found` (no such feature, or it belongs
to another project), `400 invalid_request` (non-numeric id, unreadable body, missing required
field). Step is the step object inside the Feature JSON (task 03): `id`, `position`, `type`,
`typeTitle`, `title`, `documentPath`, `status`, `states`, `createdAt`, `updatedAt`.

## Files

Main code under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `service/FeatureProgress.kt` — new: `@Service` with `setStatus`, `addStep`, `updateStep`.
- `dto/FeatureDtos.kt` — change: `SetStatusRequest(val status: String? = null)`,
  `AddStepRequest(val type: String? = null, val title: String? = null, val documentPath: String? = null)`,
  `UpdateStepRequest(val status: String? = null, val title: String? = null, val documentPath: String? = null)`;
  `FeatureStepResponse.from(step, workflow)` reused from 03.
- `controller/FeatureController.kt` — change: the three mappings.
- `controller/FeatureExceptionHandler.kt` — change: the new codes.
- `exception/FeatureExceptions.kt` — change: `InvalidStatusException(status, allowed)`,
  `InvalidStepTypeException(type, allowed)`, `InvalidStepException(message)`,
  `StepNotFoundException`.

Tests under `api/src/test/kotlin/com/toptier/custom/feature/`: `FeatureProgressTests.kt` (new),
`FeatureControllerTests.kt` (change).

## Steps

1. **`FeatureProgress`** injects `FeatureService` (for `load` and `view`), `FeatureRepository`,
   `FeatureStepRepository`, `WorkflowCodec`, `Clock`. Methods are `@Transactional` (no git here).
   The project's archived flag is not checked.
   - `setStatus(projectId, featureId, status: String?): FeatureView`:
     `feature = featureService.load(...)`; `workflow = codec.read(feature.workflow)`;
     `status?.trim()` not in `workflow.featureStates` → `InvalidStatusException(status,
     workflow.featureStates)`; set `status`, `updatedAt = clock.instant()`, save; return
     `featureService.view(feature)` (AC-11). A missing `status` is the same refusal.
   - `addStep(projectId, featureId, request): Pair<FeatureStep, Workflow>`:
     - `type` not a key of `workflow.steps` → `InvalidStepTypeException(type, keys)` (AC-12);
     - `title` trimmed, 1–200 → else `InvalidStepException("Give the step a title of up to 200 characters.")`;
     - `documentPath` (optional) via `checkPath(feature, path)`;
     - `position = (steps.maxPosition(featureId) ?: 0) + 1`; `status = step.states.first()`;
       `createdAt = updatedAt = now`; `saveAndFlush`; bump `feature.updatedAt` (AC-12, AC-14).
       Same type twice is allowed (AC-14).
   - `updateStep(projectId, featureId, stepId, request)`:
     - `steps.findByIdAndFeatureId(stepId, featureId)` or `StepNotFoundException()`;
     - nothing given → `InvalidStepException("Send a status, title or documentPath to change.")`;
     - `status` given and not in that type's `states` → `InvalidStatusException(status, states)`;
     - `title` given → same rule as add; `documentPath` given: `""` clears it, else `checkPath`;
     - set `updatedAt` on the step and the feature (AC-13).
   - `checkPath(feature, path)`: trimmed; ≤ 300; relative (no leading `/`), no `\`, no `.` or
     `..` segment, no empty segment, and starts with `"${feature.folder}/"` → else
     `InvalidStepException("documentPath must be a file inside ${feature.folder}/.")`.
   - The feature's `updatedAt` moves with every successful write (so the app's "last updated"
     reflects step changes too).
2. **Controller:**
   - `PUT "/{featureId}/status"` → `200 FeatureResponse`;
   - `POST "/{featureId}/steps"` → `201 FeatureStepResponse`;
   - `PATCH "/{featureId}/steps/{stepId}"` → `200 FeatureStepResponse`.
3. **Handler additions:**

   | Exception | Response |
   | --------- | -------- |
   | `InvalidStatusException` | `409 invalid_status`, "\"<status>\" isn't one of this workflow's states: <a, b, c>.", `setProperty("allowed", allowed)` |
   | `InvalidStepTypeException` | `409 invalid_step_type`, "\"<type>\" isn't a step in this feature's workflow: <keys>.", `allowed` |
   | `InvalidStepException` | `400 invalid_step` |
   | `StepNotFoundException` | `404 step_not_found`, "Step not found." |

## Tests

- `FeatureProgressTests` (`@DataJpaTest`, feature rows inserted directly with the contract's
  workflow; `MutableClock`):
  - `setStatus("breakdown")` changes the status and moves `updatedAt` (AC-11);
    `setStatus("shipped")` → `InvalidStatusException` with `allowed` equal to `featureStates`, and
    the row is unchanged (AC-11);
  - `addStep("requirements", "Requirements", "feature/004-features/requirements.md")` → position
    1, status `draft`; a second `addStep("spec", …)` → position 2; a third of type `spec` →
    position 3, both `spec` steps listed by `featureService.get` (AC-12, AC-14);
  - `addStep("plan", …)` → `InvalidStepTypeException` listing the three keys (AC-12);
  - blank and 201-character titles, and `documentPath` values `"/etc/passwd"`,
    `"feature/other/x.md"`, `"feature/004-features/../x.md"`, `"feature/004-features"` →
    `InvalidStepException` (AC-12);
  - `updateStep(status = "approved")` works; `status = "done"` → `InvalidStatusException` with
    `allowed == ["draft", "approved"]`; title and `documentPath` change; `documentPath = ""` clears
    it (AC-13);
  - another feature's step id → `StepNotFoundException`;
  - an archived project's feature still accepts `setStatus`.
- `FeatureControllerTests` additions (`@MockitoBean FeatureProgress`):
  - each route answers `401` without a token (AC-27);
  - `PUT` → `200` Feature; `POST` → `201` Step with `typeTitle` and `states`; `PATCH` → `200`
    Step (AC-11–AC-13);
  - `invalid_status` → `409` with `allowed` in the body; `invalid_step_type` → `409` with
    `allowed`; `invalid_step` → `400`; `step_not_found`, `feature_not_found` → `404`;
    `/steps/abc` → `400 invalid_request`.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.
- The app starts against MySQL; on a feature created in 04, `curl` `PUT …/status` with an allowed
  and a disallowed state and see `200` and `409`.

## Out of scope

- Checking that a status change follows the order of `featureStates` (any listed state is
  accepted).
- Approval rules: the agent's instructions and the project's `rules.md` own them (08).
- The chat (06), the agent tools that call these routes (08), the app display (09).
