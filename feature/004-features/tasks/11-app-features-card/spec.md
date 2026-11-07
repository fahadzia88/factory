---
feature: 004-features
task: 11-app-features-card
layer: app
status: approved
approved: 2026-11-06
branch: feat/004-features/11-app-features-card
base: feat/004-features/10-app-feature-chat
covers: [AC-2, AC-4, AC-5, AC-8, AC-9, AC-24, AC-28, AC-29]
---

# 11 — Features card and "New feature" form

## Goal

The project page gets a "Features" card listing the project's features, with a "New feature"
action when the project isn't archived and its checkout is ready. The action opens a form at
`/projects/:id/features/new` that creates the feature and lands on its page. This task makes the
feature reachable.

## Contract

Consumes, from task-breakdown.md:

- Every route needs `Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as
  `ProblemDetail` (`application/problem+json`) with a `code` property the app and agent switch on
  and a `detail` they can show as is. On every route: `404 project_not_found`,
  `404 feature_not_found` (no such feature, or it belongs to another project),
  `400 invalid_request` (non-numeric id, unreadable body, missing required field).

- **Feature (JSON):** as in task 09. Feature summary (list rows): `id`, `number`, `code`,
  `name`, `status`, `updatedAt`.

- **Routes**

  | Method & path | Request | Response | Errors (`code`) |
  | ------------- | ------- | -------- | ---------------- |
  | `GET /api/projects/{id}/features` | — | `200` `[Feature summary]`, newest number first | — |
  | `POST /api/projects/{id}/features` | `{ "name": "Features" }` | `201` Feature | `400 invalid_name`, `409 project_archived`, `409 checkout_not_ready`, `409 workflow_invalid`, `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout`, `500 git_error` |

- **Create sequence (api)**
  1. Project not archived (`409 project_archived`), checkout ready (`409 checkout_not_ready`; no
     checkout also answers this code).
  2. Validate the name (trimmed, 1–100, slug non-empty) → `400 invalid_name`.
  3. Fetch (003), read and validate the workflow and template → `409 workflow_invalid`, `detail`
     names the file and the problem, e.g. `feature/workflow.json: steps[1].skill "feature-plan" has no .claude/skills/feature-plan/SKILL.md`.
  4. Number, then `WorktreeService.add(projectId, branch, defaultBranch)`.
  5. Write `<folder>/requirements.md` from the template with `<NNN>-<slug>` → code,
     `<Feature name>` → name, `<date>` → today (UTC, from `Clock`).
  6. `git add <folder>` and `git commit` in the worktree with author and committer from
     `app.feature.commit-name` / `app.feature.commit-email`, message `<code>: start feature`.
  7. `WorktreeService.push(…, force = false)`.
  8. Save the feature in `featureStates[0]`.

  Any failure in 5–8 removes the worktree (`force`) and the branch, saves nothing, and answers
  with the failure's own code (`502 git_remote_error`, `504 git_timeout`, `500 git_error`).

- **Checkout (003):** `GET /api/projects/{id}/checkout` → `200` Checkout with `state`
  (`cloning`, `ready`, `failed`), or `404 checkout_not_found`.

- **app → api:** `lib/features.ts` with the types and calls through `lib/api.ts`. Errors show the
  problem's `detail`; `5xx` codes get "Try again".

## Files

- `app/app/routes/project-features.ts` — new: component-less route whose `clientLoader` returns
  the list and whether a feature can be created, for the card's fetcher (like
  `project-pulls.ts`).
- `app/app/components/features/features-card.tsx` — new: `FeaturesCard({ projectId, archived })`.
- `app/app/routes/feature-new.tsx` — new: the form page (`clientLoader`, `clientAction`,
  component).
- `app/app/routes/project-detail.tsx` — change: render `<FeaturesCard projectId={project.id}
  archived={project.archived} />` after `ProjectDetails`, before the Pull requests card.
- `app/app/routes.ts` — change (**shared**; additive, the user confirms):
  `route("projects/:id/features", "routes/project-features.ts")` and
  `route("projects/:id/features/new", "routes/feature-new.tsx")`, declared before
  `projects/:id/features/:featureId`.

## Steps

1. **`project-features.ts` `clientLoader({ params })`:**
   - `id = projectId(params.id)`; `null` → `{ features: null, canCreate: false, blocked: null,
     error: featureError(new Error("Invalid project")) }`.
   - `Promise.allSettled([listFeatures(id), getCheckout(id)])`.
   - Features rejected → `{ features: null, error: featureError(reason) }`.
   - `canCreate` = checkout fulfilled with `state === "ready"`. `blocked` = a reason to show
     when not: `checkout_not_found` → "Check out the repository below to start a feature.";
     `cloning` → "The repository is still being cloned."; `failed` or a checkout error → "The
     repository checkout isn't ready." (`archived` is handled in the card).
2. **`FeaturesCard({ projectId, archived })`**, modelled on `PullRequestsCard`:
   - `useFetcher<typeof clientLoader>()`; `load(\`/projects/${projectId}/features\`)` in an
     effect on `[load, projectId, archived]`, so a restore reloads it.
   - `CardHeader`: `CardTitle` "Features"; `CardAction` holds "New feature" (`PlusIcon`,
     `size="sm"`, `render={<Link to={\`/projects/${projectId}/features/new\`} />}`,
     `nativeButton={false}`) only when `!archived && data?.canCreate` (AC-4).
   - Content:
     - no data yet: three `Skeleton` rows;
     - `error`: `Alert variant="destructive"` "Couldn't load features" with the message and "Try
       again" (reloads the fetcher) when retryable. The rest of the project page keeps working
       (AC-28);
     - empty list: `Empty` (no border) with `ListTodoIcon`, "No features yet", description
       "Start a feature to plan it with the agent."; the "New feature" button inside
       `EmptyContent` when allowed; otherwise the `blocked` text (or "Archived projects can't
       start features." when `archived`) as the description (AC-24);
     - list: a `ul` with `divide-y`, newest number first (as the API sends it). Each `li` is a
       `Link` to `/projects/:id/features/:featureId` covering the row (`flex flex-col gap-1 py-3
       sm:flex-row sm:items-center sm:justify-between`, hover `bg-muted/50`, focus ring): the
       number (`featureNumber`) in muted `font-mono` and the name (`min-w-0 break-words`
       `font-medium`) on the left; `FeatureStatusBadge` and "Updated
       <formatDateTime(updatedAt)>" in muted `text-xs` on the right (AC-24).
     - When the list isn't empty but create isn't allowed and the project isn't archived, a
       muted line under the list with the `blocked` text.
3. **`feature-new.tsx`:**
   - `clientLoader({ params })`: `getProject(id)`; `404` or bad id → `{ notFound: true }`;
     other errors → `{ error: apiErrorMessage(error) }`; archived → `{ project, archived: true
     }`. `clientLoader.hydrate = true as const` and the centred `Spinner` `HydrateFallback`.
   - Not found → `ProjectNotFound`. Archived → an `Alert` "Archived projects can't start
     features." with "Back to project". Load error → the destructive `Alert` with Try again, as
     on the project page.
   - Page: `ProjectBreadcrumb` (`project`, `name="New feature"`), `PageHeader title="New
     feature"`, then a `Card` with a `Form method="post"`:
     - `FieldGroup` with one `Field` (`data-invalid` when there's a name error): `FieldLabel`
       "Name" for `Input id="name" name="name"` (`maxLength={100}`, `autoFocus`, `required`,
       `defaultValue` from the last attempt, `aria-invalid` and `aria-describedby` to the
       error); `FieldDescription` "Used for the folder and branch, for example
       feature/005-feature-records."; `FieldError` with the message (AC-8).
     - a form-level `Alert variant="destructive"` for any other error, titled "Couldn't create
       the feature", with the message; for retryable ones (`5xx`, network) the text adds
       "Try again." and the submit button stays enabled so the user can resubmit (AC-9).
       `workflow_invalid` shows the API's `detail` as is, which names the file and problem
       (AC-2). `checkout_not_ready` and `project_archived` show their `detail` with a link
       "Back to project".
     - buttons: submit "Create feature" (`Spinner` while submitting; disabled while
       submitting) and "Cancel" (`variant="outline"`, link to `/projects/:id`).
     - A note under the form in muted text while submitting: "Creating the branch and pushing
       it to GitHub…" (create takes a fetch, a commit and a push).
   - `clientAction({ request, params })`: read `name`; `createFeature(id, name)`; on success
     `toast.success("Feature created")` and `redirect(\`/projects/${id}/features/${feature.id}\`)`
     (AC-5). On error, `featureError(error)`: `invalid_name` → `{ errors: { name: message },
     values: { name } }`; anything else → `{ formError: message, retryable, code, values: {
     name } }`. The typed name stays in the field (AC-8, AC-9).
   - Client checks before sending: a trimmed empty name → "Enter a name." without calling the
     API. Everything else (length, no letters or digits) is left to the API's `invalid_name`.
4. **Layout** (AC-29): the card's rows and the form work at 375 px; names wrap; the buttons
   wrap under each other (`flex flex-wrap gap-2`).
5. Prettier on the touched files.

## Tests

The app has no unit test setup. By hand, against the running stack (tasks 02–10 in the stack)
with a project whose checkout is ready and whose default branch has a valid
`feature/workflow.json` and `feature/_templates/requirements.md`:

- The project page shows the Features card with "New feature". An archived project and one
  without a ready checkout (none, cloning, failed) show the card without it, with the reason
  (AC-4).
- With no features, the card shows "No features yet" with "New feature" (AC-24).
- New feature → "Feature records" → Create: the page lands on the new feature showing the next
  number, the name, the first state, `feat/<NNN>-feature-records/00-docs` and the worktree path;
  back on the project page, the card lists it first with its status and updated time and links
  to it (AC-5, AC-24).
- An empty name, 101 characters, or "!!!" → the field shows the error, nothing is created, and
  the typed text stays (AC-8).
- A project whose default branch lacks `feature/workflow.json`, or has one naming a missing
  skill → the form shows the API's message naming the file and the problem; no feature appears
  in the card and no new worktree on the checkout card (AC-2).
- With the network cut from `factory-api` (or `API_GIT_TOKEN` unset) → the form shows the git
  error with "Try again."; resubmitting after fixing it creates the feature (AC-9).
- With the API stopped, the card shows "Couldn't load features" with Try again and the rest of
  the project page still renders its other cards' errors independently (AC-28).
- At 375 px, the card, the form and their errors don't cause horizontal scroll (AC-29).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/routes/project-features.ts app/components/features/features-card.tsx app/routes/feature-new.tsx app/routes/project-detail.tsx app/routes.ts`

## Out of scope

- Rename, archive or delete of features (non-goal).
- Features on the Dashboard or in the top nav.
- Changing the checkout card.
