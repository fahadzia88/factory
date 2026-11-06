---
feature: 002-pull-requests
task: 01-api-github-pull-client
layer: api
status: approved
approved: 2026-11-05
branch: feat/002-pull-requests/01-api-github-pull-client
base: feat/002-pull-requests/00-docs
covers: [AC-17, AC-18]
---

# 01 — GitHub pull request client

## Goal

A `GitHubPullRequestClient` that reads open pull requests, one pull request, and a pull request's
files from GitHub, with paging from the `Link` header and every GitHub failure turned into a typed
exception. No HTTP routes yet.

## Contract

From task-breakdown.md, "api → GitHub":

- Reuses the `gitHubRestClient` bean (base URL, token, 5 s timeouts) from feature 001.
- `GET /repos/{owner}/{repo}/pulls?state=open&sort=created&direction=desc&per_page=20&page={page}`
- `GET /repos/{owner}/{repo}/pulls/{number}`
- `GET /repos/{owner}/{repo}/pulls/{number}/files?per_page=30&page={page}`
- The token needs "Pull requests: read" as well as "Metadata: read".

Failure cases it must tell apart (task 02 maps them to the error table):

- `404` → not found;
- `401`, a `403` that isn't a rate limit, `5xx`, or a connection failure → refused or error;
- `429`, or `403` with `x-ratelimit-remaining: 0` → rate-limited;
- blank `app.github.token` → not configured (no call is made);
- no answer within `app.github.timeout` → timeout.

## Files

All under `api/src/main/kotlin/com/toptier/custom/github/`:

- `service/GitHubPullRequestClient.kt` — new: `listOpen(owner, repo, page)`, `get(owner, repo,
  number)`, `listFiles(owner, repo, number, page)`.
- `service/GitHubModels.kt` — new: GitHub's JSON shapes (`GitHubPull`, `GitHubUser`,
  `GitHubRef`, `GitHubFile`) and `GitHubPage<T>(items, hasNext)`.
- `exception/GitHubExceptions.kt` — new: sealed `GitHubException` with `GitHubNotFoundException`,
  `GitHubRefusedException(status)`, `GitHubRateLimitedException`,
  `GitHubNotConfiguredException`, `GitHubTimeoutException`, `GitHubUnreachableException`.
- `config/GitHubProperties.kt` — change: KDoc on `token` says it reads repositories and pull
  requests ("Metadata: read" and "Pull requests: read"); KDoc on `timeout` says it applies to
  every GitHub call.
- `api/src/main/resources/application.properties` — change (shared file; the user confirms the
  edit): the comment above `app.github.token` names both permissions.
- `api/src/test/kotlin/com/toptier/custom/github/GitHubPullRequestClientTests.kt` — new.

## Steps

1. **Models.** Data classes with `@JsonProperty` for snake_case fields. Unknown fields are ignored
   (Boot's default `FAIL_ON_UNKNOWN_PROPERTIES=false`; confirm with a fixture holding extra
   fields).
   - `GitHubPull`: `number`, `title`, `state` (`open`/`closed`), `draft`, `user: GitHubUser?`,
     `head: GitHubRef`, `base: GitHubRef`, `body: String?`, `created_at`, `updated_at`,
     `merged_at: Instant?`, `closed_at: Instant?`, `html_url`. The detail-only counts are
     nullable, because the list endpoint doesn't send them: `commits`, `additions`, `deletions`,
     `changed_files`.
   - `GitHubUser`: `login`, `avatar_url`.
   - `GitHubRef`: `ref`, `label`, and `repo: GitHubRefRepo?` with `full_name` (a deleted fork
     sends `null`).
   - `GitHubFile`: `filename`, `previous_filename?`, `status`, `additions`, `deletions`,
     `patch?`, `blob_url`.
2. **Paging.** `hasNext` is true when the response's `Link` header contains `rel="next"`. Read it
   with `retrieve().toEntity(...)` and look at the headers. Use a
   `ParameterizedTypeReference<List<…>>` for the list bodies.
3. **Client.**
   - Before any call, throw `GitHubNotConfiguredException` when `gitHubProperties.token` is
     blank.
   - Build URIs with templates and query params (`uriBuilder`), not string concatenation.
   - Wrap each call in one `call { … }` helper that maps failures. Catch the specific
     exceptions first, then `RestClientResponseException`, then `ResourceAccessException`:
     - `HttpClientErrorException.NotFound` → `GitHubNotFoundException`;
     - `429`, or `403` with header `x-ratelimit-remaining` = `0` → `GitHubRateLimitedException`;
     - any other `401`/`403`/`4xx`/`5xx` → `GitHubRefusedException(status)`. Log `401` as a warning
       ("GitHub rejected app.github.token") and the others at info;
     - `ResourceAccessException` whose cause chain has a `SocketTimeoutException` →
       `GitHubTimeoutException`; any other → `GitHubUnreachableException`.
   - Never log the token.
4. **Properties comment and KDoc** as listed under Files.

## Tests

`GitHubPullRequestClientTests` uses `MockRestServiceServer.bindTo(GitHubConfig.configure(...))`,
like `GitHubRepositoryCheckerTests`. Its fixtures are trimmed copies of real GitHub responses,
extra fields included.

- `listOpen`:
  - sends `state=open&sort=created&direction=desc&per_page=20&page=2`, the bearer token and the
    GitHub accept header;
  - parses two pulls, one with a fork head and one with `user: null`.
- `hasNext` is true with `Link: <…&page=3>; rel="next", <…>; rel="last"` and false without a
  `Link` header (AC-3 support).
- `get` parses the detail counts, `body`, `merged_at` and `closed_at`.
- `listFiles` sends `per_page=30&page=1` and parses a renamed file (`previous_filename`) and a
  binary file with no `patch` (AC-10 and AC-12 support).
- `404` → `GitHubNotFoundException` (AC-17).
- `401` and plain `403` → `GitHubRefusedException`; `500` → `GitHubRefusedException` (AC-18).
- `429`, and `403` with `x-ratelimit-remaining: 0` → `GitHubRateLimitedException` (AC-18).
- A `SocketTimeoutException` → `GitHubTimeoutException`; `ConnectException` →
  `GitHubUnreachableException` (AC-18).
- A blank token → `GitHubNotConfiguredException`, with no request sent (AC-18).

## Checks

- `docker run --rm -v "$PWD":/workspace -w /workspace -v skeleton-gradle-cache:/home/gradle/.gradle gradle:jdk25 ./gradlew test --no-daemon -q`
  in `api/`, with the `api` container stopped first so `bootRun` doesn't hold the Gradle lock.

## Out of scope

- Routes, DTOs and error mapping (task 02).
- Caching or ETags.
- `GitHubRepositoryChecker` keeps its own error handling; don't refactor it here.
