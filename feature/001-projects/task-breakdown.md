---
feature: 001-projects
status: approved
approved: 2026-11-04
---

# Projects: task breakdown

Requirements: [requirements.md](requirements.md)

## Contracts

The interfaces between layers, fixed before the tasks that depend on them. Every `/api/projects`
route needs `Authorization: Bearer <API_TOKEN>` (401 otherwise) and returns errors as
`ProblemDetail` (`application/problem+json`).

### Project (JSON)

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
- `repositoryVerified`: `false` when GitHub couldn't be asked; the app shows "Repository not
  verified".
- `createdAt` and `updatedAt`: ISO-8601 UTC timestamps.

### Field errors

A rejected save returns a `ProblemDetail` with an `errors` property that maps a field to a
message the app shows under that field:

```json
{ "status": 400, "title": "Bad Request", "detail": "The project couldn't be saved.",
  "errors": { "name": "Enter a name.", "repositoryUrl": "Repository not found on GitHub." } }
```

The field keys are `name`, `repositoryUrl`, `description` and `status`.

### `GET /api/projects`

- Query (all optional):
  - `q`: name contains, ignoring case; trimmed, and blank is ignored.
  - `status`: one status.
  - `includeArchived`: `true` adds archived projects; default `false`.
  - `sort`: `updatedAt` (default), `name` or `status`. Status sorts in lifecycle order
    `planning` → `retired`, and `id` breaks ties.
  - `direction`: `asc` or `desc`; default `desc` for `updatedAt`, `asc` otherwise.
  - `page`: 1-based, default `1`.
  - `size`: default `20`, maximum `100`.
- Response `200`:
  `{ "items": [Project], "page": 1, "size": 20, "totalItems": 57, "totalPages": 3 }`. A page past
  the end returns empty `items`.
- Errors: `400` for an unknown `status`, `sort` or `direction`, or `page`/`size` out of range.

### `GET /api/projects/summary`

- Response `200`: `{ "counts": { "planning": 2, "active": 5, "maintenance": 1, "retired": 0 },
  "recent": [Project] }`.
- Only non-archived projects. `counts` always has all four keys; `recent` holds at most 5,
  most recently updated first.

### `GET /api/projects/{id}`

- Response `200`: Project, archived or not.
- Errors: `404` when no project has that id.

### `POST /api/projects`

- Request: `{ "name": "Factory", "repositoryUrl": "https://github.com/acme/factory",
  "description": "…", "status": "planning" }`.
  - `description` is optional; `status` is optional and defaults to `planning`.
  - The name is required, trimmed, and at most 100 characters. The URL is required, trimmed, and
    must match `https://github.com/<owner>/<repo>`. The description is at most 2,000 characters;
    blank is stored as `null`.
- Response `201`: Project, with a `Location: /api/projects/{id}` header.
- Errors:
  - `400` with field errors for invalid input, and for a repository GitHub reports not found
    (`repositoryUrl: "Repository not found on GitHub."`).
  - `409` with field errors for a duplicate name (ignoring case, archived included) or a
    duplicate repository URL (exact text).
- GitHub unreachable, rate-limited, slower than 5 s, or no token configured: not an error. The
  project saves with `repositoryVerified: false`.

### `PUT /api/projects/{id}`

