---
feature: 003-repository-checkouts
task: 09-app-checkout-card
layer: app
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/09-app-checkout-card
base: feat/003-repository-checkouts/08-app-archive-warning
covers: [AC-1, AC-2, AC-5, AC-17, AC-21, AC-28, AC-30]
---

# 09 — "Repository checkout" card

## Goal

The project detail page gets a "Repository checkout" card that loads on its own after the page.
It offers "Check out repository" when there's no checkout, polls while cloning, and shows a
failure with Retry. When ready, it shows the last-fetched time, a Fetch button and the worktree
list. Archived projects get the card with no actions. Worktree actions come in task 10.

## Contract

Consumes, from task-breakdown.md:

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
- **Routes**

  | Method & path | Request | Response | Errors (`code`) |
  | ------------- | ------- | -------- | ---------------- |
  | `GET /api/projects/{id}/checkout` | — | `200` Checkout | `404 project_not_found`, `404 checkout_not_found` (the app shows "Check out repository") |
  | `POST /api/projects/{id}/checkout` | — | `202` Checkout (`cloning`); the clone runs in the background | `409 project_archived`, `409 checkout_exists` (cloning or ready; a `failed` one is replaced, which is Retry), `503 git_not_configured` |
  | `POST /api/projects/{id}/checkout/fetch` | — | `200` Checkout | `409 checkout_not_ready`, `409 project_archived`, `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout` |

  On every route above, in addition to the listed errors:

  - `404 project_not_found` when no project has the id; on the `…/checkout/fetch` and
    `…/worktrees…` routes, `404 checkout_not_found` when the project has no checkout.
  - `400 invalid_request` for a non-numeric id, an unreadable JSON body, or a missing required
    parameter or field.
  - `500 git_error` when a local git command (no network) fails unexpectedly; `detail` is a plain
    message with no git output.
- **Archive:** restore doesn't touch checkouts; archive deletes the checkout.
- **app → api:** through `lib/api.ts`. A new `lib/checkouts.ts` has the types and calls. Errors
  show the problem's `detail`; `5xx` codes get "Try again".

## Files

- `app/app/components/checkouts/use-checkout.ts` — new: `useCheckout(projectId, archived)`, the
  card's loading, polling and reload.
- `app/app/components/checkouts/checkout-card.tsx` — new: `CheckoutCard`, its states, Check out,
  Retry and Fetch.
- `app/app/components/checkouts/worktree-list.tsx` — new: `WorktreeList`, read-only rows with an
  `actions` slot task 10 fills.
- `app/app/routes/project-detail.tsx` — change: render
  `<CheckoutCard projectId={project.id} archived={project.archived} />` below `ProjectDetails`.

No route is added: the card calls `lib/checkouts.ts` directly from the component, not through
`clientLoader`/`clientAction`. The project page's loader doesn't wait for git, its actions don't
reload the card, and the card's own errors stay inside it (AC-28).

## Steps

1. **`useCheckout(projectId: number, archived: boolean)`** returns
   `{ state, reload, setCheckout }`, where `state` is:

   ```ts
   type CheckoutView =
     | { status: "loading" }
     | { status: "archived" }
     | { status: "none" } // 404 checkout_not_found
     | { status: "loaded"; checkout: Checkout }
     | { status: "error"; error: CheckoutError }
   ```

   - **Load.** `reload()` calls `getCheckout(projectId)`. On success → `loaded`. On
     `checkoutError(e).code === "checkout_not_found"` → `none`. Anything else (`project_not_found`, `invalid_request`,
     `5xx`, network) → `error`. A
     reload while `loaded` keeps showing the current checkout until the answer arrives (no
     skeleton flash); only the first load shows `loading`.
   - **Stale answers.** Keep a `requestId` ref, incremented per call, and a `mounted` ref set
     false in the unmount cleanup. Ignore an answer whose id isn't the latest or that arrives
     after unmount.
   - **Start.** A `useEffect` on `[projectId, archived]`: when `archived`, set `archived` and make
     no request (archive deleted the checkout; there's nothing to show or do). Otherwise set
     `loading` and `reload()`. Restoring the project changes `archived` (the page revalidates
     after the `restore` intent), so the card loads again and shows "Check out repository"
     (AC-21).
   - **Polling.** A `useEffect` on `[state]`: when `state.status === "loaded"` and
     `state.checkout.state === "cloning"`, `setTimeout(reload, 3000)`; the cleanup clears the
     timer. Each answer sets a new `state` object, so the effect re-arms while it's still
     `cloning` and stops as soon as it's `ready` or `failed`, on an error, or on unmount (AC-2,
     AC-5).
   - `setCheckout(checkout)` sets `loaded` with a checkout a POST returned (checkout, Retry,
     fetch).
2. **`CheckoutCard({ projectId, archived })`:**
   - `Card` with `CardHeader`: `CardTitle` "Repository checkout"; `CardAction` holds the state
     badge (below) and, when `ready` and not archived, the Fetch button.
   - State badge via `StatusBadge` from `~/components/status-badge`: `cloning` → neutral
     "Cloning", `ready` → positive "Ready", `failed` → negative "Failed".
   - Local state: `pending: "checkout" | "fetch" | null` and `failure: { error: CheckoutError;
     retry: () => void } | null`, shown as one `Alert variant="destructive"` at the top of
     `CardContent` titled "That didn't work", with `failure.error.message` and, when
     `retryable`, a "Try again" button that calls `retry`. Every action clears `failure` first.
   - `run(kind, call, retry)`: set `pending`, `await call()`, `setCheckout(result)`; on error set
     `failure` (except the codes handled below); always clear `pending`.
