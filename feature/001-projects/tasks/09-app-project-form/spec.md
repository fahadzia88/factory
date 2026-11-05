---
feature: 001-projects
task: 09-app-project-form
layer: app
status: approved
approved: 2026-11-04
branch: feat/001-projects/09-app-project-form
base: feat/001-projects/08-app-project-detail
covers: [AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-11, AC-30]
---

# 09 — Create and edit form

## Goal

Add `/projects/new` and `/projects/:id/edit`, sharing one form. The status starts on `planning`
for new projects. The API's field errors show under their fields, and entered values are kept
when a save fails.

## Contract

Consumes, from task-breakdown.md:

- `POST /api/projects` with `{ name, repositoryUrl, description?, status? }` → `201` Project.
- `PUT /api/projects/{id}` with the same fields, `status` required → `200` Project.
- Errors:
  - `400` and `409` carry a `ProblemDetail` with an `errors` map whose keys are `name`,
    `repositoryUrl`, `description` and `status`;
  - `409` without `errors` when the project is archived ("Archived projects can't be edited.
    Restore it first.");
  - `404` for an unknown id.
- Limits: the name is required and at most 100 characters; the URL is required and
  `https://github.com/<owner>/<repo>`; the description is at most 2,000 characters.

## Files

- `app/app/components/projects/project-form.tsx` — new: the shared form fields.
- `app/app/routes/project-new.tsx` — new.
- `app/app/routes/project-edit.tsx` — new.
- `app/app/routes.ts` — change: add `route("projects/new", …)` and
  `route("projects/:id/edit", …)` inside the app layout. List `projects/new` before
  `projects/:id`, or rely on React Router ranking; check it doesn't open the detail page.

## Steps

1. **`ProjectForm({ defaultValues, errors, formError, submitting, submitLabel, cancelTo })`**
   renders a React Router `Form method="post"` with `FieldGroup`:
   - Name (`Input`, `maxLength={100}`, required, `autoFocus` on new);
   - Repository URL (`Input type="url"`, placeholder `https://github.com/owner/repo`, with a
     `FieldDescription` "A GitHub repository. Private repositories work if the server's token can
     see them.");
   - Status (`Select` named `status` with the four statuses);
   - Description (`Textarea`, `maxLength={2000}`, rows 4).

   Each field shows `FieldError` from `errors[field]` and gets `aria-invalid` and
   `aria-describedby`. The submit `Button` shows a `Spinner` while submitting, next to a "Cancel"
   link to `cancelTo`. A `formError` without field errors shows in an `Alert` above the fields.
2. **Keeping values:** the action returns `{ errors, formError, values }` on failure, and the
   form uses `values ?? defaultValues` as `defaultValue`s. Give the form a `key` that changes per
   submit, so the inputs re-render with the returned values (AC-5).
3. **`project-new.tsx`**:
   - `meta` "New project".
   - No loader data is needed. Set `defaultValues = { status: "planning" }` (AC-3).
   - `clientAction`: read the `FormData` and call `createProject`.
     - Success: `redirect(`/projects/${p.id}`)` and toast "Project created" (AC-4).
     - `ApiError` 400 or 409: return `{ errors: projectFieldErrors(e), formError, values }`, where
       `formError` is `apiErrorMessage(e)` only when there are no field errors.
     - Other errors: `{ formError: apiErrorMessage(e), values }`.
   - `PageHeader` "New project", with a breadcrumb back to Projects.
4. **`project-edit.tsx`**:
   - `clientLoader` loads the project, as in task 08 (404 → "Project not found").
   - An archived project shows the archived `Alert` and a link back to the detail page instead of
     the form.
   - `clientAction` calls `updateProject(id, input)` with the same error handling. A 409 without
     field errors (archived meanwhile) becomes the `formError`.
   - Success: `redirect(`/projects/${id}`)` and toast "Changes saved" (AC-11).
   - The `PageHeader` is "Edit <name>".
5. **Client-side hints only:** the browser's `required`/`maxLength`/`type=url` checks are fine.
   The API remains the source of truth for format, uniqueness and the GitHub check, and its
   messages are shown as they are (AC-5–AC-8).
6. The form is one column at every width; the action row wraps on phones (AC-30).
7. Format the touched files with Prettier.

## Tests

By hand against the running stack (api with `API_GITHUB_TOKEN` set):
- `/projects/new` starts with status Planning, and all four can be picked (AC-3).
- Valid input → the detail page with every field and the "Project created" toast (AC-4).
- An empty name, `https://gitlab.com/a/b`, or a 2,001-character description (pasted, bypassing
  `maxLength` via devtools) shows the API's errors under the fields with the values kept (AC-5).
- The name "FACTORY" when "Factory" exists → error under Name (AC-6).
- An exact duplicate URL → error under Repository URL. The same URL with different case is
  accepted (AC-7).
- `https://github.com/acme/does-not-exist-123` → "Repository not found on GitHub." under the
  field (AC-8).
- Edit the name only → saved, and back on the detail page with the new updated time. Change the
  URL to a missing repo → error under the field (AC-11).
- At 375 px, the form has no horizontal scroll (AC-30).

## Checks

- `docker exec skeleton-app npm run typecheck`
- `docker exec skeleton-app npm run build`
- `docker exec skeleton-app npx prettier --check app/components/projects/project-form.tsx
  app/routes/project-new.tsx app/routes/project-edit.tsx app/routes.ts`

## Out of scope

- The Dashboard and nav item (task 10).
- Client-side duplicate checks or live GitHub lookups while typing.