- Request: the same fields as `POST`, with `status` required.
- GitHub is called only when the URL changed, or the project is unverified.
- Response `200`: Project, with a new `updatedAt`.
- Errors:
  - `400` and `409` with field errors, as for `POST`;
  - `404` when no project has that id;
  - `409` without field errors when the project is archived ("Archived projects can't be
    edited. Restore it first.").

### `POST /api/projects/{id}/archive` and `POST /api/projects/{id}/restore`

- No body.
- Response `200`: Project. Both are idempotent and don't change `status`.
- Errors: `404`.
- There is no `DELETE` route (`405`).

### api → GitHub (`app.github.*`)

- `GET https://api.github.com/repos/{owner}/{repo}` with `Authorization: Bearer <app.github.token>`,
  connect and read timeouts of 5 s.
- `200` means found. `404` means not found, which also covers a private repo the token can't see.
- Unverified: `403` or `429` from rate limiting, `401` (bad token, logged as a warning), `5xx`, a
  timeout, an I/O error, or a blank `app.github.token` (no call is made).
- Config: `app.github.token=${GITHUB_TOKEN:}` and `app.github.base-url=https://api.github.com`.

### agent → api (root agent tools)

- `list_projects({ q?, status?, includeArchived = true, sort?, direction? })` calls
  `GET {API_URL}/api/projects` with `Authorization: Bearer ${API_TOKEN}`. It fetches pages of 100
  until done and returns the Project JSON as is.
- `get_project({ id })` calls `GET {API_URL}/api/projects/{id}` and returns the Project, or
  `{ found: false }` on `404`.
- There are no write tools. The root instructions say the Assistant can't change projects and
  point to the Projects page.

### app → api

The app calls the routes above through `lib/api.ts` (`/api` is proxied by Vite). There's no new
app ↔ agent interface: the Assistant page already talks to the agent through `/eve`.

## Tasks

Stack order, bottom first. Each task is one PR based on the row above it.

| #  | Layer | Task | Depends on | ACs | Branch |
| -- | ----- | ---- | ---------- | --- | ------ |
| 00 | docs  | Feature folder | — | — | `feat/001-projects/00-docs` |
| 01 | api   | GitHub repository check: `app.github` properties, `RestClient` bean, `GitHubRepositoryChecker` returning found / not found / unavailable | 00 | AC-8, AC-9 | `feat/001-projects/01-api-github-check` |
| 02 | api   | `Project` entity, repository and `ProjectService`: create, update, archive, restore; validation, uniqueness, GitHub check and the verified flag; feature exceptions. No HTTP yet. | 01 | AC-4–AC-11, AC-19–AC-21 | `feat/001-projects/02-api-project-service` |
| 03 | api   | `ProjectController` and DTOs for `POST`, `GET {id}`, `PUT`, archive, restore; exception handler with field errors | 02 | AC-2, AC-4, AC-5, AC-19, AC-20, AC-22, AC-23 | `feat/001-projects/03-api-project-endpoints` |
| 04 | api   | `GET /api/projects` (search, status, includeArchived, sort, paging) and `GET /api/projects/summary` | 03 | AC-12–AC-16, AC-24 | `feat/001-projects/04-api-project-list` |
| 05 | infra | Pass the API's own read-only token: `api` service `GITHUB_TOKEN: ${API_GITHUB_TOKEN:-}` in `docker-compose.yml`, documented in `README-base.md` | 04 | AC-8, AC-9 | `feat/001-projects/05-infra-api-github-token` |
| 06 | agent | Root agent tools `list_projects` and `get_project` calling the API; instructions: read-only, point to the Projects page | 05 | AC-26–AC-28 | `feat/001-projects/06-agent-project-tools` |
| 07 | app   | `lib/projects.ts` (types and calls) and the `/projects` list page: search, status filter, archived switch, sortable columns, pagination, state in the URL, empty and error states. Route registered, not in the nav. | 06 | AC-2, AC-9, AC-12–AC-18, AC-29, AC-30 | `feat/001-projects/07-app-project-list` |
| 08 | app   | `/projects/:id` detail page: fields, "Repository not verified", archived read-only view, archive with confirmation, restore, not-found page | 07 | AC-4, AC-9, AC-19–AC-23, AC-29, AC-30 | `feat/001-projects/08-app-project-detail` |
| 09 | app   | `/projects/new` and `/projects/:id/edit`: shared form (status starts on `planning`), field errors from the API, values kept on failure | 08 | AC-3–AC-8, AC-11, AC-30 | `feat/001-projects/09-app-project-form` |
| 10 | app   | Dashboard summary (status counts linking to the filtered list, 5 recent, View all, empty state with New project) and the "Projects" nav item | 09 | AC-1, AC-24, AC-25, AC-29, AC-30 | `feat/001-projects/10-app-dashboard-nav` |

Who does what: `api` tasks go to the `api/` Claude Code session and `infra` to the coordinator
(with the user's go-ahead). `agent` and `app` tasks go to the session the coordinator names: the
`app/` Claude Code session, or the agent's `stacked-pr` subagent.

## AC coverage

| AC    | Tasks          | Verified by |
| ----- | -------------- | ----------- |
| AC-1  | 10             | Manual: the "Projects" nav item opens `/projects` |
| AC-2  | 03, 07         | `ProjectControllerTests` 401 without token; manual: signed out, `/projects` redirects to `/login` |
| AC-3  | 09             | Manual: the create form starts on `planning` with four options |
| AC-4  | 01, 02, 03, 08, 09 | `ProjectServiceTests` create; `ProjectControllerTests` 201 + Location; manual: save lands on the detail page with every field |
| AC-5  | 02, 03, 09     | `ProjectServiceTests` blank, too long, bad URL; `ProjectControllerTests` 400 field errors; manual: errors under fields, values kept |
| AC-6  | 02, 09         | `ProjectServiceTests` duplicate name, ignoring case and archived included → conflict on `name`; manual error on name |
| AC-7  | 02, 09         | `ProjectServiceTests` exact duplicate URL → conflict on `repositoryUrl`, case/`.git` variant accepted; manual error on repository |
| AC-8  | 01, 02, 09     | `GitHubRepositoryCheckerTests` 404 → not found; `ProjectServiceTests` → field error; manual message |
| AC-9  | 01, 02, 07, 08 | `GitHubRepositoryCheckerTests` 403/429/5xx/timeout/blank token → unavailable; `ProjectServiceTests` saves unverified; manual marker on list and detail |
| AC-10 | 02             | `ProjectServiceTests` unverified project re-checked on save: found clears the flag, not found → field error |
| AC-11 | 02, 09         | `ProjectServiceTests` update; unchanged URL of a verified project makes no GitHub call; manual edit flow |
| AC-12 | 04, 07         | `ProjectListTests` default sort and 20 per page; manual pagination |
| AC-13 | 04, 07         | `ProjectListTests` case-insensitive name search across pages; manual |
| AC-14 | 04, 07         | `ProjectListTests` status filter; manual "Any status" |
| AC-15 | 04, 07         | `ProjectListTests` includeArchived; manual archived badge |
| AC-16 | 04, 07         | `ProjectListTests` sort by name/status/updatedAt both directions; manual column toggle |
| AC-17 | 07             | Manual: reload, copied link and Back restore search, filter, switch, sort and page |
| AC-18 | 07             | Manual: no projects → empty state with New project; no matches → "No projects match" + clear filters |
| AC-19 | 02, 03, 07, 08 | `ProjectServiceTests` archive; `ProjectControllerTests` archive 200; manual confirm dialog, leaves default list, archived on detail |
| AC-20 | 02, 03, 08     | `ProjectServiceTests` edit archived rejected; `ProjectControllerTests` → 409; manual read-only detail with Restore, no Edit |
| AC-21 | 02, 08         | `ProjectServiceTests` restore keeps status; manual |
| AC-22 | 03, 08         | `ProjectControllerTests` DELETE → 405; manual: no delete action |
| AC-23 | 03, 08         | `ProjectControllerTests` 404; manual "Project not found" page |
| AC-24 | 04, 10         | `ProjectListTests` summary counts and recent; manual Dashboard links |
| AC-25 | 10             | Manual: Dashboard empty state opens the create form |
| AC-26 | 06             | eve eval: "which projects are there?" lists archived ones too (API stubbed) |
| AC-27 | 06             | eve eval: asking about a project by name answers from its stored fields |
| AC-28 | 06             | eve eval: asking to create or archive gets a refusal and no write tool is called |
| AC-29 | 07, 08, 10     | Manual: API stopped → error with retry on list, detail and Dashboard |
| AC-30 | 07, 08, 09, 10 | Manual at 375 px: no horizontal page scroll |

## Rollout

- The api routes (01–04) are additive. They're live once merged, but nothing links to them.
- Until the API's token is set (05), every save is `repositoryVerified: false`; nothing breaks.
- The agent tools (06) are live once merged. Before 10 the only way to create a project is the
  API, so the Assistant just reports that there are none.
- The app routes (07–09) are registered but unlinked: no nav item, and the Dashboard is unchanged.
- 10 adds the "Projects" nav item and the Dashboard summary, which releases the feature.
- On release: create a fine-grained GitHub token with Metadata read on the repos projects will use,
  and set `API_GITHUB_TOKEN` in the root `.env`. Hibernate creates the `project` table on the
  API's next start (`ddl-auto=update`).

## Progress

| #  | Spec | PR | Status |
| -- | ---- | -- | ------ |
| 01 | [spec](tasks/01-api-github-check/spec.md) | | not started |
| 02 | [spec](tasks/02-api-project-service/spec.md) | | not started |
| 03 | [spec](tasks/03-api-project-endpoints/spec.md) | | not started |
| 04 | [spec](tasks/04-api-project-list/spec.md) | | not started |
| 05 | [spec](tasks/05-infra-api-github-token/spec.md) | | not started |
| 06 | [spec](tasks/06-agent-project-tools/spec.md) | | not started |
| 07 | [spec](tasks/07-app-project-list/spec.md) | | not started |
| 08 | [spec](tasks/08-app-project-detail/spec.md) | | not started |
| 09 | [spec](tasks/09-app-project-form/spec.md) | | not started |
| 10 | [spec](tasks/10-app-dashboard-nav/spec.md) | | not started |

## Changelog

- 2026-11-04 — Created.
- 2026-11-04 — Approved.
