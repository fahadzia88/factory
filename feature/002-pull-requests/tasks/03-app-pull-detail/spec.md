---
feature: 002-pull-requests
task: 03-app-pull-detail
layer: app
status: approved
approved: 2026-11-05
branch: feat/002-pull-requests/03-app-pull-detail
base: feat/002-pull-requests/02-api-pull-endpoints
covers: [AC-7, AC-8, AC-13, AC-14, AC-15, AC-16, AC-17, AC-18, AC-19]
---

# 03 — Pull request details page

## Goal

Add `/projects/:id/pulls/:number`, which shows a pull request's summary, its state and draft
badges, its description rendered as Markdown, and its not-found and error states. The route is
registered but nothing links to it yet. The files come in task 04.

## Contract

Consumes, from task-breakdown.md:

- `GET /api/projects/{id}` → Project (feature 001).
- `GET /api/projects/{id}/pulls/{number}` → PullRequest:
  - `number`, `title`, `state` (`open`, `closed` or `merged`), `draft`;
  - `author`: `{ login, avatarUrl }`, or `null`;
  - `headRef`, `baseRef`;
  - `createdAt`, `updatedAt`, `htmlUrl`;
  - `body`: raw Markdown, or `null`;
  - `commits`, `additions`, `deletions`, `changedFiles`;
  - `mergedAt`, `closedAt`.
- Errors are `ProblemDetail`s with `code`:
  - `404`: `project_not_found`, `pull_request_not_found` or `repository_not_found`;
  - `400`: `invalid_request`;
  - `5xx`: `github_error`, `github_rate_limited`, `github_not_configured` or `github_timeout`.

  Show `detail` as is.

## Files

- `app/package.json` — change (shared file; the user confirms the edit): add `react-markdown` and
  `remark-gfm`. Install with `docker exec factory-app npm install react-markdown remark-gfm`.
- `app/app/lib/pull-requests.ts` — new:
  - the types `PullRequestSummary`, `PullRequest`, `PullRequestFile`, `PullRequestPage<T>`;
  - `getPullRequest(projectId, number)`;
  - `listPullRequests(projectId, page)` and `listPullRequestFiles(projectId, number, page)`, for
    tasks 04 and 05, so the module holds the whole contract;
  - `pullRequestError(error)`.
- `app/app/components/pull-requests/pull-request-badges.tsx` — new: `PullRequestStateBadge` (Open,
  Draft, Merged, Closed).
- `app/app/components/pull-requests/markdown.tsx` — new: `Markdown`.
- `app/app/components/pull-requests/pull-request-not-found.tsx` — new.
- `app/app/components/projects/project-page.tsx` — change: `ProjectBreadcrumb` takes an optional
  `project: { id, name }`, rendered as a middle link.
- `app/app/routes/pull-request-detail.tsx` — new.
- `app/app/routes.ts` — change (shared file): add
  `route("projects/:id/pulls/:number", "routes/pull-request-detail.tsx")` in the app layout.

## Steps

1. **`pullRequestError(error)`** returns `{ code, message, retryable }`:
   - `code`: the problem's `code` property, or `undefined`;
   - `message`: the problem's `detail` when there is one. Otherwise use `apiErrorMessage(error)`,
     because `lib/api.ts` is locked and maps `502` to generic text;
   - `retryable`: true when the status is `5xx`, or when the request failed before getting a
     response.

   Keep a 401 as `apiErrorMessage`'s session message.
2. **`clientLoader({ params })`:**
   - Parse the ids with `projectId()`. Read `number` the same way; an invalid value → not found.
   - Load the project and the pull request in parallel with `Promise.allSettled`.
   - Return `{ project, pull, notFound, error }`:
     - not found: the project `404`s, or the pull request's `code` is `project_not_found` or
       `pull_request_not_found`;
     - error: any other failure, as `pullRequestError` gives it.
   - Set `hydrate = true` and add a `HydrateFallback` spinner, as in `project-detail.tsx`.
   - Signed-out visitors are already redirected by the app layout (AC-15).
