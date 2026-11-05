---
feature: 001-projects
task: 12-app-verify-repository
layer: app
status: approved
approved: 2026-11-05
branch: feat/001-projects/12-app-verify-repository
base: feat/001-projects/11-api-project-verify
covers: [AC-31]
---

# 12 — "Verify repository" button

## Goal

On the detail page of an unverified, non-archived project, add a "Verify repository" button that
runs the GitHub check again.

## Contract

Consumes `POST /api/projects/{id}/verify` from task 11: `200` Project; `409` archived; `422`
repository not found; `503` GitHub unreachable. Errors carry a `ProblemDetail` `detail` meant for
the user.

## Files

- `app/app/lib/projects.ts` — change: add `verifyProject(id)`.
- `app/app/routes/project-detail.tsx` — change: the button, the `verify` intent, the hint text.

## Steps

1. **`verifyProject(id)`** = `api.post<Project>(\`/api/projects/${id}/verify\`)`.
2. **`clientAction`:** handle `intent === "verify"`: call `verifyProject`, then toast "Repository
   verified". Errors return `{ error: apiErrorMessage(e) }` and show in the existing "That didn't
   work" `Alert`; the loader revalidates either way.
3. **Button:** in the Repository row of the details card, after the "Repository not verified"
   badge, when `!project.repositoryVerified && !project.archived`: a small outline `Button`
   (`size="sm"`) in a `Form method="post"`, `name="intent" value="verify"`, with `RefreshCwIcon`,
   or a `Spinner` while submitting (`useSubmitting("verify")`), reading "Verify repository".
4. **Hint:** when unverified, it reads "GitHub couldn't be reached when it was saved. Verify it
   again, or save the project to re-check it." For an archived project, keep the old hint, since
   it can't be verified until restored.
5. Format the touched files with Prettier.

## Tests

By hand against the running stack (AC-31):
- An unverified project shows the button; a verified or archived one doesn't.
- With GitHub reachable and the repository real: click → the toast, the badge and the button are
  gone, and "Last updated" is unchanged.
- With the API token unset (unavailable): click → the "try again later" message shows, and the
  badge stays.
- At 375 px, the row wraps without horizontal scroll.

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/lib/projects.ts app/routes/project-detail.tsx`

## Out of scope

- A verify action on the list page or for the Assistant.
