---
feature: 003-repository-checkouts
task: 10-app-worktree-actions
layer: app
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/10-app-worktree-actions
base: feat/003-repository-checkouts/09-app-checkout-card
covers: [AC-6, AC-7, AC-8, AC-10, AC-11, AC-12, AC-13, AC-14, AC-15, AC-16, AC-30]
---

# 10 — Worktree actions on the checkout card

## Goal

The ready checkout card gets an "Add worktree" form, and each worktree gets Push (with a "Force
with lease" retry after a rejection) and Remove (with a second confirmation listing unsaved work).
The main worktree has no Remove. This completes the feature in the app.

## Contract

Consumes, from task-breakdown.md (types and calls already in `lib/checkouts.ts` from task 08):

- Every route needs `Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as
  `ProblemDetail` (`application/problem+json`) with a `code` property the app switches on, and a
  `detail` it can show as is.
- **Worktree** is an entry of the Checkout's `worktrees`:

  ```json
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
  ```

  - `missing`: the folder (or its `.git`) isn't on disk; `head` is then `null`.
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

  Fetch's `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout`.
- Folder names replace `/` with `__`, so `a/b` and `a__b` share a folder: adding the second
  answers `409 branch_checked_out`.
- Push: `git push -u origin <branch>:<branch>`, or `--force-with-lease=<branch>` when `force`.
  No fetch before; a fetch after. Pushing the default branch is refused before git runs.
- Remove: `git worktree remove --force <dir>` (after the unsaved check, unless `force`), then
  `git branch -D` only if the branch is on origin. For a `missing` worktree:
  `git worktree prune` and delete the record.
- **app → api:** through `lib/api.ts`. A new `lib/checkouts.ts` has the types and calls. Errors
  show the problem's `detail`; `5xx` codes get "Try again".

## Files

- `app/app/components/checkouts/add-worktree-form.tsx` — new: `AddWorktreeForm`.
- `app/app/components/checkouts/worktree-actions.tsx` — new: `WorktreeActions` (Push button and
  its inline result or error) and `RemoveWorktreeDialog`.
- `app/app/components/checkouts/checkout-card.tsx` — change: when `ready` (archived projects
  never reach `ready`, see task 09), pass `actions` to `WorktreeList` and render
  `AddWorktreeForm` below it; both call `reload()` from `useCheckout` after a change.
- `app/app/components/checkouts/worktree-list.tsx` — change only if the row needs room for the
  push result line (an optional `footer?: (worktree) => ReactNode` slot under the details).

## Steps

1. **`AddWorktreeForm({ projectId, defaultBranch, onAdded })`:**
   - A plain `<form onSubmit>` (not router `Form`; the card owns its data). Controlled fields:
     - "Branch" (`Input`, `name="branch"`, `font-mono`, `required`, `autoComplete="off"`,
       placeholder `feat/my-change`);
     - "Base branch" (optional; placeholder `defaultBranch ?? "main"`; `FieldDescription` "Used
       only when the branch isn't on GitHub yet. Defaults to <defaultBranch>.").
   - Layout: `FieldGroup` stacked on phones; from `sm` the two fields side by side
     (`sm:grid sm:grid-cols-2`), the "Add worktree" button below (`PlusIcon`, `Spinner` while
     pending).
   - Submit: trim both. An empty branch → field error "Enter a branch name." with no request.
     Otherwise `addWorktree(projectId, { branch, base: base || undefined })`.
   - Success (`201`): clear the fields, `toast.success(\`Worktree added for ${branch}\`)`,
     `onAdded()` → the card's `reload()`, so the new row appears in order with its path, head and
     created time (AC-6, AC-7).
   - Errors via `checkoutError`, shown with `Field data-invalid` + `FieldError`, like
     `project-form.tsx` (AC-8):
     - `invalid_branch`, `branch_checked_out` → under Branch, showing `detail` as is (it also
       covers the `a/b` vs `a__b` folder collision, so don't write a fixed "already checked out"
       text);
     - `base_not_found` → under Base branch;
     - `404 checkout_not_found` → `onAdded()` (reload; the card shows "Check out repository");
     - anything else (`checkout_not_ready`, `project_archived`, `invalid_request`, `400`
       without a known code, `500 git_error`, `502`/`503`/`504`) → a form-level `Alert variant="destructive"` with the message, plus "Try again"
       (resubmits the same values) when `retryable` (AC-28).
   - Field values are kept on error so the user can correct them.
2. **`WorktreeActions({ projectId, worktree, defaultBranch, onChanged })`**, rendered through
   `WorktreeList`'s `actions`:
   - **Push** (outline, `size="sm"`, `UploadIcon`): shown unless `worktree.main`,
     `worktree.missing`, or `worktree.branch === defaultBranch`. The API refuses those anyway
     (`409 push_default_branch`, `worktree_missing`; AC-15), so the button isn't offered.
   - **Remove** (outline, `size="sm"`, `Trash2Icon`): shown unless `worktree.main` (AC-12). Shown
     for missing worktrees (AC-13).
   - Both disabled while either is pending for this row.
3. **Push flow** (local state `pending: false | "push" | "force"`, `pushed: PushResult | null`,
   `pushError: CheckoutError | null`, `forced: boolean`):
   - `pushWorktree(projectId, worktree.id, force)`.
   - Success: `pushed` = result, `toast.success(\`Pushed ${branch}\`)`, and a muted line under the
     row "Pushed to GitHub at <head>" (AC-14). Clear `pushError`.
   - `push_rejected` without force: an `Alert variant="destructive"` under the row titled "GitHub
     rejected the push", with the `detail` and the text "If you rebased this branch, force push
     with lease. It overwrites the branch on GitHub only if nobody pushed to it since the last
     fetch.", and a "Force with lease" button (`size="sm"`) that pushes with `force: true`
     (AC-16).
   - `push_rejected` with force: the same alert without the button, saying "The branch on GitHub
     moved since the last fetch. Fetch, check what changed, then push again." (AC-16).
   - `5xx` (including `500 git_error`): the alert with the message and "Try again" (repeats the same push, same `force`).
   - Other codes (`worktree_not_found`, `worktree_missing`, `push_default_branch`,
     `project_archived`, `checkout_not_found`, `invalid_request`): the alert with the message,
     then `onChanged()` for `worktree_not_found`, `worktree_missing` and `checkout_not_found` so
     the card refreshes.
   - The alert and the pushed line sit in the row's details column (`footer` slot) so they wrap
     with it at phone width.
4. **`RemoveWorktreeDialog`** (opened by Remove; local state `step: "confirm" | "unsaved"`,
   `unsaved: UnsavedWork | null`, `error: CheckoutError | null`, `pending`):
   - **Step 1, `confirm`:** title "Remove worktree?"; the branch in `font-mono break-all`;
     description "Deletes <path>. The branch stays on GitHub if it was pushed." For a missing
     worktree: "The folder is already gone. This clears its record." Buttons Cancel and
     "Remove" (destructive). Remove calls `removeWorktree(projectId, id)` (no force).
   - `204` → close, `toast.success("Worktree removed")`, `onChanged()` → `reload()`; the row is
     gone (AC-10, AC-13).
   - `409 worktree_unsaved` → **step 2, `unsaved`:** title "This worktree has unsaved work";
     description "These changes and commits aren't on GitHub. Removing the worktree deletes them.";
     `<UnsavedWorkList items={[unsaved]} />` from task 08, using the problem's `unsaved`
     (`checkoutError(e).unsaved`). If `unsaved` is `null`, show the `detail` instead. Buttons
     Cancel and "Remove anyway" (destructive), which calls `removeWorktree(projectId, id, true)`;
     `204` → as above (AC-11).
   - `404 worktree_not_found` or `404 checkout_not_found` → close, `toast("Worktree was already removed")`, `onChanged()`.
   - Any other error (`main_worktree`, `project_archived`, `invalid_request`, `500 git_error`,
     network) → an `Alert
     variant="destructive"` inside the dialog with the message; the confirm button retries the
     same step.
   - Closing the dialog (Cancel, Escape, outside click) resets to step 1 and clears the error;
     nothing is removed. Keep the dialog open while pending.
5. **`checkout-card.tsx`:** in the `ready` branch, pass
   `actions={(w) => <WorktreeActions … onChanged={reload} />}` and render a "Add worktree"
   sub-heading with `<AddWorktreeForm projectId={projectId}
   defaultBranch={checkout.defaultBranch} onAdded={reload} />` under the list, separated by a
   `Separator`. `reload` keeps the current list visible while it loads (task 09).
6. **Layout:** at 375 px, row actions wrap under the details, the form stacks, dialogs fit the
   screen, and branch names, paths and file lists wrap with `break-all` (AC-30).
7. Format the touched files with Prettier.

## Tests

The app has no unit test setup. By hand, against the running stack with a ready checkout and
`API_GIT_TOKEN` set:

- Add a worktree for a branch that exists on GitHub: it's listed with branch, path
  `/repos/<id>.worktrees/<branch with __>/`, head and created time (AC-6).
- Add one for a new branch with a base, and one without: both listed, created from the base or
  the default branch (AC-7).
- `bad..name` → error under Branch; a missing base → error under Base branch; the same branch
  twice, the default branch, or `a__b` after `a/b` → the API's `branch_checked_out` reason under
  Branch; nothing new is listed
  (AC-8).
- Remove a clean, pushed worktree: one confirmation, then it's gone from the card and disk
  (AC-10).
- Write a file and commit in a worktree (`docker exec factory-api …`), then Remove: the second
  step lists the file and the commit; Cancel keeps it; "Remove anyway" removes it (AC-11).
- The Main row has no Remove (and no Push) (AC-12, AC-15).
- Delete a worktree folder by hand: the row shows "Missing" without Push; Remove clears it
  (AC-13).
- Commit in a worktree and Push: the branch is on GitHub at that commit with upstream set, and
  the row says it was pushed (AC-14).
- Rebase a pushed branch in its worktree and Push: "GitHub rejected the push" with "Force with
  lease"; clicking it pushes. Then push another commit to that branch on GitHub without
  fetching, rebase again, Push and Force with lease: rejected with the "moved since the last
  fetch" text (AC-16).
- With the network cut from `factory-api`, Add worktree and Push show the GitHub error with Try
  again; the page still works (AC-28).
- At 375 px, the form, rows, alerts and dialogs don't cause horizontal scroll; long branches and
  paths wrap (AC-30).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/components/checkouts/add-worktree-form.tsx app/components/checkouts/worktree-actions.tsx app/components/checkouts/checkout-card.tsx app/components/checkouts/worktree-list.tsx`

## Out of scope

- Editing files, committing or rebasing from the app (non-goal).
- Opening pull requests from the app (non-goal).
- Changed-file or ahead/behind counts on rows (non-goal).
- The agent's use of these routes (task 11).
