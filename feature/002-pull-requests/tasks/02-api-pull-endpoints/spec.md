---
feature: 002-pull-requests
task: 02-api-pull-endpoints
layer: api
status: approved
approved: 2026-11-05
branch: feat/002-pull-requests/02-api-pull-endpoints
base: feat/002-pull-requests/01-api-github-pull-client
covers: [AC-1, AC-3, AC-4, AC-5, AC-7, AC-9, AC-10, AC-14, AC-15, AC-16, AC-17, AC-18]
---

# 02 — Pull request endpoints

## Goal

Serve a project's open pull requests, one pull request, and its files under
`/api/projects/{id}/pulls`. Errors are `ProblemDetail`s with the `code`s the app switches on.

## Contract

Provides, copied from task-breakdown.md:

- **PullRequestSummary:**
  - `number`, `title`;
  - `state`: `open`, `closed` or `merged` (`merged` when `merged_at` is set);
  - `draft`;
  - `author`: `{ login, avatarUrl }`, or `null`;
  - `headRef`: GitHub's `head.label` when the head is in a fork, else `head.ref`;
  - `baseRef`;
  - `createdAt` and `updatedAt`: ISO-8601 UTC;
  - `htmlUrl`.
- **PullRequest:** the summary plus:
  - `body`: raw Markdown, or `null` when empty;
  - `commits`, `additions`, `deletions`, `changedFiles`;
  - `mergedAt` and `closedAt`, each nullable.
- **PullRequestFile:**
  - `filename`;
  - `previousFilename`: set for renamed and copied files, else `null`;
  - `status`: GitHub's value;
  - `additions`, `deletions`;
  - `patch`, or `null`;
  - `htmlUrl`: GitHub's `blob_url`.
- **Page:** `{ items, page, size, hasNext }`.
- **Routes:**
  - `GET /api/projects/{id}/pulls?page=`: size 20, open only, newest created first. Works for
    archived and unverified projects.
  - `GET /api/projects/{id}/pulls/{number}`: a PullRequest, in any state.
  - `GET /api/projects/{id}/pulls/{number}/files?page=`: size 30.
- **Errors,** each with a `code` property:
  - `400 invalid_request`: "Invalid value for page." or "Invalid value for number.";
  - `404`:
    - `project_not_found`: "Project not found.";
    - `pull_request_not_found`: "Pull request not found.";
    - `repository_not_found`: "GitHub couldn't find this repository, or the API's token can't
      see it.";
  - `502 github_error`:
    - for a `403`: "GitHub refused the request. The API's token may need \"Pull requests: read\"
      on this repository.";
    - otherwise: "GitHub couldn't be reached. Try again.";
  - `502 github_rate_limited`: "GitHub's rate limit was reached. Try again in a few minutes.";
  - `503 github_not_configured`: "The API has no GitHub token configured.";
  - `504 github_timeout`: "GitHub didn't answer in time. Try again."
- **404 disambiguation:** on a GitHub `404` from `/pulls/{number}` or its files, check the
  repository with `GitHubRepositoryChecker`:
  - `NOT_FOUND` → `repository_not_found`;
  - otherwise → `pull_request_not_found`.

  A `404` on the list route is always `repository_not_found`.

## Files

All under `api/src/main/kotlin/com/toptier/custom/pullrequest/`:

- `service/PullRequestService.kt` — new: `list(projectId, page)`, `get(projectId, number)`,
  `files(projectId, number, page)`.
- `dto/PullRequestDtos.kt` — new: `PullRequestSummaryResponse`, `PullRequestResponse`,
  `PullRequestFileResponse`, `PullRequestAuthor`, `PageResponse<T>`, each with a `from(...)`
  mapping from the GitHub models.
- `exception/PullRequestExceptions.kt` — new: `PullRequestNotFoundException`,
  `PullRequestRepositoryNotFoundException`, `InvalidPullRequestParameterException(name)`.
- `controller/PullRequestController.kt` — new.
- `controller/PullRequestExceptionHandler.kt` — new:
  `@RestControllerAdvice(basePackageClasses = [PullRequestController::class])`.
- Tests: `api/src/test/kotlin/com/toptier/custom/pullrequest/PullRequestServiceTests.kt` and
  `PullRequestControllerTests.kt` — new.

## Steps

