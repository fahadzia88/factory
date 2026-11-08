---
feature: 005-probe
task: 02-api-feature-file-routes
layer: api
status: draft
approved:
branch: feat/005-probe/02-api-feature-file-routes
base: feat/005-probe/01-api-feature-files
covers: [AC-1, AC-2, AC-8, AC-10, AC-11, AC-15]
---

# 02 — Feature file routes

## Goal

Expose `FeatureFiles` as two read-only routes: the folder tree and one markdown file's content,
with the DTOs and the error codes from the breakdown.

## Contract

Provides, copied from task-breakdown.md:

Both routes need `Authorization: Bearer <API_TOKEN>` (`401` otherwise) and return errors as
`ProblemDetail` (`application/problem+json`) with a `code` property the app switches on and a
`detail` it can show as is. They share the feature routes' codes: `404 project_not_found`,
`404 feature_not_found` (no such feature, or it belongs to another project), `400 invalid_request`
(non-numeric id, missing `path`). Both are `GET`; no route writes, renames or deletes a file.

### `GET /api/projects/{id}/features/{featureId}/files`

- Request: none.
- Response `200`:

  ```json
  {
    "root": "feature/005-probe",
    "entries": [
      { "name": "tasks", "path": "tasks", "type": "directory", "children": [
        { "name": "01-api-x", "path": "tasks/01-api-x", "type": "directory", "children": [
          { "name": "spec.md", "path": "tasks/01-api-x/spec.md", "type": "file", "markdown": true }
        ] }
      ] },
      { "name": "requirements.md", "path": "requirements.md", "type": "file", "markdown": true },
      { "name": "diagram.png", "path": "diagram.png", "type": "file", "markdown": false }
    ]
  }
  ```

- `markdown` is true when the name ends in `.md` (case-insensitive). Directories have `children`
  (possibly empty); files don't.
- The folder is empty or doesn't exist yet: `200` with `"entries": []`.
- The tree is capped at 500 entries and 8 levels deep.
- Errors: `409 worktree_missing`.

### `GET /api/projects/{id}/features/{featureId}/files/content?path=<path>`

- Request: query `path` (required, relative to the root).
- Response `200`: `{ "path": "requirements.md", "content": "…", "size": 1234, "updatedAt": "2026-11-08T04:32:15Z" }`.
- Errors: `400 invalid_path`, `404 file_not_found`, `415 unsupported_file_type`,
  `413 file_too_large`, `409 worktree_missing`.

## Files

Under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `dto/FeatureFileDtos.kt` — new: `FeatureFileTreeResponse(root, entries)`,
  `FeatureFileEntryResponse(name, path, type, markdown, children)` and
  `FeatureFileContentResponse(path, content, size, updatedAt)`, each with `from(...)` like
  `FeatureDtos.kt`.
- `controller/FeatureFilesController.kt` — new: both routes.

Tests: `api/src/test/kotlin/com/toptier/custom/feature/FeatureFilesControllerTests.kt` (new).

## Steps

1. **DTOs.** `FeatureFileEntryResponse` is annotated `@JsonInclude(JsonInclude.Include.NON_NULL)`
   (`com.fasterxml.jackson.annotation`, as the api rules note for Jackson 3) so that a file has no
   `children` and a directory has no `markdown`. `type` is `"directory"` or `"file"`; `markdown` is
   set for files only; `children` for directories only (possibly empty, so use an empty list, not
   `null`). `updatedAt` is an `Instant` (ISO-8601, as the feature DTOs).
2. **Controller** `FeatureFilesController(featureFiles: FeatureFiles)`, `@RestController`,
   `@RequestMapping("/api/projects/{id}/features/{featureId}/files")`, in the `controller` package
   so `FeatureExceptionHandler` (`basePackageClasses = [FeatureController::class]`) already covers
   it:
   - `@GetMapping` `tree(@PathVariable id: Long, @PathVariable featureId: Long)` →
     `FeatureFileTreeResponse.from(featureFiles.tree(id, featureId))`;
   - `@GetMapping("/content")` `content(@PathVariable id, @PathVariable featureId, @RequestParam path: String)`
     → `FeatureFileContentResponse.from(featureFiles.read(id, featureId, path))`.
   A missing `path` is already `400 invalid_request` through the handler's
   `MissingServletRequestParameterException` branch.
3. No change to `SecurityConfig`: both routes are protected like every `/api` route (AC-15).
4. KDoc on the class: read-only, never accepts a body.

## Tests

`FeatureFilesControllerTests` — `@WebMvcTest(FeatureFilesController::class)`,
`@Import(SecurityConfig::class, FeatureExceptionHandler::class)`,
`@EnableConfigurationProperties(SecurityProperties::class)`,
`@TestPropertySource(properties = ["app.security.api-token=test-token"])`,
`@MockitoBean FeatureFiles`; helpers `auth()`/`problem()` as in `FeatureControllerTests`:

- both routes answer `401` without a token (AC-15);
- tree answers `200` with the nested JSON; a directory has `children` and no `markdown`; a file has
  `markdown` and no `children`; `root` is the folder (AC-1);
- content answers `200` with `path`, `content`, `size`, `updatedAt` (AC-2);
- an empty tree answers `200` with `"entries": []`;
- error mapping from the service's exceptions: `WorktreeMissingException` → `409 worktree_missing`
  on both routes with the message as `detail` (AC-10); `InvalidFilePathException` → `400
  invalid_path` (AC-11); `FeatureFileNotFoundException` → `404 file_not_found` (AC-8);
  `UnsupportedFileTypeException` → `415 unsupported_file_type`; `FileTooLargeException` → `413
  file_too_large`; `FeatureNotFoundException` → `404 feature_not_found`; `ProjectNotFoundException`
  → `404 project_not_found`;
- `/features/abc/files` → `400 invalid_request`; `/files/content` with no `path` → `400
  invalid_request`;
- `POST`, `PUT`, `PATCH` and `DELETE` on both paths → `405` (read-only).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`
- The app starts against MySQL; with a feature whose worktree exists,
  `curl -H 'authorization: Bearer 123456' localhost:8080/api/projects/<id>/features/<fid>/files`
  lists its folder and `…/files/content?path=requirements.md` returns the file; `?path=../rules.md`
  answers `400 invalid_path`.

## Out of scope

- The service and path guard (task 01).
- Any app change (tasks 03–05); a `DELETE`/write route; reading other folders of the worktree.
