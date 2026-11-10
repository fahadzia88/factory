---
feature: 006-feature-implementation
task: 02-api-github-pull-client
layer: api
status: approved
approved: 2026-11-10
branch: feat/006-feature-implementation/02-api-github-pull-client
base: feat/006-feature-implementation/01-infra-drop-agent-github-token
covers: [AC-13, AC-14, AC-15, AC-16]
---

# 02 — GitHub pull request client on `GIT_TOKEN`

## Goal

A GitHub client that creates, retargets and reads pull requests and their check runs with the
API's `GIT_TOKEN`, plus the pure builders for a PR's title and body. Nothing calls it yet.

## Contract

From task-breakdown.md, "GitHub (api ↔ GitHub), `GIT_TOKEN`":

| Call | Used by |
| ---- | ------- |
| `POST /repos/{o}/{r}/pulls` `{title, head, base, body}` | pull |
| `PATCH /repos/{o}/{r}/pulls/{n}` `{base}` | restack |
| `GET /repos/{o}/{r}/pulls/{n}` | all reads (state, head SHA, merged) |
| `GET /repos/{o}/{r}/commits/{sha}/check-runs` (up to 100) | all reads |

`pull` in the Task JSON: `number`, `title`, `state` (`open`/`closed`/`merged`), `draft`, `htmlUrl`,
`baseRef`, `headSha`, `mergedAt`, `checks` { `state` (`none`/`pending`/`success`/`failure`),
`runs` [{ `name`, `status`, `conclusion`, `htmlUrl`, `title`, `summary` }] }. Title `<NNN> <NN> <layer>:
<task>`; body links the spec and lists the ACs. A blank `GIT_TOKEN` answers `503 git_not_configured`.

## Files

Under `api/src/main/kotlin/com/toptier/custom/`:

- `github/config/GitHubConfig.kt` — change: bean `gitHubWriteRestClient(builder, gitHubProperties,
  gitProperties)`: same base URL, headers and timeouts as `gitHubRestClient` (reuse `configure`), but
  `Authorization: Bearer ${gitProperties.token}` when it is not blank. Refactor `configure` to take the
  token as a parameter (existing callers and tests pass `props.token`).
- `github/service/GitHubCalls.kt` — new: top-level `fun <T> callGitHub(path: String, request: () -> T): T`
  holding the failure mapping now private in `GitHubPullRequestClient.call` (404 → `GitHubNotFoundException`,
  rate limit, 401/403/other → `GitHubRefusedException`, timeout, unreachable; never logs the token).
- `github/service/GitHubPullRequestClient.kt` — change: its `call` keeps the blank-token check and delegates
  the rest to `callGitHub`. Behaviour and tests unchanged.
- `github/service/GitHubModels.kt` — change: `GitHubRef` gets `val sha: String? = null`; new
  `GitHubCheckRuns(@JsonProperty("check_runs") val checkRuns: List<GitHubCheckRun>)` and
  `GitHubCheckRun(name, status, conclusion: String?, @JsonProperty("html_url") htmlUrl: String?, output: GitHubCheckOutput?)`,
  `GitHubCheckOutput(title: String?, summary: String?)`.
- `github/service/GitHubPullWriteClient.kt` — new `@Service`: `create(owner, repo, title, head, base, body): GitHubPull`,
  `setBase(owner, repo, number, base): GitHubPull`, `get(owner, repo, number): GitHubPull`,
  `status(owner, repo, number): PullStatus` (get + check runs for `head.sha`). Qualifies the RestClient
  `gitHubWriteRestClient`. A blank `GIT_TOKEN` throws `GitNotConfiguredException` (checkout package) before any request.
- `github/service/PullStatus.kt` — new: `PullStatus(number, title, state, draft, htmlUrl, baseRef, headSha, mergedAt, checks)`,
  `ChecksStatus(state, runs)`, `CheckRunStatus(name, status, conclusion, htmlUrl, title, summary)`, and the mapping from
  `GitHubPull` + `GitHubCheckRun`s.
- `feature/service/PullText.kt` — new object: `title(featureNumber, taskNumber, layer, task)`,
  `docsTitle(featureNumber, featureName)`, `body(task, specUrl, acs, stackedOn: Int?)`, `docsBody(code, folderUrl)`,
  `blobUrl(owner, repo, branch, path)`.
- `checkout/config/GitProperties.kt` — change: the `token` KDoc says "Contents and Pull requests read+write".

## Steps

1. Extract `callGitHub` and make `GitHubPullRequestClient` use it; run the existing client tests unchanged.
2. Add the models and `gitHubWriteRestClient`. Inject `GitHubPullWriteClient` with
   `@Qualifier("gitHubWriteRestClient")`.
3. `create`: `POST /repos/{o}/{r}/pulls` with the four fields; returns the created `GitHubPull`. A 422 (a PR already
   exists, no commits between the branches) is a `GitHubRefusedException` like any other 4xx; GitHub's message is not
   passed on.
4. `setBase`: `PATCH …/pulls/{n}` with `{"base": base}`.
5. `status`: `get`, then `GET /repos/{o}/{r}/commits/{headSha}/check-runs?per_page=100`; no `head.sha` → no checks.
   `state` of the PR: `merged` when `merged_at` is set, else GitHub's `open`/`closed` (reuse
   `PullRequestSummaryResponse.state`). Checks state: no runs → `none`; any run whose `status` isn't `completed` →
   `pending`; else any `conclusion` not in `success`, `neutral`, `skipped` → `failure`; else `success`. A run's `title`
   is cut to 200 characters and `summary` to 1000.
6. `PullText`:
   - `title` → `"%03d %02d %s: %s"` (feature number, task number, layer, task); `docsTitle` → `"%03d %s: docs"`.
   - `body` → `<task>\n\nSpec: <specUrl>\nACs covered: AC-3, AC-4\n` plus `\nStacked on #<n>\n` when `stackedOn` is set.
   - `docsBody` → `Docs for <code>: requirements, task breakdown and task specs.\n\nFeature folder: <folderUrl>\n`.
   - `blobUrl` → `https://github.com/<owner>/<repo>/blob/<branch>/<path>` (branch names keep their `/`).

## Tests

- `GitHubPullWriteClientTests` (`MockRestServiceServer.bindTo`, fixtures shaped like GitHub's):
  - `create` sends method, path, JSON body and the Bearer header with the git token; parses the created PR (AC-13, AC-14);
  - `setBase` sends the `PATCH` body (AC-8's retarget, used in 06);
  - `status` maps: merged PR; open PR with a failing run (name, conclusion, title, summary kept) → `failure` (AC-15);
    all success → `success`; a queued run → `pending`; none → `none`; long summary cut to 1000;
  - 403, 404, 429, 500, timeout, connection refused → `GitHubRefusedException`, `GitHubNotFoundException`,
    `GitHubRateLimitedException`, `GitHubRefusedException`, `GitHubTimeoutException`, `GitHubUnreachableException` (AC-16);
  - a blank token → `GitNotConfiguredException` and no request (AC-16).
- `PullTextTests` — title and docs title, body with and without "Stacked on", blob URL with slashes in the branch (AC-14).
- `GitHubPullRequestClientTests` — unchanged and green (refactor safety).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.

## Out of scope

- Any route, entity or feature logic (03 onward). The handler mapping of these exceptions is added with the first route (03).
- Changing which token the 002 read routes use.
