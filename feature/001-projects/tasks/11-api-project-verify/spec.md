---
feature: 001-projects
task: 11-api-project-verify
layer: api
status: approved
approved: 2026-11-05
branch: feat/001-projects/11-api-project-verify
base: feat/001-projects/10-app-dashboard-nav
covers: [AC-31]
---

# 11 — Verify a project's repository again

## Goal

Add `POST /api/projects/{id}/verify`, which runs the GitHub check again for an unverified project
without changing anything else.

## Contract

Provides, from task-breakdown.md:

- `POST /api/projects/{id}/verify`, no body → `200` Project. It sets `repositoryVerified: true`
  when GitHub finds the repository, and leaves `updatedAt` unchanged. An already verified project
  answers `200` as is, with no GitHub call.
- `404` for an unknown id; `409` when archived (same message as `PUT`); `422` "Repository not
  found on GitHub. Edit the project to fix its URL."; `503` "GitHub couldn't be reached. Try again
  later." The project stays unverified on `422` and `503`.

## Files

- `project/exception/ProjectExceptions.kt` — change: add `RepositoryNotFoundException(id)` and
  `GitHubUnavailableException(id)` to the sealed `ProjectException`.
- `project/service/ProjectService.kt` — change: add `verify(id)`.
- `project/controller/ProjectController.kt` — change: add `@PostMapping("/{id}/verify")`.
- `project/controller/ProjectExceptionHandler.kt` — change: map the two new exceptions.
- `ProjectServiceTests.kt`, `ProjectControllerTests.kt` — change: new tests.

## Steps

1. **Exceptions.** `RepositoryNotFoundException` has the message "Repository not found on GitHub.
   Edit the project to fix its URL." and `GitHubUnavailableException` has "GitHub couldn't be
   reached. Try again later."
2. **`ProjectService.verify(id)`**:
   - `get(id)`; if archived, throw `ProjectArchivedException`;
   - if already verified, return it;
   - parse the stored URL with `GitHubRepositoryUrl.parse`, then `checker.check(owner, repo)`:
     `FOUND` → set `repositoryVerified = true` and `save` (no `updatedAt` change); `NOT_FOUND` →
     throw `RepositoryNotFoundException`; `UNAVAILABLE` → throw `GitHubUnavailableException`.
3. **Controller:** `fun verify(@PathVariable id: Long): ProjectResponse`, with a KDoc line.
4. **Handler:** `RepositoryNotFoundException` → `422` (`HttpStatus.UNPROCESSABLE_CONTENT`) and
   `GitHubUnavailableException` → `503`, both `ProblemDetail.forStatusAndDetail` with the
   exception's message. Log `503` at info.

## Tests

- `ProjectServiceTests` (AC-31):
  - unverified + found → verified, `updatedAt` unchanged, other fields unchanged;
  - unverified + not found → `RepositoryNotFoundException`, still unverified;
  - unverified + unavailable → `GitHubUnavailableException`, still unverified;
  - verified → returned as is, the checker isn't called;
  - archived → `ProjectArchivedException`, the checker isn't called;
  - unknown id → `ProjectNotFoundException`.
- `ProjectControllerTests`: `200` with `repositoryVerified: true`, `422` and `503` with their
  `detail`, `409` archived, `404` unknown, `401` without a token.

## Checks

- `./gradlew test` in the `gradle:jdk25` container (stop the api container first).

## Out of scope

- The app button (task 12). Any automatic or scheduled re-check.