3. **Content by state:**
   - `loading`: two `Skeleton` lines (`h-4 w-48`, `h-4 w-72 max-w-full`).
   - `archived`: an `Empty` (no border) with `GitBranchIcon`, title "No checkout", description
     "Archived projects have no checkout. Restore the project to check out its repository."
     No buttons (AC-21).
   - `none`: an `Empty` with `GitBranchIcon`, title "Not checked out", description "Clone the
     repository onto the server to work on it here.", and a "Check out repository" button
     (`DownloadIcon`, `Spinner` while pending) (AC-1). It calls `startCheckout`; the `202`
     answer is `cloning`, so the card switches to Cloning and starts polling (AC-2).
     `409 checkout_exists` → `reload()` instead of an error (someone else started it).
   - `loaded`, `cloning`: a row with `Spinner` and "Cloning the repository. This can take a few
     minutes.", then the path (below).
   - `loaded`, `failed`: an `Alert variant="destructive"` titled "The clone failed" with
     `checkout.error` (plain words from the API) and a "Retry" button (`RotateCcwIcon`) that
     calls `startCheckout` like "Check out repository" (AC-5).
   - `loaded`, `ready`: a `dl` grid (one column, two from `sm`) with:
     - "Path": `checkout.path` + `/` in `font-mono text-sm break-all`;
     - "Default branch": `defaultBranch` in `font-mono break-all`;
     - "Last fetched": `formatDateTime(lastFetchedAt)`, or "Never".
     Then a "Worktrees" heading and `<WorktreeList worktrees={checkout.worktrees} />` (AC-2).
   - `error`: an `Alert variant="destructive"` titled "Couldn't load the repository checkout"
     with the message and, when `retryable`, "Try again" calling `reload()`. The rest of the
     page keeps working (AC-28).
4. **Fetch** (`RefreshCwIcon`, `Spinner` while pending, outline, `size="sm"`): calls
   `fetchCheckout`; the answer replaces the checkout, so "Last fetched" and the main worktree's
   head update (AC-17); `toast.success("Fetched from GitHub")`. On `409 checkout_not_ready` or
   `404 checkout_not_found` → `reload()` (the card then shows the current state, or "Check out
   repository"). On `500 git_error`, `502 git_remote_error`, `503 git_not_configured` or
   `504 git_timeout` → `failure` with the `detail` and "Try again" that fetches again (AC-28). `409 project_archived`
   → `failure` with the `detail` (the page is stale).
5. **`WorktreeList({ worktrees, actions })`**, where `actions?: (worktree: Worktree) =>
   ReactNode`:
   - A `ul` with `divide-y`; each `li` is `flex flex-col gap-3 py-3 sm:flex-row
     sm:items-start sm:justify-between`, with the details in a `min-w-0 flex-1` column and
     `actions?.(worktree)` in a `flex shrink-0 flex-wrap gap-2` box.
   - Details:
     - the branch in `font-mono text-sm font-medium break-all`, then a `Badge
       variant="secondary"` "Main" when `main`, and `StatusBadge tone="warning"` "Missing" when
       `missing`; all in a `flex flex-wrap items-center gap-2` row;
     - the path in `font-mono text-xs text-muted-foreground break-all`;
     - the head: `sha` in `font-mono` and the subject with `break-words`; when `head` is `null`,
       "The folder isn't on disk." in muted text;
     - "Created <formatDateTime(createdAt)>" in muted text.
6. **Layout:** at 375 px nothing is wider than the viewport; paths and branch names wrap
   (`min-w-0` on flex children, `break-all` on mono text) (AC-30).
7. Format the touched files with Prettier.

## Tests

The app has no unit test setup. By hand, against the running stack with `API_GIT_TOKEN` set:

- A non-archived project with no checkout shows the card with "Check out repository" (AC-1).
- Clicking it shows "Cloning" at once; the network tab shows a `GET …/checkout` every 3 s; it
  turns "Ready" with the main worktree on the default branch at `/repos/<id>/`, and the polling
  stops. Leaving the page mid-clone stops the requests (AC-2).
- A project whose repository the token can't read (or with an invalid token) shows "Failed" with
  the reason and Retry; after fixing the token, Retry clones again (AC-5).
- Push a commit to the default branch on GitHub, click Fetch: "Last fetched" updates and the main
  worktree's head shows the new commit (AC-17).
- Archive the project: the card shows "No checkout" with no buttons. Restore it: the card shows
  "Check out repository" again (AC-21).
- With `API_GIT_TOKEN` unset, "Check out repository" and Fetch show the not-configured message
  with Try again. With the network cut from `factory-api`, Fetch shows the GitHub error with Try
  again. In both cases Edit and Archive still work (AC-28).
- A worktree folder deleted by hand shows "Missing" and no head (AC-13, display only).
- At 375 px, the card doesn't cause horizontal scroll, and long paths and branch names wrap
  (AC-30).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/components/checkouts/use-checkout.ts app/components/checkouts/checkout-card.tsx app/components/checkouts/worktree-list.tsx app/routes/project-detail.tsx`

## Out of scope

- Add worktree, Push and Remove (task 10).
- Showing changed-file or ahead/behind counts (non-goal).
- Deleting a checkout without archiving (non-goal).
