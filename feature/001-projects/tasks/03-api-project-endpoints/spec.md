---
feature: 001-projects
task: 03-api-project-endpoints
layer: api
status: approved
approved: 2026-11-04
branch: feat/001-projects/03-api-project-endpoints
base: feat/001-projects/02-api-project-service
covers: [AC-2, AC-4, AC-5, AC-19, AC-20, AC-22, AC-23]
---

# 03 — Project endpoints

## Goal

Expose `ProjectService` over HTTP: create, get, update, archive and restore under
`/api/projects`, with the Project JSON and `ProblemDetail` errors that carry field errors.

## Contract

From task-breakdown.md. Every `/api/projects` route needs `Authorization: Bearer <API_TOKEN>`
(401 otherwise) and returns errors as `ProblemDetail` (`application/problem+json`).

**Project (JSON)**

```json
{
  "id": 42,
  "name": "Factory",
  "repositoryUrl": "https://github.com/acme/factory",
  "description": "Builds the apps.",
  "status": "planning",
  "archived": false,
  "repositoryVerified": true,
  "createdAt": "2026-11-04T10:15:30Z",
  "updatedAt": "2026-11-04T10:15:30Z"
}
```

- `id`: number.
- `description`: string or `null`.
- `status`: one of `planning`, `active`, `maintenance`, `retired`.
- `repositoryVerified`: `false` when GitHub couldn't be asked.
- `createdAt` and `updatedAt`: ISO-8601 UTC timestamps.

**Field errors**

```json
{ "status": 400, "title": "Bad Request", "detail": "The project couldn't be saved.",
  "errors": { "name": "Enter a name.", "repositoryUrl": "Repository not found on GitHub." } }
```

The field keys are `name`, `repositoryUrl`, `description` and `status`.

**Routes**

- `GET /api/projects/{id}` → `200` Project, archived or not. `404` when no project has that id.
- `POST /api/projects` with `{ name, repositoryUrl, description?, status? }` → `201` Project plus
  a `Location: /api/projects/{id}` header.
  - `400` with field errors for invalid input or a repository GitHub reports not found.
  - `409` with field errors for a duplicate name or repository URL.
- `PUT /api/projects/{id}`: the same fields, with `status` required → `200` Project.
  - `400` and `409` with field errors;
  - `404`;
  - `409` without field errors when archived ("Archived projects can't be edited. Restore it
    first.").
- `POST /api/projects/{id}/archive` and `POST /api/projects/{id}/restore`: no body → `200`
  Project. Idempotent, and `status` doesn't change. `404` for an unknown id.
- No `DELETE` route (`405`).

## Files

- `api/src/main/kotlin/com/toptier/custom/project/dto/ProjectDtos.kt` — new:
  `ProjectRequest` (all fields nullable, so the service reports missing ones) and
  `ProjectResponse` with `from(project)`.
- `api/src/main/kotlin/com/toptier/custom/project/controller/ProjectController.kt` — new.
- `api/src/main/kotlin/com/toptier/custom/project/controller/ProjectExceptionHandler.kt` — new:
  `@RestControllerAdvice(basePackageClasses = [ProjectController::class])`.
- `api/src/test/kotlin/com/toptier/custom/project/ProjectControllerTests.kt` — new.

## Steps

1. **`ProjectResponse`**: `id: Long`, `name`, `repositoryUrl`, `description: String?`,
   `status: String` (`project.status.json`), `archived`, `repositoryVerified`, and
   `createdAt: Instant` and `updatedAt: Instant`. Jackson writes `Instant` as ISO-8601; check
   that the existing Jackson 3 setup doesn't write numbers, and if it does, annotate the fields
   with `@JsonFormat(shape = STRING)`.
2. **`ProjectRequest(name: String?, repositoryUrl: String?, description: String?, status: String?)`**
   maps to `ProjectInput`.
3. **`@RestController @RequestMapping("/api/projects") class ProjectController(service)`**:
   - `@GetMapping("/{id}")` → `ProjectResponse.from(service.get(id))`.
   - `@PostMapping` → `ResponseEntity.created(URI("/api/projects/${p.id}")).body(...)`.
   - `@PutMapping("/{id}")` → `service.update(id, input)`.
   - `@PostMapping("/{id}/archive")` and `@PostMapping("/{id}/restore")`.
   - A non-numeric `{id}` gives Spring's 400, which is fine. Don't add a `@DeleteMapping`; Spring
     answers `405` for `DELETE` on `/{id}`.
   - KDoc each handler with the route's purpose.
4. **`ProjectExceptionHandler`**, following `AgentExceptionHandler`:
   - `ProjectValidationException` → 400 with detail "The project couldn't be saved." and
     `setProperty("errors", ex.errors)`.
   - `ProjectConflictException` → 409, with the same detail and `errors`.
   - `ProjectArchivedException` → 409 with detail `ex.message` and no `errors`.
   - `ProjectNotFoundException` → 404 with detail "Project not found.".
   - Malformed JSON (`HttpMessageNotReadableException`) → 400 "The request body isn't valid JSON."

   Log not-found at info and other errors at debug: they're the caller's input, not failures.
5. **Security:** nothing to change. `SecurityConfig` already requires the token for every route
   except `/error`.

## Tests

`ProjectControllerTests`: `@WebMvcTest(ProjectController::class)` importing `SecurityConfig` and
`ProjectExceptionHandler`, with `@EnableConfigurationProperties(SecurityProperties::class)`,
`app.security.api-token=test-token`, and a `@MockitoBean` `ProjectService`.

- Every route without a token → 401 (AC-2).
- `POST` valid → 201, a `Location` header, and every JSON field, including `status` `"planning"`,
  `repositoryVerified`, and ISO-8601 `createdAt` (AC-4).
- `POST` where the service throws `ProjectValidationException(mapOf("name" to "Enter a name."))` →
  400 `application/problem+json` with `$.errors.name` (AC-5).
- `POST` where the service throws `ProjectConflictException` → 409 with `$.errors.repositoryUrl`
  (AC-6, AC-7, via the HTTP mapping).
- `PUT` on an archived project (`ProjectArchivedException`) → 409 with the detail and no `errors`
  (AC-20).
- `POST /{id}/archive` and `/restore` → 200 with `archived` true/false (AC-19, AC-21).
- `GET /{id}` unknown → 404 problem detail (AC-23).
- `DELETE /api/projects/1` → 405 (AC-22).
- Malformed JSON body → 400.

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`.
- Against MySQL with the app running (`bootRun`), curl one create, get, update, archive and
  restore with `Authorization: Bearer 123456`. Stop what you started.

## Out of scope

- `GET /api/projects` and `/summary` (task 04).
- Any app or agent code.
