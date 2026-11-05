---
feature: 001-projects
task: 10-app-dashboard-nav
layer: app
status: approved
approved: 2026-11-04
branch: feat/001-projects/10-app-dashboard-nav
base: feat/001-projects/09-app-project-form
covers: [AC-1, AC-24, AC-25, AC-29, AC-30]
---

# 10 — Dashboard summary and Projects nav item

## Goal

Release the feature: add "Projects" to the top nav, and replace the Dashboard's placeholder with
per-status counts and recent projects (or a "New project" empty state).

## Contract

Consumes, from task-breakdown.md:

- `GET /api/projects/summary` → `200`
  `{ "counts": { "planning": 2, "active": 5, "maintenance": 1, "retired": 0 }, "recent":
  [Project] }`.
- Only non-archived projects. `counts` always has all four keys; `recent` holds at most 5, most
  recently updated first.

## Files

- `app/app/routes/app-layout.tsx` — change: add `{ to: "/projects", label: "Projects" }` to
  `NAV_ITEMS`, between Dashboard and Assistant.
- `app/app/routes/dashboard.tsx` — change: load the summary and render counts, recent projects
  and the empty state.

## Steps

1. **Nav:** add the item. `NavLink`'s active state covers `/projects/*` by default, which is what
   we want. Check the header still fits at 375 px: the labels collapse no further than they do
   today, so if three items plus Sign out overflow, let the `nav` scroll horizontally
   (`overflow-x-auto`) instead of wrapping (AC-1, AC-30).
2. **`dashboard.tsx`**:
   - `clientLoader` returns `getProjectSummary()`. On error it returns `{ error }`, rendered as
     an `Alert` with "Try again" (AC-29). Set `hydrate = true` and add a `HydrateFallback`.
   - **Empty state:** when all counts are 0, keep the existing `Empty` layout with the title "No
     projects yet", the description "Add the codebases the factory works on.", and a "New
     project" button linking to `/projects/new` (AC-25).
   - **Otherwise:**
     - `PageHeader` "Dashboard" with a "New project" button.
     - A grid of four count `Card`s (two columns on phones, four from `md`). Each shows the status
       label and the count, and the whole card links to `/projects?status=<status>` (AC-24).
     - A "Recent projects" `Card` listing up to 5 projects: the name links to `/projects/:id`,
       with the status badge, `owner/repo`, and the last updated time. Its footer has "View all"
       linking to `/projects`.
   - Reuse `ProjectStatusBadge` and the date formatting from task 07. If the formatter lives in
     the list route, move it to `lib/projects.ts`.
3. Format the touched files with Prettier.

## Tests

By hand against the running stack:
- Every page in the layout shows "Projects" in the nav; it opens `/projects` and is highlighted
  there and on detail pages (AC-1).
- With projects, the Dashboard counts match the list's status filter for non-archived projects.
  Each count card opens the filtered list. "Recent projects" shows the 5 most recently updated,
  and "View all" opens `/projects` (AC-24).
- Archive every project: the Dashboard shows the empty state, and "New project" opens the form
  (AC-25).
- With the api stopped, the Dashboard shows the error and "Try again" (AC-29).
- At 375 px, neither the header nor the Dashboard scrolls the page sideways (AC-30).

## Checks

- `docker exec skeleton-app npm run typecheck`
- `docker exec skeleton-app npm run build`
- `docker exec skeleton-app npx prettier --check app/routes/app-layout.tsx app/routes/dashboard.tsx`
  (plus `app/lib/projects.ts` if changed)

## Out of scope

- Charts or trends over time.
