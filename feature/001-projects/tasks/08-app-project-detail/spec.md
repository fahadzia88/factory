---
feature: 001-projects
task: 08-app-project-detail
layer: app
status: approved
approved: 2026-11-04
branch: feat/001-projects/08-app-project-detail
base: feat/001-projects/07-app-project-list
covers: [AC-4, AC-9, AC-19, AC-20, AC-21, AC-22, AC-23, AC-29, AC-30]
---

# 08 — Project detail page

## Goal

Add `/projects/:id`: every field of a project, the "Repository not verified" marker, archive
with confirmation, restore, an archived read-only view, and a not-found page.

## Contract

Consumes, from task-breakdown.md:

- `GET /api/projects/{id}` → `200` Project, archived or not; `404` when no project has that id.
- `POST /api/projects/{id}/archive` and `POST /api/projects/{id}/restore`: no body → `200`
  Project. Idempotent, and `status` doesn't change. `404` for an unknown id.
- There is no `DELETE` route.
- Project JSON: `{ id, name, repositoryUrl, description|null, status, archived,
  repositoryVerified, createdAt, updatedAt }`.

## Files

- `app/app/routes/project-detail.tsx` — new.
- `app/app/routes.ts` — change: add `route("projects/:id", "routes/project-detail.tsx")` inside
  the app layout.

## Steps

1. **`clientLoader({ params })`**:
   - call `getProject(Number(params.id))`;
   - on `ApiError` 404, or a non-numeric id, return `{ notFound: true }`;
   - on another error return `{ error: apiErrorMessage(e) }`.

   Set `hydrate = true` and add a `HydrateFallback`.
2. **`clientAction({ request, params })`**: the form field `intent` is `archive` or `restore`.
   Call `archiveProject` or `restoreProject`, then return `{ ok: true }` (the loader revalidates).
   On error, return `{ error: apiErrorMessage(e) }` and show it in an `Alert`. Show a success
   toast, "Project archived" or "Project restored" (sonner, which is already mounted).
3. **Not found:** an `Empty` with "Project not found", "It may have been removed, or the link is
   wrong.", and a "Back to projects" link (AC-23).
4. **Page:**
   - `Breadcrumb`: Projects (link to `/projects`) › name.
   - `PageHeader`: the project name, with the status badge, and the "Archived" badge when
     archived.
   - Actions in the header:
     - **non-archived:** "Edit" (`Button` link to `/projects/:id/edit`; the route arrives in task
       09), and "Archive";
     - **archived:** "Restore" only. No Edit (AC-20).
   - No delete action anywhere (AC-22).
5. **Archive confirmation:**
   - a `Dialog` titled "Archive <name>?", with the body "It's hidden from the projects list and the
     Dashboard. You can restore it at any time.";
   - "Cancel", and "Archive" (submits the `Form` with `intent=archive`).

   Restore submits directly, with no dialog (AC-19, AC-21).
6. **Archived notice:** when archived, an `Alert` at the top reads "This project is archived. Restore
   it to make changes." (AC-20).
7. **Details `Card`** (a `dl` grid, one column on phones and two from `sm`):
   - Repository: a link opening in a new tab with `rel="noreferrer"`, plus the "Repository not
     verified" badge and the hint "GitHub couldn't be reached when it was saved. Saving the
     project again re-checks it." when unverified (AC-9);
   - Status;
   - Description, or "No description" in muted text, keeping line breaks (`whitespace-pre-wrap`);
   - Created and Last updated, both formatted as in task 07.
8. **Error state:** an `Alert` with "Try again" (revalidate), as in task 07 (AC-29).
9. Buttons use `useNavigation()` to disable themselves and show a `Spinner` while submitting.
10. Format the touched files with Prettier.

## Tests

By hand against the running stack:
- Open a project from the list: every field shows, and the repository link opens GitHub (AC-4).
- A project with `repositoryVerified: false` shows the badge and the hint (AC-9).
- Archive: the dialog appears; Cancel keeps it; Archive shows the toast, the badge and the notice,
  and the project is gone from the default list and back with "Show archived" (AC-19).
- An archived project shows no Edit, and Restore is available (AC-20).
- Restore: the badge is gone, Edit is back, and the status is unchanged (AC-21).
- No delete control is visible (AC-22).
- `/projects/99999` and `/projects/abc` show "Project not found" (AC-23).
- With the api stopped, the error shows with "Try again" (AC-29).
- At 375 px, no horizontal page scroll (AC-30).

## Checks

- `docker exec skeleton-app npm run typecheck`
- `docker exec skeleton-app npm run build`
- `docker exec skeleton-app npx prettier --check app/routes/project-detail.tsx app/routes.ts`

## Out of scope

- The edit and create forms (task 09). The Edit button's target route arrives there.