1. **Service.**
   - Look up the project with `ProjectService.get(id)`. It throws `ProjectNotFoundException`;
     archived and unverified projects are allowed.
   - Parse `repositoryUrl` with `GitHubRepositoryUrl.parse`.
   - Validate `page >= 1` and `number >= 1`, else throw `InvalidPullRequestParameterException`.
   - Call `GitHubPullRequestClient`, and map the results to DTOs:
     - `state = if (mergedAt != null) "merged" else github.state`;
     - `headRef = if (head.repo?.fullName != base.repo?.fullName) head.label else head.ref`, with
       a `null` head repo (a deleted fork) counting as a fork;
     - a blank `body` becomes `null`.
   - On `GitHubNotFoundException`:
     - from `list`, throw `PullRequestRepositoryNotFoundException`;
     - from `get` or `files`, call `checker.check(owner, repo)`: `NOT_FOUND` → the repository
       exception; anything else → `PullRequestNotFoundException`.
   - Mark the service `@Transactional(readOnly = true)` only around the project lookup, or not
     at all: don't hold a transaction open across the GitHub call. Inject `ProjectService`.
2. **Controller.**
   - `@RequestMapping("/api/projects/{id}/pulls")`.
   - `page` is `@RequestParam(required = false) page: Int?`, defaulting to 1 in the service.
   - KDoc on each method.
3. **Handler.** One function builds `ProblemDetail.forStatusAndDetail(status, detail)` and sets
   the `code` property:
   - `ProjectNotFoundException` → `404 project_not_found`. It is thrown from the project
     package, but caught here because this advice is scoped to the pull request controller;
   - the pull request exceptions → their row in the table;
   - `GitHubRefusedException`:
     - status `403` → `502 github_error` with the "Pull requests: read" detail;
     - otherwise → `502 github_error`, "GitHub couldn't be reached. Try again.";
   - `GitHubUnreachableException` → `502 github_error`, "couldn't be reached";
   - `GitHubRateLimitedException` → `502 github_rate_limited`;
   - `GitHubNotConfiguredException` → `503 github_not_configured`;
   - `GitHubTimeoutException` → `504 github_timeout`;
   - `GitHubNotFoundException`, if one escapes → `404 repository_not_found`;
   - `MethodArgumentTypeMismatchException` (for example `page=abc` or `number=abc`) →
     `400 invalid_request`, "Invalid value for <name>."

   Log the 5xx codes at info, with the project id.

## Tests

`PullRequestServiceTests` (plain unit test; `ProjectService`, the client and the checker are
mocked with `@MockitoBean`, or built by hand with Mockito):

- `list`:
  - passes owner, repo and page through;
  - maps a fork head to its label and `user: null` to `author: null`;
  - keeps `hasNext` (AC-1, AC-3).
- Archived and unverified projects list normally (AC-5).
- An empty GitHub page → empty `items`, `hasNext: false` (AC-4).
- `get`:
  - maps the counts and `body`, and turns a blank body into `null`;
  - `merged_at` set → `merged`; `closed` with no `merged_at` → `closed` (AC-7, AC-14).
- `files` maps `previousFilename` and `patch: null` (AC-9, AC-12).
- Unknown project → `ProjectNotFoundException` (AC-16).
- A GitHub `404` on `get`:
  - when the checker says `FOUND` → `PullRequestNotFoundException` (AC-16);
  - when it says `NOT_FOUND` → the repository exception (AC-17).
- A GitHub `404` on `list` → the repository exception, without calling the checker (AC-17).
- `page = 0` or `number = 0` → `InvalidPullRequestParameterException`.

`PullRequestControllerTests` (`@WebMvcTest`, importing `SecurityConfig` and the handler, with
`app.security.api-token=test-token`):

- `401` without a token on all three routes (AC-15).
- `200` JSON shapes for the list (with `hasNext`), the detail and the files, field by field
  (AC-1, AC-7, AC-9).
- `?page=3` reaches the service as 3; no page → 1 (AC-3, AC-10).
- Each exception → its status, `code` and `detail`:
  - `project_not_found`, `pull_request_not_found`, `repository_not_found` (AC-16, AC-17);
  - `github_error` (403 and non-403), `github_rate_limited`, `github_not_configured`,
    `github_timeout` (AC-18).
- `page=abc` → `400 invalid_request`.
- The content type is `application/problem+json`.

## Checks

- `./gradlew test` in the `gradle:jdk25` container, as in task 01.
- The app starts against MySQL (`./gradlew bootRun`).
- With `API_GITHUB_TOKEN` set, curl the list for a real project.
- Without a token, the list answers `503 github_not_configured`.

## Out of scope

- The app (03–05).
- Agent tools.
- Listing closed pull requests.
