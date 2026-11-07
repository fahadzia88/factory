---
feature: 004-features
task: 09-app-feature-page
layer: app
status: approved
approved: 2026-11-06
branch: feat/004-features/09-app-feature-page
base: feat/004-features/08-agent-feature-write-tools
covers: [AC-5, AC-11, AC-12, AC-13, AC-14, AC-15, AC-21, AC-25, AC-28, AC-29]
---

# 09 — Feature page

## Goal

Add `lib/features.ts` (types, calls, errors) and the feature page
`/projects/:id/features/:featureId`: header, details (number, status, branch, worktree path or
"Missing", times) and the steps list. The page has no status controls. Nothing links to it yet:
task 11 adds the Features card. The chat comes in task 10.

## Contract

Consumes, from task-breakdown.md:

- Every route needs `Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as
  `ProblemDetail` (`application/problem+json`) with a `code` property the app and agent switch on
  and a `detail` they can show as is. On every route: `404 project_not_found`,
  `404 feature_not_found` (no such feature, or it belongs to another project),
  `400 invalid_request` (non-numeric id, unreadable body, missing required field).

- **Feature (JSON)**

  ```json
  {
    "id": 21,
    "projectId": 12,
    "number": 4,
    "code": "004-features",
    "name": "Features",
    "slug": "features",
    "status": "requirements",
    "branch": "feat/004-features/00-docs",
    "folder": "feature/004-features",
    "worktree": { "id": 9, "path": "/repos/12.worktrees/feat__004-features__00-docs", "missing": false },
    "workflow": { "featureStates": ["…"], "steps": [{ "key": "…", "title": "…", "skill": "…", "states": ["…"] }] },
    "steps": [
      {
        "id": 5,
        "position": 1,
        "type": "requirements",
        "typeTitle": "Requirements",
        "title": "Requirements",
        "documentPath": "feature/004-features/requirements.md",
        "status": "draft",
        "states": ["draft", "approved"],
        "createdAt": "2026-11-06T10:00:00Z",
        "updatedAt": "2026-11-06T10:00:00Z"
      }
    ],
    "createdAt": "2026-11-06T10:00:00Z",
    "updatedAt": "2026-11-06T10:00:00Z"
  }
  ```

  - `worktree` is `null` when no worktree with `branch` exists; the app shows that as missing too.
    `missing` is 003's flag (folder not on disk).
  - Feature summary (list rows): `id`, `number`, `code`, `name`, `status`, `updatedAt`.

- **Routes** (the rows `lib/features.ts` wraps; this task's page calls only the `GET` of one
  feature):

  | Method & path | Request | Response | Errors (`code`) |
  | ------------- | ------- | -------- | ---------------- |
  | `GET /api/projects/{id}/features` | — | `200` `[Feature summary]`, newest number first | — |
  | `POST /api/projects/{id}/features` | `{ "name": "Features" }` | `201` Feature | `400 invalid_name`, `409 project_archived`, `409 checkout_not_ready`, `409 workflow_invalid`, `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout`, `500 git_error` |
  | `GET /api/projects/{id}/features/{featureId}` | — | `200` Feature | — |
  | `GET /api/projects/{id}/features/{featureId}/messages` | — | `200` `[{ "id", "role": "user"\|"assistant", "text", "createdAt" }]`, oldest first | — |
  | `POST /api/projects/{id}/features/{featureId}/messages` | `{ "message": "Start the requirements" }` | `200` `application/x-ndjson`: the agent's events (below) | before the stream: `400 invalid_request` (blank), `409 chat_busy`, `502 agent_unavailable` |
  | `POST /api/projects/{id}/features/{featureId}/messages/cancel` | — | `200` `{ "cancelled": true }` | — |

  There is no rename, archive or delete route for features or steps.

- **app → api:** `lib/features.ts` with the types and calls through `lib/api.ts`. The chat reads
  the NDJSON stream with `fetch` (same-origin `/api`, bearer token from `lib/password.ts`), since
  `api()` reads whole bodies. Errors show the problem's `detail`; `5xx` codes get "Try again".

## Files

- `app/app/lib/features.ts` — new: types, the JSON calls, `featureError()`, `featureId()`.
- `app/app/routes/feature-detail.tsx` — new: the page (`clientLoader`, `HydrateFallback`,
  component).
- `app/app/components/features/feature-not-found.tsx` — new: `FeatureNotFound({ projectId })`.
- `app/app/components/features/feature-steps.tsx` — new: `FeatureSteps({ steps })`, the read-only
  list.
- `app/app/components/features/feature-status-badge.tsx` — new: `FeatureStatusBadge` and
  `StepStatusBadge`.
- `app/app/routes.ts` — change (**shared** base file; additive, the user confirms the edit): add
  `route("projects/:id/features/:featureId", "routes/feature-detail.tsx")` inside the app layout,
  after the pulls routes. No `NAV_ITEMS` change.

## Steps

1. **`lib/features.ts`**, in the style of `lib/checkouts.ts`:
   - Types `FeatureWorkflowStep { key; title; skill; states: string[] }`,
     `FeatureWorkflow { featureStates: string[]; steps: FeatureWorkflowStep[] }`,
     `FeatureWorktree { id: number; path: string; missing: boolean }`,
     `FeatureStep` (the step fields above, `documentPath: string | null`),
     `Feature` (the JSON above, `worktree: FeatureWorktree | null`),
     `FeatureSummary { id; number; code; name; status; updatedAt }`,
     `FeatureMessage { id: number; role: "user" | "assistant"; text: string; createdAt: string }`.
   - `const featuresPath = (projectId: number) => \`/api/projects/${projectId}/features\``.
   - `listFeatures(projectId)` → `api.get<FeatureSummary[]>`;
     `getFeature(projectId, featureId)` → `api.get<Feature>`;
     `createFeature(projectId, name)` → `api.post<Feature>(path, { name })`;
     `listFeatureMessages(projectId, featureId)` → `api.get<FeatureMessage[]>`;
     `cancelFeatureChat(projectId, featureId)` → `api.post<{ cancelled: boolean }>`.
     The streaming send is added in task 10.
   - `FeatureErrorCode` union of the codes in the routes above plus `project_not_found`,
     `feature_not_found`, `invalid_request`.
   - `FeatureError { status: number | null; code: string | null; message: string; retryable:
     boolean }` and `featureError(error: unknown)`: the same rules as `checkoutError` (the
     problem's `detail` first, except for `401`; `apiErrorMessage` otherwise; `retryable` when
     there was no response or `status >= 500`).
   - `featureId(raw)`: a positive integer or `null`, like `projectId` in `lib/projects.ts`.
   - `featureNumber(n)`: `String(n).padStart(3, "0")`.
   - `stateLabel(state)`: the state with `-` → space and the first letter upper-cased
     (`"in-review"` → "In review"). States come from each project's workflow, so there's no fixed
     label map.
