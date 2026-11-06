---
feature: 003-repository-checkouts
task: 08-app-archive-warning
layer: app
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/08-app-archive-warning
base: feat/003-repository-checkouts/07-api-archive-lookup
covers: [AC-20]
---

# 08 — `lib/checkouts.ts` and the archive warning

## Goal

Add `lib/checkouts.ts`, which holds the whole checkout contract (types, calls, error reading) for
tasks 08–10. The archive dialog on `/projects/:id` loads the project's unsaved work when it opens
and lists it per worktree above the confirm button. When there's none, the dialog shows nothing
extra.

## Contract

Consumes, from task-breakdown.md (the module types all of it; this task calls only
`…/checkout/unsaved` and the existing archive route):

- Every route needs `Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as
  `ProblemDetail` (`application/problem+json`) with a `code` property the app switches on, and a
  `detail` it can show as is.
- **Checkout (JSON)**

  ```json
  {
    "id": 3,
    "projectId": 12,
    "state": "ready",
    "error": null,
    "path": "/repos/12",
    "defaultBranch": "main",
    "createdAt": "2026-11-05T10:00:00Z",
    "lastFetchedAt": "2026-11-05T10:15:00Z",
    "worktrees": [
      {
        "id": 7,
        "branch": "main",
        "path": "/repos/12",
        "main": true,
        "baseBranch": null,
        "missing": false,
        "head": { "sha": "819b9a2", "subject": "001 12-app: Verify repository button" },
        "createdAt": "2026-11-05T10:00:00Z"
      }
    ]
  }
  ```

  - `state`: `cloning`, `ready` or `failed`. `worktrees` is empty unless `ready`, main first,
    then by `createdAt`.
  - `missing`: the folder (or its `.git`) isn't on disk; `head` is then `null`.
  - `head` is read from disk on each request (local, no network).
- **Unsaved work (JSON)**

  ```json
  {
    "worktreeId": 9,
    "branch": "feat/003-x/01-api-y",
    "changedFiles": ["api/src/main/kotlin/…/Foo.kt"],
    "unpushedCommits": [{ "sha": "a1b2c3d", "subject": "WIP" }]
  }
  ```

  - `changedFiles`: `git status --porcelain` paths (tracked changes and untracked files), at
    most 50.
  - `unpushedCommits`: `git log <branch> --not --remotes=origin`, at most 50.
- **Routes**

  | Method & path | Request | Response | Errors (`code`) |
  | ------------- | ------- | -------- | ---------------- |
  | `GET /api/projects/{id}/checkout` | — | `200` Checkout | `404 project_not_found`, `404 checkout_not_found` (the app shows "Check out repository") |
  | `POST /api/projects/{id}/checkout` | — | `202` Checkout (`cloning`); the clone runs in the background | `409 project_archived`, `409 checkout_exists` (cloning or ready; a `failed` one is replaced, which is Retry), `503 git_not_configured` |
  | `POST /api/projects/{id}/checkout/fetch` | — | `200` Checkout | `409 checkout_not_ready`, `409 project_archived`, `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout` |
  | `GET /api/projects/{id}/checkout/unsaved` | — | `200` `[Unsaved work]`, only worktrees with something; `[]` when there's no checkout | `404 project_not_found` |
  | `POST /api/projects/{id}/checkout/worktrees` | `{ "branch": "feat/x", "base": "main" }` (`base` optional) | `201` Worktree | `400 invalid_branch` (fails `check-ref-format --branch`), `400 base_not_found`, `409 branch_checked_out` (in a worktree already, the main one included), `409 checkout_not_ready`, `409 project_archived`, `502`/`503`/`504` as fetch |
  | `DELETE /api/projects/{id}/checkout/worktrees/{worktreeId}` | `?force=true` optional | `204` | `404 worktree_not_found`, `409 main_worktree`, `409 worktree_unsaved` (problem has `unsaved`: Unsaved work), `409 project_archived` |
  | `POST /api/projects/{id}/checkout/worktrees/{worktreeId}/push` | `{ "force": false }` | `200` `{ "branch": "…", "head": "a1b2c3d" }` | `404 worktree_not_found`, `409 push_default_branch`, `409 push_rejected` (non-fast-forward, or the lease failed), `409 worktree_missing`, `409 project_archived`, `502`/`503`/`504` as fetch |

  On every route above, in addition to the listed errors:

  - `404 project_not_found` when no project has the id; on the `…/checkout/fetch` and
    `…/worktrees…` routes, `404 checkout_not_found` when the project has no checkout.
  - `400 invalid_request` for a non-numeric id, an unreadable JSON body, or a missing required
    parameter or field.
  - `500 git_error` when a local git command (no network) fails unexpectedly; `detail` is a plain
    message with no git output.
- **Archive (api ↔ app, extends feature 001)**
  - `POST /api/projects/{id}/archive` is unchanged for callers, but now also deletes the
    checkout: both folders and both tables' rows, whatever their state. It doesn't ask for
    force; the app warns first using `GET …/checkout/unsaved`.
  - Restore doesn't touch checkouts.
- **app → api:** through `lib/api.ts`. A new `lib/checkouts.ts` has the types and calls. Errors
  show the problem's `detail`; `5xx` codes get "Try again".

## Files

- `app/app/lib/checkouts.ts` — new: types, one function per route, `checkoutError()`.
- `app/app/components/checkouts/unsaved-work-list.tsx` — new: `UnsavedWorkList`, the per-worktree
  list of changed files and unpushed commits. Task 10 reuses it in the remove confirmation.
- `app/app/routes/project-detail.tsx` — change: `ArchiveDialog` takes `projectId`, loads unsaved
  work when it opens and shows it.

## Steps

1. **Types in `lib/checkouts.ts`**, matching the JSON exactly:

   ```ts
   export type CheckoutState = "cloning" | "ready" | "failed"
   export type CommitRef = { sha: string; subject: string }
   export type Worktree = {
     id: number
     branch: string
     path: string
     main: boolean
     baseBranch: string | null
     missing: boolean
     head: CommitRef | null
     createdAt: string
   }
   export type Checkout = {
     id: number
     projectId: number
     state: CheckoutState
     error: string | null
     path: string
     defaultBranch: string | null
     createdAt: string
     lastFetchedAt: string | null
     worktrees: Worktree[]
   }
   export type UnsavedWork = {
     worktreeId: number
     branch: string
     changedFiles: string[]
     unpushedCommits: CommitRef[]
   }
   export type AddWorktreeInput = { branch: string; base?: string }
   export type PushResult = { branch: string; head: string }
   ```

   `defaultBranch` and `lastFetchedAt` are nullable because the database allows it before the
   first clone or fetch.
2. **Calls** (all through `api` from `~/lib/api`; `base` is `/api/projects/${projectId}/checkout`):
   - `getCheckout(projectId)` → `api.get<Checkout>(base)`;
   - `startCheckout(projectId)` → `api.post<Checkout>(base)` (also Retry);
   - `fetchCheckout(projectId)` → `api.post<Checkout>(\`${base}/fetch\`)`;
   - `getUnsavedWork(projectId)` → `api.get<UnsavedWork[]>(\`${base}/unsaved\`)`;
   - `addWorktree(projectId, input)` → `api.post<Worktree>(\`${base}/worktrees\`, input)`; send
     `base` only when it's a non-empty string;
   - `removeWorktree(projectId, worktreeId, force = false)` →
     `api.delete<void>(\`${base}/worktrees/${worktreeId}${force ? "?force=true" : ""}\`)`;
   - `pushWorktree(projectId, worktreeId, force = false)` →
     `api.post<PushResult>(\`${base}/worktrees/${worktreeId}/push\`, { force })`.
3. **Errors.** Export `CheckoutErrorCode`, a union of every `code` in the routes table
   (`project_not_found`, `checkout_not_found`, `project_archived`, `checkout_exists`,
   `git_not_configured`, `checkout_not_ready`, `git_remote_error`, `git_timeout`,
   `invalid_branch`, `base_not_found`, `branch_checked_out`, `worktree_not_found`,
   `main_worktree`, `worktree_unsaved`, `push_default_branch`, `push_rejected`,
   `worktree_missing`, plus `invalid_request` and `git_error` from the
   "On every route" block). Export `checkoutError(error: unknown): CheckoutError`:

   ```ts
   export type CheckoutError = {
     status: number | null // null when the request never got a response
     code: CheckoutErrorCode | string | null
     message: string
     retryable: boolean
     unsaved: UnsavedWork | null // only for worktree_unsaved
   }
   ```

   - `code`: the problem's `code` when it's a string (read via `problemDetail(error)` cast to
     `{ code?: unknown; unsaved?: unknown }`), else `null`.
   - `message`: for `401`, or when the error isn't an `ApiError`, `apiErrorMessage(error)`.
     Otherwise the problem's `detail` when it's a non-empty string, else `apiErrorMessage(error)`.
     `lib/api.ts` is locked and maps every `502` to generic text, so `detail` must come first or
     `git_remote_error`'s reason is lost.
   - `retryable`: `true` when `status >= 500` (so `500 git_error` gets "Try again") or the error isn't an `ApiError` (network failure).
   - `unsaved`: the problem's `unsaved` when `code === "worktree_unsaved"` and it's an object
     with a `changedFiles` array, else `null`.
4. **`UnsavedWorkList({ items }: { items: UnsavedWork[] })`:**
   - A `ul` with `flex flex-col gap-4`. Each item (keyed by `worktreeId`):
     - the branch in `font-mono text-sm font-medium break-all`;
     - when `changedFiles` isn't empty: "Changed files (n)" in `text-xs text-muted-foreground`,
       then a `ul` of paths in `font-mono text-xs break-all`;
     - when `unpushedCommits` isn't empty: "Unpushed commits (n)", then a `ul` of rows with the
       `sha` in `font-mono` and the subject with `break-words`;
     - when either list has 50 entries: "Showing the first 50." in muted text.
   - Wrap the whole list in a `div` with `max-h-64 overflow-y-auto` so a long list scrolls inside
     the dialog instead of pushing the buttons off screen.
   - Paths and branches wrap at 375 px; nothing scrolls sideways.
5. **`ArchiveDialog({ name, projectId })`** in `project-detail.tsx`; the page passes
   `projectId={project.id}`.
   - Local state `unsaved`: `{ status: "idle" } | { status: "loading" } | { status: "loaded";
     items: UnsavedWork[] } | { status: "error"; message: string }`.
   - A `useEffect` on `[open, projectId]`: when `open` turns true, set `loading` and call
     `getUnsavedWork(projectId)`. Use a `cancelled` flag set in the cleanup so a late answer after
     closing is dropped. When `open` turns false, reset to `idle`.
   - Description: keep the existing text and add "Its repository checkout is deleted too, and
     restoring the project doesn't bring it back."
   - Between the header and the footer:
     - `loading`: a row with `Spinner` and "Checking for unsaved work…";
     - `loaded` with items: an `Alert variant="destructive"` with `TriangleAlertIcon`, title
       "Some worktrees have work that isn't on GitHub", description "Archiving deletes it.", and
       below it `<UnsavedWorkList items={items} />`;
     - `loaded` with `[]`: nothing extra;
     - `error`: an `Alert` (default variant) titled "Couldn't check for unsaved work" with the
       message (`checkoutError(error).message`) and "Archiving deletes the checkout, including
       anything not pushed to GitHub."
   - The submit button is disabled while `loading`, so nothing can be archived before the list
     shows (AC-20). Its label is "Archive anyway" when there are items, else "Archive".
   - Cancel closes the dialog and changes nothing. The archive itself still goes through the
     existing `clientAction` intent `archive`; nothing changes there.
6. Format the touched files with Prettier.

## Tests

The app has no unit test setup (`package.json` has no test script). By hand, against the running
stack with tasks 01–07 in place. With no app UI for checkouts yet, create them through the API
(`curl` with the bearer token: `POST …/checkout`, then `POST …/checkout/worktrees`), and make a
worktree dirty with `docker exec factory-api sh -c 'echo x > /repos/<id>.worktrees/<dir>/x.txt'`
and an unpushed commit with `git -C … commit`.

- A project with no checkout: the dialog opens, shows the spinner briefly, then the usual text
  with no warning; Archive works as before.
- A project whose worktrees are all clean and pushed: no warning; archiving removes
  `/repos/<id>/` and `/repos/<id>.worktrees/`.
- A worktree with an untracked file and an unpushed commit: the dialog lists that worktree's
  branch, the file and the commit, and the button reads "Archive anyway" (AC-20).
- Cancel: the project isn't archived, and the worktree and its file are still on disk (AC-20).
- Confirm: the project is archived and both folders are gone (AC-20).
- With the API stopped after the page loads, opening the dialog shows "Couldn't check for unsaved
  work" and Archive stays available.
- At 375 px, a long branch name and file path wrap inside the dialog; the list scrolls inside it.

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/lib/checkouts.ts app/components/checkouts/unsaved-work-list.tsx app/routes/project-detail.tsx`

## Out of scope

- The "Repository checkout" card (task 09) and worktree actions (task 10).
- Any change to archive or restore behaviour in the API (task 07).
