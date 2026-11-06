---
feature: 003-repository-checkouts
task: 06-api-worktree-push
layer: api
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/06-api-worktree-push
base: feat/003-repository-checkouts/05-api-worktrees
covers: [AC-14, AC-15, AC-16, AC-28]
---

# 06 — Worktrees: push

## Goal

Add `POST /api/projects/{id}/checkout/worktrees/{worktreeId}/push`. It pushes the worktree's
branch with its upstream set. It refuses the default branch, uses `--force-with-lease` only when
the caller asks for force, and fetches after the push.

## Contract

Provides, copied from task-breakdown.md:

### Routes (this task's row)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST /api/projects/{id}/checkout/worktrees/{worktreeId}/push` | `{ "force": false }` | `200` `{ "branch": "…", "head": "a1b2c3d" }` | `404 worktree_not_found`, `409 push_default_branch`, `409 push_rejected` (non-fast-forward, or the lease failed), `409 worktree_missing`, `409 project_archived`, `502`/`503`/`504` as fetch |

On every route above, in addition to the listed errors:

- `404 project_not_found` when no project has the id; on the `…/checkout/fetch` and
  `…/worktrees…` routes, `404 checkout_not_found` when the project has no checkout.
- `400 invalid_request` for a non-numeric id, an unreadable JSON body, or a missing required
  parameter or field.
- `500 git_error` when a local git command (no network) fails unexpectedly; `detail` is a plain
  message with no git output.

- Push: `git push -u origin <branch>:<branch>`, or `--force-with-lease=<branch>` when
  `force`. No fetch before; a fetch after. Pushing the default branch is refused before git runs.
- Git failures map as: auth rejected or other remote error → `502 git_remote_error` with a plain
  message (no stderr containing URLs or headers); timeout → `504 git_timeout`. Any text taken
  from git output is scrubbed of the token.
- One git operation at a time per checkout (an in-process lock keyed by checkout id).

`409 project_archived` is added in task 07.

## Files

- `api/src/main/kotlin/com/toptier/custom/checkout/service/WorktreeService.kt` — change: add
  `push` and `data class PushResult(branch, head)`.
- `api/src/main/kotlin/com/toptier/custom/checkout/dto/WorktreeDtos.kt` — change: add
  `PushRequest(force: Boolean? = false)` and `PushResponse(branch, head)`.
- `api/src/main/kotlin/com/toptier/custom/checkout/exception/CheckoutExceptions.kt` — change:
  add `PushDefaultBranchException(branch)`, `PushRejectedException(lease: Boolean)` and
  `WorktreeMissingException(branch)`.
- `api/src/main/kotlin/com/toptier/custom/checkout/controller/WorktreeController.kt` — change:
  add `POST /{worktreeId}/push`.
- `api/src/main/kotlin/com/toptier/custom/checkout/controller/CheckoutExceptionHandler.kt` —
  change: map the three new exceptions.
- `api/src/test/kotlin/com/toptier/custom/checkout/WorktreePushTests.kt` — new.
- `api/src/test/kotlin/com/toptier/custom/checkout/WorktreeControllerTests.kt` — change.

## Steps

1. **`push(projectId, worktreeId, force: Boolean): PushResult`.**
   1. Call `locate(projectId, worktreeId)` (task 05).
   2. If `worktree.branch == checkout.defaultBranch`, throw `PushDefaultBranchException` before
      any git command runs (AC-15). This also covers the main worktree.
   3. If `inspector.missing(path)` is true, throw `WorktreeMissingException`.
   4. Call `runner.requireToken()`.
   5. Inside `locks.withLock(checkout.id)`:
      - Build the args `listOf("push", "-u", "origin", "<b>:<b>")`. When `force` is true,
        insert `"--force-with-lease=<b>"` right after `push`. Don't fetch first: the lease
        compares against `refs/remotes/origin/<b>` as of the last fetch (AC-16).
      - Run `runner.runOrThrow(path, args, network = true)`. Map a `GitCommandException` by its
        `failure`:
        - `REJECTED` → `PushRejectedException(lease = false)`;
        - `STALE_LEASE` → `PushRejectedException(lease = true)`;
        - anything else → `GitRemoteException(failure)`.
      - After a successful push, call `checkoutService.fetchUnderLock(checkout)` (task 04)
        inside a `try`. If it fails, log at warn and still return success.
      - Return `head = runOrThrow(path, listOf("rev-parse", "--short", "HEAD")).trim()` (AC-14).
