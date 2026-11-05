---
feature: 001-projects
task: 01-api-github-check
layer: api
status: approved
approved: 2026-11-04
branch: feat/001-projects/01-api-github-check
base: feat/001-projects/00-docs
covers: [AC-8, AC-9]
---

# 01 — GitHub repository check

## Goal

Add a `github` package that answers one question for the project service: does this GitHub
repository exist and can our token see it? The answer is `FOUND`, `NOT_FOUND` or `UNAVAILABLE`.
Nothing calls it yet.

## Contract

From task-breakdown.md, "api → GitHub (`app.github.*`)":

- `GET https://api.github.com/repos/{owner}/{repo}` with `Authorization: Bearer <app.github.token>`,
  connect and read timeouts of 5 s.
- `200` means found. `404` means not found, which also covers a private repo the token can't see.
- Unverified: `403` or `429` from rate limiting, `401` (bad token, logged as a warning), `5xx`, a
  timeout, an I/O error, or a blank `app.github.token` (no call is made).
- Config: `app.github.token=${GITHUB_TOKEN:}` and `app.github.base-url=https://api.github.com`.

## Files

- `api/src/main/kotlin/com/toptier/custom/github/config/GitHubProperties.kt` — new: typed
  `@ConfigurationProperties(prefix = "app.github")`.
- `api/src/main/kotlin/com/toptier/custom/github/config/GitHubConfig.kt` — new: the
  `gitHubRestClient` bean.
- `api/src/main/kotlin/com/toptier/custom/github/service/GitHubRepositoryChecker.kt` — new: the
  check and its `RepositoryCheck` result enum.
- `api/src/main/resources/application.properties` — change: add the two `app.github.*`
  properties with comments.
- `api/src/test/kotlin/com/toptier/custom/github/GitHubRepositoryCheckerTests.kt` — new.

## Steps

1. **`GitHubProperties`** (data class, validated in `init` like `AgentProperties`):
   - `token: String = ""`: blank means "don't check";
   - `baseUrl: String = "https://api.github.com"`: must not be blank;
   - `timeout: Duration = Duration.ofSeconds(5)`: must be positive.

   KDoc each field. Add to `application.properties`, each with a comment saying what it's for:

   ```properties
   # Read-only GitHub token for checking project repositories exist (Metadata: read); blank skips the check
   app.github.token=${GITHUB_TOKEN:}
   app.github.base-url=${GITHUB_API_URL:https://api.github.com}
   ```
2. **`GitHubConfig.gitHubRestClient(builder: RestClient.Builder, props: GitHubProperties)`**:
   - base URL `props.baseUrl`;
   - default headers `Accept: application/vnd.github+json` and `X-GitHub-Api-Version: 2022-11-28`;
   - `Authorization: Bearer <token>` only when the token isn't blank;
   - a `SimpleClientHttpRequestFactory` with connect and read timeouts of `props.timeout`, as in
     `AgentConfig`.
3. **`enum class RepositoryCheck { FOUND, NOT_FOUND, UNAVAILABLE }`**, with KDoc saying
   `UNAVAILABLE` means "couldn't ask GitHub; save as unverified".
4. **`@Service class GitHubRepositoryChecker(gitHubRestClient: RestClient, props: GitHubProperties)`**
   with `fun check(owner: String, repo: String): RepositoryCheck`:
   - Blank token: return `UNAVAILABLE` without a request, and log at debug.
   - Otherwise `GET /repos/{owner}/{repo}` (URI template variables, so they're encoded) using
     `.retrieve().toBodilessEntity()` inside `try`.
   - `2xx` → `FOUND`.
   - `HttpClientErrorException.NotFound` → `NOT_FOUND`.
   - `401` → `UNAVAILABLE`, logged as a warning: "GitHub rejected app.github.token".
   - `403` or `429` → `UNAVAILABLE`, logged at info as rate-limited.
   - Any other `RestClientResponseException`, `ResourceAccessException` (timeouts, I/O), or other
     `RestClientException` → `UNAVAILABLE`, logged as a warning without the token.
   - Never include the token or the response body in a log or exception message.
5. Owner and repo come from the caller already parsed; this class doesn't parse URLs (task 02
   does).

## Tests

`GitHubRepositoryCheckerTests`, using `MockRestServiceServer.bindTo(builder)` on a
`RestClient.Builder` built the way `GitHubConfig` builds it (with the token header):

- `200` with a repo JSON fixture → `FOUND`. The request carries `Authorization: Bearer test-token`
  and goes to `/repos/acme/factory` (AC-8, AC-9).
- `404` → `NOT_FOUND` (AC-8).
- `403` with rate-limit headers (`x-ratelimit-remaining: 0`) → `UNAVAILABLE` (AC-9).
- `429` → `UNAVAILABLE` (AC-9).
- `401` → `UNAVAILABLE` (AC-9).
- `502` → `UNAVAILABLE` (AC-9).
- A `ResourceAccessException` thrown by the server (`withException(SocketTimeoutException())`) →
  `UNAVAILABLE` (AC-9).
- Blank token → `UNAVAILABLE`, and the mock server expects no request (AC-9).
- Owner and repo with characters that need encoding are sent encoded (for example `my.repo`
  stays `my.repo`, a space becomes `%20`).

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`, or the throwaway
  `gradle:jdk25` container from `api/CLAUDE.md`.
- The app still starts with `GITHUB_TOKEN` unset (the properties default to blank).

## Out of scope

- Parsing or validating repository URLs, and deciding what to do with the result (task 02).
- Passing the token through `docker-compose.yml` (task 05).
- Caching results, or reading anything else from GitHub.