2. **`clientLoader({ params })`** in `routes/feature-detail.tsx`, modelled on
   `pull-request-detail.tsx`:
   - `id = projectId(params.id)`, `fid = featureId(params.featureId)`; either `null` →
     `{ notFound: true, projectId: id, … }`.
   - `Promise.allSettled([getProject(id), getFeature(id, fid)])`.
   - Project `404` → `notFound` with `projectId: null` (the not-found page links to the list).
   - Feature rejected: code `feature_not_found` or `project_not_found` → `notFound` with the
     project id; anything else → `{ error: featureError(reason) }`.
   - Success → `{ project: { id, name, archived }, feature }` (`project` `null` if only the
     project call failed; the breadcrumb then says "Project").
   - `clientLoader.hydrate = true as const` and a `HydrateFallback` with the centred `Spinner`.
   - `meta`: `\`${featureNumber(feature.number)} ${feature.name}\``, or "Feature".
3. **Not found** (AC-25): `FeatureNotFound` copies `PullRequestNotFound` with `FileQuestionIcon`,
   title "Feature not found", description "It may belong to another project, or the link is
   wrong." and "Back to project" (or "Back to projects" when `projectId` is `null`).
4. **Error** (AC-28): `Alert variant="destructive"` titled "Couldn't load the feature" with the
   message and, when `retryable`, "Try again" calling `useRevalidator().revalidate()` (disabled
   while loading), as on the pull request page.