2. **Details.**
   - `PushDefaultBranchException`: "<branch> is the repository's default branch and can't be
     pushed from here."
   - `PushRejectedException(lease = false)`: "GitHub rejected the push: the branch on GitHub has
     commits this worktree doesn't. Push with force to replace them."
   - `PushRejectedException(lease = true)`: "The branch on GitHub changed since the last fetch,
     so the forced push was refused. Fetch and check it first."
   - `WorktreeMissingException`: "The worktree's folder is missing. Remove it and add it again."
3. **Handler.**
   - `PushDefaultBranchException` → `409 push_default_branch`.
   - `PushRejectedException` → `409 push_rejected`.
   - `WorktreeMissingException` → `409 worktree_missing`.
4. **Controller.** `@PostMapping("/{worktreeId}/push")` with `@RequestBody(required = false)
   body: PushRequest?`. A missing body or a missing `force` means `false`. It returns
   `PushResponse`.

## Tests

`WorktreePushTests` is a `@DataJpaTest` set up like `WorktreeServiceTests`. It uses
`CheckoutTestConfig` and `file://` bare remotes saved as the project's `repositoryUrl`, so it
never calls GitHub. "GitHub" is the bare remote, and a scratch clone plays a second contributor.

- **New branch (AC-14).** Push a new branch with one commit. Afterwards:
  - the remote has the branch at the worktree's head;
  - `rev-parse --abbrev-ref @{u}` is `origin/<branch>`;
  - the result's `head` matches;
  - `lastFetchedAt` moved, because of the fetch after the push.
- **Default branch (AC-15).** Pushing the main worktree throws `PushDefaultBranchException`. So
  does pushing a worktree whose branch equals `defaultBranch`, built with a row inserted by hand
  on `main` in a second checkout. Neither runs git.
- **Rebased branch (AC-16).** Amend a commit that is already pushed:
  - without force, the push throws `PushRejectedException(lease = false)` and the remote is
    unchanged;
  - with force, the push succeeds.
- **Lease (AC-16).** Push the branch. The scratch clone then pushes a commit to it, and the API
  doesn't fetch. The worktree amends and pushes with force. Expect
  `PushRejectedException(lease = true)`, and the remote keeps the scratch clone's commit.
- **Missing folder.** If the worktree's folder is gone, push throws `WorktreeMissingException`.
- **Remote gone (AC-28).** If the remote is deleted, push throws `GitRemoteException`.

Additions to `WorktreeControllerTests`:

- Push without a token answers `401` (AC-26).
- Push answers `200 { branch, head }`. `{ "force": true }` reaches the service as `true`; no body
  and `{}` reach it as `false` (AC-14, AC-16).
- These exceptions map as follows:
  - `PushDefaultBranchException` → `409 push_default_branch` (AC-15);
  - `PushRejectedException`, both variants → `409 push_rejected` with their own details (AC-16);
  - `WorktreeMissingException` → `409 worktree_missing`;
  - `GitRemoteException` → `502 git_remote_error`, `GitNotConfiguredException` → `503`,
    `GitTimeoutException` → `504` (AC-28).

## Checks

- Run `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`, or the throwaway
  `gradle:jdk25` container from `api/CLAUDE.md`.
- The app starts against MySQL with `./gradlew bootRun` in `factory-api`.
- With a ready checkout of a scratch GitHub repository:
  1. Add a `feat/…` worktree.
  2. Commit in it from `factory-agent`.
  3. Push it through the API.
  4. See the branch on GitHub.

## Out of scope

- `409 project_archived` (task 07).
- The app's Push and Force with lease actions (task 10).
- The agent's push tool (task 11).
