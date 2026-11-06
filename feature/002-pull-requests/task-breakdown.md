---
feature: 002-pull-requests
status: in-progress
approved: 2026-11-05
---

# Pull requests: task breakdown

Requirements: [requirements.md](requirements.md)

## Contracts

The interfaces between layers, fixed before the tasks that depend on them. Every route needs
`Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as `ProblemDetail`
(`application/problem+json`). Pull requests are read live from GitHub through the project's
repository; nothing is stored.

### PullRequestSummary (JSON)

```json
{
  "number": 42,
  "title": "Add the projects list",
  "state": "open",
  "draft": false,
  "author": { "login": "octocat", "avatarUrl": "https://avatars.githubusercontent.com/u/1?v=4" },
  "headRef": "feat/list",
  "baseRef": "main",
  "createdAt": "2026-11-04T10:15:30Z",
  "updatedAt": "2026-11-05T08:00:00Z",
  "htmlUrl": "https://github.com/acme/factory/pull/42"
}
```

- `state`: `open`, `closed` or `merged` (`merged` when GitHub's `merged_at` is set).
- `author`: `null` when GitHub has no user (a deleted account).
- `headRef`: GitHub's `head.label` (`owner:branch`) when the head is in a fork, else `head.ref`.
- Times: ISO-8601 UTC.

### PullRequest (JSON)

PullRequestSummary plus:

```json
{
  "body": "## Summary\n- …",
  "commits": 3,
  "additions": 120,
  "deletions": 14,
  "changedFiles": 7,
  "mergedAt": null,
  "closedAt": null
}
```

- `body`: the raw Markdown, or `null` when empty. The app renders it; the API doesn't touch it.

### PullRequestFile (JSON)

```json
{
  "filename": "app/app/routes/projects.tsx",
  "previousFilename": null,
  "status": "modified",
  "additions": 10,
  "deletions": 2,
  "patch": "@@ -1,4 +1,5 @@\n …",
  "htmlUrl": "https://github.com/acme/factory/blob/abc123/app/app/routes/projects.tsx"
}
```

- `status`: GitHub's value: `added`, `removed`, `modified`, `renamed`, `copied`, `changed` or
  `unchanged`.
- `previousFilename`: set for `renamed` (and `copied`), else `null`.
- `patch`: `null` when GitHub sends none (binary or too large).
- `htmlUrl`: GitHub's `blob_url`.

### Paged results

GitHub gives no totals for these lists, so pages carry `hasNext` (from GitHub's `Link` header
having `rel="next"`):

```json
{ "items": [ … ], "page": 1, "size": 20, "hasNext": true }
```

### `GET /api/projects/{id}/pulls`

- Query: `page`, 1-based, default `1`.
- Response `200`: a page of PullRequestSummary, `size` 20, open pull requests only, newest
  created first. A page past the end has empty `items`.
- Works for archived and unverified projects.

### `GET /api/projects/{id}/pulls/{number}`

- Response `200`: PullRequest, in any state.

### `GET /api/projects/{id}/pulls/{number}/files`

- Query: `page`, 1-based, default `1`.
- Response `200`: a page of PullRequestFile, `size` 30, in GitHub's order.

### Errors (all three routes)

Each error's `ProblemDetail` has a `code` property the app switches on, and a `detail` it can
show as is:

| Status | `code` | When | `detail` |
| ------ | ------ | ---- | -------- |
| `400` | `invalid_request` | `page` < 1 or not a number, or `number` < 1 or not a number | "Invalid value for page." |
| `404` | `project_not_found` | no project has that id | "Project not found." |
| `404` | `pull_request_not_found` | the repository is there but has no pull request with that number | "Pull request not found." |
| `404` | `repository_not_found` | GitHub answers `404` for the repository: it doesn't exist or the token can't see it | "GitHub couldn't find this repository, or the API's token can't see it." |
| `502` | `github_error` | GitHub rejects the token (`401`), refuses access (`403` that isn't a rate limit), or answers `5xx`, or the connection fails | "GitHub refused the request. The API's token may need \"Pull requests: read\" on this repository." for `403`; "GitHub couldn't be reached. Try again." otherwise |
| `502` | `github_rate_limited` | `429`, or `403` with `x-ratelimit-remaining: 0` | "GitHub's rate limit was reached. Try again in a few minutes." |
| `503` | `github_not_configured` | `app.github.token` is blank (no call is made) | "The API has no GitHub token configured." |
| `504` | `github_timeout` | GitHub doesn't answer within 5 s (`app.github.timeout`) | "GitHub didn't answer in time. Try again." |

- GitHub answers `404` both for a missing repository and for a missing pull request. On a `404`
  from `/pulls/{number}` or its files, the API checks the repository with
  `GitHubRepositoryChecker` and answers `repository_not_found` or `pull_request_not_found`.

### api → GitHub

- Reuses the `gitHubRestClient` bean (base URL, token, 5 s timeouts) from feature 001.
- `GET /repos/{owner}/{repo}/pulls?state=open&sort=created&direction=desc&per_page=20&page={page}`
- `GET /repos/{owner}/{repo}/pulls/{number}`
- `GET /repos/{owner}/{repo}/pulls/{number}/files?per_page=30&page={page}`
- The owner and repo come from the project's stored `repositoryUrl` (`GitHubRepositoryUrl`).
- The token needs "Pull requests: read" as well as "Metadata: read".

### app → api

Through `lib/api.ts` (`/api` is proxied by Vite). Errors from these routes show the problem's
`detail`, not the generic `502` text, with "Try again" for the `5xx` codes. There's no agent
interface in this feature.

### App routes

- `/projects/:id`: gains a "Pull requests" card.
- `/projects/:id/pulls/:number`: the details page, with `?page=` for the files page.

## Tasks

Stack order, bottom first. Each task is one PR based on the row above it.

| #  | Layer | Task | Depends on | ACs | Branch |
| -- | ----- | ---- | ---------- | --- | ------ |
| 00 | docs  | Feature folder | — | — | `feat/002-pull-requests/00-docs` |
| 01 | api   | `GitHubPullRequestClient` in `github/service`: list open, get one, list files, `Link`-header paging; GitHub failures as typed exceptions (not found, refused, rate-limited, not configured, timeout, unreachable). `MockRestServiceServer` tests with fixtures shaped like GitHub's. Property comment on `app.github.token` names "Pull requests: read". | 00 | AC-17, AC-18 | `feat/002-pull-requests/01-api-github-pull-client` |
| 02 | api   | `pullrequest` package: `PullRequestService` (project → owner/repo, 404 disambiguation), DTOs, `PullRequestController` with the three routes, exception handler with `code`s. Service and `@WebMvcTest` tests. | 01 | AC-1, AC-3–AC-5, AC-7, AC-9, AC-10, AC-14–AC-18 | `feat/002-pull-requests/02-api-pull-endpoints` |
| 03 | app   | `lib/pull-requests.ts` (types, calls, error handling) and the `/projects/:id/pulls/:number` details page: breadcrumb, summary, state and draft badges, Markdown description (`react-markdown` + `remark-gfm`, no raw HTML, links in a new tab), not-found and error states. Route registered, not linked. | 02 | AC-7, AC-8, AC-13–AC-19 | `feat/002-pull-requests/03-app-pull-detail` |
| 04 | app   | Changed files on the details page: path, status, previous path, +/−; 30 per page with Previous/Next in `?page=`; collapsible unified diff (collapsed by default, added/removed lines colored, scrolls in its own box), "Diff not available here" with a GitHub link when `patch` is null. | 03 | AC-9–AC-12, AC-19 | `feat/002-pull-requests/04-app-pull-files` |
| 05 | app   | "Pull requests" card on `/projects/:id`: loads on its own after the page, rows (title link, number, avatar and login, Draft, branches, updated), GitHub icon link, Previous/Next without reloading the project, empty, loading and error-with-retry states. Releases the feature. | 04 | AC-1–AC-6, AC-18, AC-19 | `feat/002-pull-requests/05-app-pull-list` |

All tasks are done in this Claude Code session, as for the end of feature 001, unless the user
names other sessions at dispatch.

## AC coverage

| AC    | Tasks      | Verified by |
| ----- | ---------- | ----------- |
| AC-1  | 02, 05     | `PullRequestControllerTests` list shape; `PullRequestServiceTests` maps fork head label, null author; manual card rows |
| AC-2  | 05         | Manual: title opens the details page, icon opens GitHub in a new tab |
| AC-3  | 01, 02, 05 | `GitHubPullRequestClientTests` `hasNext` from `Link`; `PullRequestControllerTests` `page` passed through; manual Next/Previous without the project reloading |
| AC-4  | 02, 05     | `PullRequestControllerTests` empty page; manual "No open pull requests" |
| AC-5  | 02, 05     | `PullRequestServiceTests` archived and unverified projects still list; manual |
| AC-6  | 05         | Manual: with a slowed network the project shows first and the card has a loading state |
| AC-7  | 02, 03     | `PullRequestControllerTests` details shape; manual summary |
| AC-8  | 03         | Manual: a description with headings, lists, code, a table, a task list, a link and `<script>`/`<img onerror>` renders Markdown, shows the HTML as text, and links open in a new tab; empty → "No description" |
| AC-9  | 02, 04     | `PullRequestControllerTests` files shape incl. `previousFilename`; manual file rows |
| AC-10 | 01, 02, 04 | `GitHubPullRequestClientTests` files paging; manual Previous/Next over files |
| AC-11 | 04         | Manual: files start collapsed, expand to a colored diff |
| AC-12 | 02, 04     | `PullRequestControllerTests` `patch: null`; manual "Diff not available here" + GitHub link |
| AC-13 | 03         | Manual breadcrumb |
| AC-14 | 02, 03     | `PullRequestServiceTests` `merged` from `merged_at`, `closed`; manual badges |
| AC-15 | 02, 03     | `PullRequestControllerTests` `401` without a token; manual signed-out redirect |
| AC-16 | 02, 03     | `PullRequestServiceTests` unknown project, 404 disambiguation → `pull_request_not_found`; manual "Pull request not found" page |
| AC-17 | 01, 02, 03 | `GitHubPullRequestClientTests` `404`; `PullRequestServiceTests` → `repository_not_found`; manual message on the card and details page |
| AC-18 | 01, 02, 03, 05 | `GitHubPullRequestClientTests` `401`/`403`/`403`+rate-limit/`429`/`5xx`/timeout/blank token; `PullRequestControllerTests` status and `code` per case; manual "Try again" on the card (project still usable) and details page |
| AC-19 | 03, 04, 05 | Manual at 375 px: no horizontal page scroll; long paths wrap; diffs scroll inside their box |

## Rollout

- The api routes (01–02) are additive; nothing calls them until 05.
- The details page (03–04) is registered but unlinked: nothing points to it until 05.
- 05 adds the card to the project page, which releases the feature.
- On release: give the API's GitHub token (`API_GITHUB_TOKEN`) "Pull requests: read" on the
  project repositories, as well as "Metadata: read". Without it the card shows the `github_error`
  message; without any token, the `github_not_configured` one.
- No database changes.

## Progress

Docs PR: [#16](https://github.com/fahadzia88/factory/pull/16), the base of the stack. Merge bottom-up.

| #  | Spec | PR | Status |
| -- | ---- | -- | ------ |
| 01 | [spec](tasks/01-api-github-pull-client/spec.md) | [#17](https://github.com/fahadzia88/factory/pull/17) | pr open |
| 02 | [spec](tasks/02-api-pull-endpoints/spec.md) | [#18](https://github.com/fahadzia88/factory/pull/18) | pr open |
| 03 | [spec](tasks/03-app-pull-detail/spec.md) | [#19](https://github.com/fahadzia88/factory/pull/19) | pr open |
| 04 | [spec](tasks/04-app-pull-files/spec.md) | [#20](https://github.com/fahadzia88/factory/pull/20) | pr open |
| 05 | [spec](tasks/05-app-pull-list/spec.md) | [#21](https://github.com/fahadzia88/factory/pull/21) | pr open |

## Changelog

- 2026-11-05 — Created.
- 2026-11-05 — Approved.
- 2026-11-05 — Task specs written.
- 2026-11-05 — Specs approved; in progress.
- 2026-11-05 — All five task PRs open (#17–#21), stacked on the docs PR #16.
