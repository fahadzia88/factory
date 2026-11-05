---
feature: 001-projects
task: 07-app-project-list
layer: app
status: approved
approved: 2026-11-04
branch: feat/001-projects/07-app-project-list
base: feat/001-projects/06-agent-project-tools
covers: [AC-2, AC-9, AC-12, AC-13, AC-14, AC-15, AC-16, AC-17, AC-18, AC-29, AC-30]
---

# 07 — Projects list page

## Goal

Add the typed projects client (`lib/projects.ts`) and the `/projects` list page: search, status
filter, archived switch, sortable columns and pagination, with the view kept in the URL, plus
empty and error states. The route is registered but not linked from the nav yet.

## Contract

Consumes, from task-breakdown.md:

- Project JSON: `{ id, name, repositoryUrl, description|null, status, archived,
  repositoryVerified, createdAt, updatedAt }`, where `status` is one of `planning`, `active`,
  `maintenance`, `retired`.
- `GET /api/projects`:
  - Query: `q`, `status`, `includeArchived` (default `false`), `sort` (`updatedAt` default, `name`,
    `status`), `direction` (`asc`/`desc`; default `desc` for `updatedAt`, `asc` otherwise), `page`
    (1-based), `size` (default 20, max 100).
  - Response `200`: `{ items, page, size, totalItems, totalPages }`; a page past the end has empty
    `items`.
  - Errors: `400` for invalid params; `401` without a token.

## Files

- `app/app/lib/projects.ts` — new: types and calls used by tasks 07–10.
- `app/app/routes/projects.tsx` — new: the list page.
- `app/app/components/projects/project-status-badge.tsx` — new: `StatusBadge` per status, and
  the "Archived" and "Repository not verified" badges.
- `app/app/routes.ts` — change: add `route("projects", "routes/projects.tsx")` inside the app
  layout. Don't touch `NAV_ITEMS` (task 10).

## Steps

1. **`lib/projects.ts`**:
   - `export const PROJECT_STATUSES = ["planning", "active", "maintenance", "retired"] as const`,
     `type ProjectStatus`, and `STATUS_LABEL` (`Planning`, `Active`, `Maintenance`, `Retired`).
   - `type Project` (timestamps as ISO strings) and `type ProjectPage`.
   - `type ProjectListParams = { q?, status?, includeArchived?, sort?, direction?, page? }`.
     `listProjects(params)` builds `URLSearchParams`, skipping defaults and empties, and calls
     `api.get<ProjectPage>("/api/projects?…")`.
   - Also add the calls tasks 08–10 use, typed now so the module's shape is settled:
     `getProject(id)`, `createProject(input)`, `updateProject(id, input)`,
     `archiveProject(id)`, `restoreProject(id)`, `getProjectSummary()`, and
     `projectFieldErrors(error): Record<string, string>` (reads `problemDetail(error).errors`
     when it's an object of strings).
   - `type ProjectInput = { name: string; repositoryUrl: string; description: string; status:
     ProjectStatus }`.
2. **URL state:** `parseListParams(searchParams)` and `toSearchParams(params)` in the route
   file.
   - Keys: `q`, `status`, `archived` (`1` when on), `sort`, `dir`, `page`.
   - Unknown values fall back to defaults instead of erroring.
   - Changing any filter resets `page` to 1.
3. **`routes/projects.tsx`**:
   - `meta` title "Projects".
   - `clientLoader({ request })` reads params from `request.url` and returns
     `listProjects(...)` awaited. Errors are caught and returned as `{ error: apiErrorMessage(e) }`
     so the page can render a retry. Set `clientLoader.hydrate = true` and add a
     `HydrateFallback` with a `Spinner`, as in the layout.
   - **Header:** `PageHeader` "Projects" with a "New project" `Button` linking to
     `/projects/new` (`render={<Link …/>}` and `nativeButton={false}`). The route arrives in
     task 09; until then the link 404s, which is fine while the page is unlinked.
   - **Toolbar** (a `Form` with `method="get"`, so the URL drives state):
     - a search `InputGroup` with a search icon, submitting on Enter and debounced 300 ms with
       `useSubmit`;
     - a status `Select` with "Any status" plus the four;
     - a "Show archived" `Switch` with a `Label`.

     It wraps on phone width.
   - **Table** (`components/ui/table`) with columns Name (link to `/projects/:id`), Status
     (`ProjectStatusBadge`, plus an "Archived" badge when archived), Repository (`owner/repo`
     text with the full URL as `title`, plus "Repository not verified" when false), and Last
     updated (`Intl.DateTimeFormat` medium date and short time).
     - Name, Status and Last updated headers are buttons that toggle sort: a new column uses its
       default direction, the same column flips it. Set `aria-sort` on the header, and show an
       arrow icon.
     - The table sits in a horizontally scrollable wrapper (`overflow-x-auto`), so the page itself
       never scrolls sideways at 375 px. Hide the Repository column below `sm` and show
       `owner/repo` under the name instead.
   - **Pagination** (`components/ui/pagination`): Previous and Next plus page numbers with
     ellipsis, built as links to the same URL with `page` changed. Hide it when `totalPages <= 1`.
     Show "Showing 21–40 of 57".
   - **Empty states** (`Empty`):
     - no projects at all (`totalItems === 0` with no filters): "No projects yet" with a "New
       project" button;
     - filters active and no match: "No projects match" with a "Clear filters" link to
       `/projects`.
   - **Error:** an `Alert` (destructive) with the message and a "Try again" button calling
     `useRevalidator().revalidate()`.
   - Loading between navigations uses the layout's existing progress bar.
4. **`ProjectStatusBadge`**: tone mapping `planning` → neutral, `active` → positive,
   `maintenance` → warning, `retired` → neutral (with `STATUS_LABEL`). The "Archived" badge is
   `Badge variant="secondary"`; "Repository not verified" is a `StatusBadge` with the warning
   tone.
5. If a component is missing, add it with the shadcn CLI. Don't hand-edit `components/ui/`.
6. Format the touched files with Prettier.

## Tests

The app has no test runner; verify by hand against the running stack (api from task 04 with some
seeded projects):
- Signed out, `/projects` redirects to `/login` (AC-2).
- A project saved with `repositoryVerified: false` shows "Repository not verified" in its row
  (AC-9).
- 25 projects: 20 on page 1, newest updated first; Next shows 5 (AC-12).
- Searching "fac" filters across pages, ignoring case (AC-13).
- The status filter shows one status; "Any status" shows all again (AC-14).
- Archived projects are hidden until "Show archived" is on, then appear with the badge (AC-15).
- Sorting by each column; clicking again reverses it (AC-16).
- Reload, copy and paste the URL into a new tab, and Back: the same view each time (AC-17).
- With no projects: "No projects yet". With a nonsense search: "No projects match" and "Clear
  filters" (AC-18).
- Stop the api: the page shows the error and "Try again" works after it starts (AC-29).
- At 375 px width: no horizontal page scroll, and the toolbar wraps (AC-30).

## Checks

- `docker exec skeleton-app npm run typecheck`
- `docker exec skeleton-app npm run build`
- `docker exec skeleton-app npx prettier --check app/lib/projects.ts app/routes/projects.tsx
  app/routes.ts app/components/projects/project-status-badge.tsx`

## Out of scope

- Detail page (task 08), create and edit form (task 09), Dashboard and nav item (task 10).