5. **Page:**
   - `ProjectBreadcrumb` with `project`, `name={feature.name}` and
     `page={featureNumber(feature.number)}`.
   - `PageHeader` title: a `flex flex-wrap items-center gap-2` span with the name
     (`min-w-0 break-words`), the number in muted text, and `FeatureStatusBadge`. No header
     buttons: no rename, archive, delete or status change (AC-15, AC-26).
   - A details `Card` with a `dl` grid (`grid gap-x-6 gap-y-5 sm:grid-cols-2`), using a local
     `Detail` like the pull request page:
     - "Status": `FeatureStatusBadge`;
     - "Branch": `feature.branch` in `font-mono text-sm break-all`;
     - "Folder": `feature.folder` in `font-mono text-sm break-all`;
     - "Worktree" (wide): `worktree.path` in `font-mono text-sm break-all`, or, when `worktree`
       is `null` or `missing`, `StatusBadge tone="warning"` "Missing" and muted text "The
       worktree isn't on disk. Add a worktree for this branch on the project page." with a
       link to `/projects/:id` (AC-21);
     - "Created" and "Last updated": `formatDateTime`.
   - A "Steps" `Card` with `FeatureSteps`.
6. **`FeatureSteps({ steps })`:**
   - Sorted by `position` ascending (the API already sends them in that order; sort anyway).
   - Empty → `Empty` (no border) with `ListTodoIcon`, title "No steps yet", description "Steps
     appear here as the agent works through the workflow."
   - Otherwise a `ul` with `divide-y`; each `li` is `flex flex-col gap-2 py-3 sm:flex-row
     sm:items-start sm:justify-between`:
     - left (`min-w-0 flex-1`): the title in `text-sm font-medium break-words`; under it the
       type in muted text (`typeTitle`), then `documentPath` in `font-mono text-xs
       text-muted-foreground break-all` when set;
     - right (`flex shrink-0 flex-col items-start gap-1 sm:items-end`): `StepStatusBadge` and
       "Updated <formatDateTime(updatedAt)>" in muted `text-xs`.
   - Two steps of the same type are two rows (AC-14). No buttons or menus (AC-15).
7. **Badges** (`feature-status-badge.tsx`), on `StatusBadge` with `stateLabel` as the text:
   - `FeatureStatusBadge({ status })`: `done` → positive, `abandoned` → negative, anything
     else → neutral.
   - `StepStatusBadge({ step })`: `approved` or `done` → positive; the type's first state
     (`step.states[0]`) → neutral; anything else → warning.
8. **Layout** (AC-29): `min-w-0` on flex children, `break-all` on branch, folder and paths,
   `break-words` on names and titles; nothing sets a fixed width.
9. Prettier on the touched files.

## Tests

The app has no unit test setup. By hand, against the running stack with tasks 02–08 merged into
the stack, after creating a feature through the API
(`curl -X POST -H 'authorization: Bearer 123456' -H 'content-type: application/json'
-d '{"name":"Feature records"}' localhost:8080/api/projects/<id>/features`):

- `/projects/<id>/features/<fid>` shows the name, number `00N`, the first feature state,
  branch `feat/00N-feature-records/00-docs`, the folder, the worktree path and both times (AC-5,
  page part).
- Set the status and add, update and repeat steps with `curl` (`PUT …/status`, `POST …/steps`,
  `PATCH …/steps/{id}`), reload: the new status and updated time show; steps are listed in the
  order added with type, title, path, status and updated time; two steps of one type both show
  (AC-11–AC-14).
- The page has no control that changes a status, and no rename, archive or delete (AC-15).
- Remove the feature's worktree on the project page: the page shows "Missing" with the hint
  (AC-21).
- `/projects/<id>/features/99999`, a feature id of another project, and `/projects/<id>/features/x`
  show "Feature not found" with "Back to project"; an unknown project id shows "Back to projects"
  (AC-25).
- With the API stopped (`docker stop factory-api`), the page shows "Couldn't load the feature"
  with Try again; after `docker start factory-api`, Try again loads it (AC-28).
- At 375 px, no horizontal scroll; the branch and worktree path wrap (AC-29).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/lib/features.ts app/routes/feature-detail.tsx app/components/features/feature-not-found.tsx app/components/features/feature-steps.tsx app/components/features/feature-status-badge.tsx app/routes.ts`

## Out of scope

- The chat (task 10) and the streaming send in `lib/features.ts`.
- The Features card, the "New feature" form and any link to this page (task 11).
- Status or step editing in the app (non-goal).
