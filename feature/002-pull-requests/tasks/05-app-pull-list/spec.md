---
feature: 002-pull-requests
task: 05-app-pull-list
layer: app
status: approved
approved: 2026-11-05
branch: feat/002-pull-requests/05-app-pull-list
base: feat/002-pull-requests/04-app-pull-files
covers: [AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-18, AC-19]
---

# 05 — "Pull requests" card on the project page

## Goal

The project detail page gets a "Pull requests" card listing the repository's open pull requests.
The card loads on its own, pages without reloading the project, and links to the details page
and to GitHub. This releases the feature.

## Contract

Consumes `GET /api/projects/{id}/pulls?page=` → `{ items: [PullRequestSummary], page, size: 20,
hasNext }`: open pull requests only, newest created first. Errors are as in task 03.

## Files

- `app/app/routes/project-pulls.ts` — new: a client resource route at `projects/:id/pulls`,
  exporting only a `clientLoader` that returns `{ page }` or `{ error }` (from
  `pullRequestError`).
- `app/app/routes.ts` — change (shared file): add
  `route("projects/:id/pulls", "routes/project-pulls.ts")` in the app layout.
- `app/app/components/pull-requests/pull-request-list.tsx` — new: `PullRequestsCard`.
- `app/app/routes/project-detail.tsx` — change: render `<PullRequestsCard projectId={project.id}
  />` below the details card, for every project, archived and unverified included (AC-5).

## Steps

1. **Loading.**
   - `PullRequestsCard` uses `useFetcher<typeof clientLoader>()`.
   - On mount, and when the page changes, it calls
     `fetcher.load(\`/projects/${projectId}/pulls?page=${page}\`)`. The page is local state that
     starts at 1.
   - The project page's own loader doesn't wait for GitHub, and paging doesn't rerun it (AC-3,
     AC-6).
   - If `fetcher.load` won't call a component-less `clientLoader` route in this React Router
     version, fall back to local state: call `listPullRequests` in an effect, with an abort guard.
     Keep the same states.
2. **States:**
   - **loading**, the first time: three `Skeleton` rows. When changing pages, keep the old rows
     dimmed and disable the pager (AC-6).
   - **empty**: "No open pull requests" in an `Empty` inside the card (AC-4).
   - **error**: an `Alert` with the message and, when `retryable`, a "Try again" button that
     reloads the current page. The rest of the project page keeps working (AC-18).
3. **Rows.** A `ul` with dividers, like the Dashboard's "Recent projects". Each row has:
   - the title as a `Link` to `/projects/:id/pulls/:number`, and `#number` in muted text (AC-2);
   - a "Draft" badge when `draft`;
   - a 20 px `Avatar` with the author's login, or "Unknown";
   - `headRef → baseRef` in `font-mono`, wrapped with `break-all`;
   - "Updated <formatDateTime>";
   - an icon button (`ExternalLinkIcon`, `aria-label="Open #42 on GitHub"`), a link to `htmlUrl`
     with `target="_blank" rel="noreferrer"` (AC-1, AC-2).
4. **Pager.** A footer holds Previous (when `page > 1`) and Next (when `hasNext`), as buttons
   that set the page. Show "Page n". Hide the footer when it's page 1 with no next.
5. **Card header:** "Pull requests", with "Open on GitHub" linking to
   `<repositoryUrl>/pulls`.
6. **Layout:** rows wrap at 375 px with no horizontal page scroll (AC-19).
7. Format the touched files with Prettier.

## Tests

By hand, against the running stack, with `API_GITHUB_TOKEN` set:

- A project with open PRs lists them newest first, with every row field and the Draft badge
  (AC-1).
- The title opens the details page; the icon opens GitHub in a new tab (AC-2).
- A repository with more than 20 open PRs: Next and Previous page through them, and the project
  details don't reload (no request to `/api/projects/{id}` in the network tab) (AC-3).
- A repository with none shows "No open pull requests" (AC-4).
- The card shows on an archived project and on an unverified one (AC-5).
- With throttled network, the project's details show first and the card's skeleton follows
  (AC-6).
- With the token unset, the card shows the not-configured message with Try again, while Edit and
  Archive still work (AC-18).
- At 375 px, there's no horizontal page scroll (AC-19).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the changed files

## Out of scope

- Pull requests on the Dashboard or the projects list.
- Filters.
- Closed pull requests.