3. **`meta`:** `#<number> <title>`, or "Pull request".
4. **Page:**
   - `ProjectBreadcrumb` with `project` (a link to `/projects/:id`) and the page "#<number>"
     (AC-13).
   - `PageHeader`: the title, with `PullRequestStateBadge` beside it. The actions hold an
     "Open on GitHub" outline button, a link with `target="_blank" rel="noreferrer"` and
     `ExternalLinkIcon`.
   - A summary `Card` with a `dl` grid, one column on phones and two from `sm` (AC-7):
     - the author: a 20 px `Avatar` and the login, or "Unknown" when `null`;
     - the branches: `headRef → baseRef` in `font-mono`, wrapping with `break-all`;
     - the created and updated times (`formatDateTime`), plus merged or closed when set;
     - commits;
     - changes: `+additions` and `−deletions` with `tabular-nums`, and the number of files changed.
   - A "Description" `Card`: `<Markdown>{pull.body}</Markdown>`, or "No description" in muted text
     (AC-8).
5. **`PullRequestStateBadge`:**
   - `merged` → "Merged";
   - `closed` → "Closed" (outline);
   - `open` → "Open", or "Draft" (secondary) when `draft`.

   Use `Badge` variants only; no hard-coded colors (AC-14).
6. **`Markdown`:**
   - `react-markdown` with `remarkPlugins={[remarkGfm]}`. Keep its default `urlTransform`, which
     drops `javascript:` URLs.
   - Raw HTML must show as text, never as live elements. If react-markdown drops HTML by default
     instead of showing it, add a tiny remark plugin that turns `html` nodes into `text` nodes, so
     `<script>` reads literally (AC-8).
   - A `components` map styles headings, `p`, lists, task-list checkboxes (disabled), `code` and
     `pre` (scrolling in their own box), `blockquote`, tables (wrapped in `overflow-x-auto`), and
     `img` (`max-w-full`). Use theme tokens only; `app.css` doesn't change.
   - `a` gets `target="_blank" rel="noreferrer"`.
7. **`PullRequestNotFound`:** an `Empty` with "Pull request not found", "It may not exist in this
   repository, or the link is wrong.", and "Back to project". The button goes to `/projects/:id`,
   or "Back to projects" when the project itself is missing (AC-16).
8. **Errors:**
   - `repository_not_found` → an `Alert` with its message (AC-17);
   - otherwise → an `Alert` titled "Couldn't load the pull request" with the message, plus "Try
     again" (revalidate) when `retryable` (AC-18).
9. **Layout:** long titles and branch names wrap, and nothing is wider than the viewport at
   375 px (AC-19).
10. Format the touched files with Prettier.

## Tests

By hand, against the running stack, with `API_GITHUB_TOKEN` set:

- Open `/projects/<id>/pulls/<open PR>`: every summary field shows; Open on GitHub opens a new tab
  (AC-7).
- A description with headings, lists, code, a table, a task list and a link renders. `<script>`
  and `<img src=x onerror=alert(1)>` show as text, and no alert fires. Links open in a new tab.
  A PR without a description shows "No description" (AC-8).
- The breadcrumb leads to the project and to Projects (AC-13).
- A merged PR shows "Merged" and a closed one "Closed"; a draft shows "Draft" (AC-14).
- Signed out, the page redirects to `/login` (AC-15).
- `/projects/<id>/pulls/999999`, `/projects/999999/pulls/1` and `/projects/<id>/pulls/abc` show
  "Pull request not found" (AC-16).
- A project whose repository the token can't see shows the repository message (AC-17).
- With `API_GITHUB_TOKEN` unset, the not-configured message shows with Try again (AC-18).
- At 375 px, there's no horizontal page scroll (AC-19).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the changed files

## Out of scope

- Changed files and diffs (task 04).
- The project page card and the link into this page (task 05).
