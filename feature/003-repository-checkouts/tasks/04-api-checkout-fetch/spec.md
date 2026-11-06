---
feature: 003-repository-checkouts
task: 04-api-checkout-fetch
layer: api
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/04-api-checkout-fetch
base: feat/003-repository-checkouts/03-api-checkout-clone
covers: [AC-17, AC-18, AC-28]
---

# 04 — Checkout: fetch and the scheduled fetch

## Goal

`POST /api/projects/{id}/checkout/fetch`, which fetches, fast-forwards a clean main worktree and
records `lastFetchedAt`. It also adds the `@Scheduled` fetch of every ready checkout.

## Contract

Provides, copied from task-breakdown.md:

### Routes (this task's row)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST /api/projects/{id}/checkout/fetch` | — | `200` Checkout | `409 checkout_not_ready`, `409 project_archived`, `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout` |

On every route above, in addition to the listed errors:

- `404 project_not_found` when no project has the id; on the `…/checkout/fetch` and
  `…/worktrees…` routes, `404 checkout_not_found` when the project has no checkout.
- `400 invalid_request` for a non-numeric id, an unreadable JSON body, or a missing required
  parameter or field.
- `500 git_error` when a local git command (no network) fails unexpectedly; `detail` is a plain
  message with no git output.

- Fetch: `git fetch --prune origin`, then `git merge --ff-only origin/<default>` in the main
  worktree when `git status --porcelain` is empty. Sets `lastFetchedAt`.
- Git failures map as: auth rejected or other remote error → `502 git_remote_error` with a plain
  message (no stderr containing URLs or headers); timeout → `504 git_timeout`. Any text taken
  from git output is scrubbed of the token.
- One git operation at a time per checkout (an in-process lock keyed by checkout id).

### api config (these rows)

| Property | Env | Default | For |
| -------- | --- | ------- | --- |
| `app.git.network-timeout` | `GIT_NETWORK_TIMEOUT` | `2m` | fetch and push. |
| `app.git.fetch-interval` | `GIT_FETCH_INTERVAL` | `15m` | The scheduled fetch (runs only when `app.scheduling.enabled`). |

Task 07 adds `409 project_archived`. The Checkout JSON and every error code already exist from
task 03. The route needs `Authorization: Bearer <API_TOKEN>` (`401` otherwise).

## Files

- `api/src/main/kotlin/com/toptier/custom/checkout/service/CheckoutService.kt` — change: add
  `fetch`, `fetchUnderLock`, `fetchAll` and a private `remote { }` helper.
- `api/src/main/kotlin/com/toptier/custom/checkout/controller/CheckoutController.kt` — change:
  add `POST /fetch`.
- `api/src/test/kotlin/com/toptier/custom/checkout/CheckoutFetchTests.kt` — new.
- `api/src/test/kotlin/com/toptier/custom/checkout/CheckoutControllerTests.kt` — change.

## Steps

1. **`fetch(projectId): CheckoutView`**
   1. Call `projects.get(projectId)`.
   2. Look up the checkout row; if there is none, throw `CheckoutNotFoundException`.
   3. If the row isn't `READY`, throw `CheckoutNotReadyException`.
   4. Call `runner.requireToken()`.
   5. Run `locks.withLock(id) { fetchUnderLock(row) }`.
   6. Return `view(row)`.
2. **`private fun <T> remote(block: () -> T): T`** turns a `GitCommandException` into
   `GitRemoteException(ex.failure)`. `GitTimeoutException` and `GitNotConfiguredException` pass
   through unchanged. Tasks 05 and 06 reuse this helper.
3. **`fetchUnderLock(checkout)`** is public, and the caller must hold the lock. Tasks 05 and 06
   call it too.
   1. Run `remote { runner.runOrThrow(dir, listOf("fetch", "--prune", "origin"), network = true)
      }`, where `dir` is `Path.of(checkout.path)`.
   2. If `runner.run(dir, listOf("status", "--porcelain")).stdout.isBlank()`, run
      `runner.run(dir, listOf("merge", "--ff-only", "origin/<defaultBranch>"))`.
      - A non-zero exit (local commits have diverged) is logged at info and doesn't fail the
        fetch.
      - A dirty main worktree is not touched (AC-17).
      - Other worktrees are never touched.
   3. Set `lastFetchedAt = clock.instant()` and call `saveAndFlush`.
4. **`fetchAll()`** is annotated `@Scheduled(fixedDelayString = "\${app.git.fetch-interval:15m}",
   initialDelayString = "\${app.git.fetch-interval:15m}")`.
   - It only runs when `SchedulingConfig` is active (`app.scheduling.enabled`). The tests call it
     directly.
   - Spring Framework 7 should parse the simple `15m` form. If `bootRun` rejects it, use `PT15M`
     as the default in the annotation and in `application.properties`, and say so in the PR.
   - If the token is blank, log at debug and return.
   - For each `findAllByState(READY)`, run `locks.withLock(it.id) { fetchUnderLock(it) }` inside
     a `try`:
     - catch `GitException` and `CheckoutException`;
     - log at warn: "Scheduled fetch of checkout {} (project {}) failed: {}", using the
       exception's message, which the runner has already scrubbed;
     - leave the row's `state` and `lastFetchedAt` as they were (AC-18).
5. **Controller.** Add `@PostMapping("/fetch") fun fetch(@PathVariable id: Long):
   CheckoutResponse`, with KDoc.

## Tests

`CheckoutFetchTests` is a `@DataJpaTest` with the same imports as `CheckoutServiceTests`
(including `CheckoutTestConfig`). Projects are saved through `ProjectRepository` with `file://`
remotes, which is how the GitHub-only URL rule is bypassed. Each test starts from a `READY`
checkout created with `start`. A scratch clone of the remote pushes the "new commits on GitHub".

- After the scratch clone pushes to `main` and the clock is advanced, `fetch` fast-forwards the
  main worktree to the new head and sets `lastFetchedAt` to the advanced time (AC-17).
- With an untracked file in main, `fetch` leaves `HEAD` where it was and still updates
  `lastFetchedAt` (AC-17).
- Add a worktree with `GitFixtures.git(dir, "worktree", "add", …)` and push a commit to its
  branch from the scratch clone. After `fetch`, the worktree's `HEAD` is unchanged (AC-17).
- `fetch` on a `FAILED` checkout throws `CheckoutNotReadyException`. With no checkout, it throws
  `CheckoutNotFoundException`.
- With the remote deleted, `fetch` throws `GitRemoteException` with failure `NOT_FOUND` (AC-28).
- `fetchAll` covers two `READY` checkouts. One remote lives at
  `file://<tmp>/test-token/remote.git` and is deleted after the clone (AC-18, AC-27):
  - the good checkout gets the new `lastFetchedAt`;
  - the bad one stays `READY` with its old `lastFetchedAt`;
  - a Logback `ListAppender` on `CheckoutService`'s logger has exactly one warn line;
  - no formatted message contains `test-token`.

Additions to `CheckoutControllerTests`:

- `POST …/fetch` answers `401` without a token (AC-26).
- It answers `200` with the Checkout shape (AC-17).
- Mapped errors (AC-28, AC-29):
  - `CheckoutNotReadyException` → `409 checkout_not_ready`;
  - `CheckoutNotFoundException` → `404 checkout_not_found`;
  - `GitRemoteException(AUTH)` and `GitRemoteException(UNREACHABLE)` → `502 git_remote_error`,
    each with its own detail;
  - `GitTimeoutException` → `504 git_timeout`;
  - `GitNotConfiguredException` → `503 git_not_configured`.

## Checks

- Run `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`, or use the throwaway
  `gradle:jdk25` container from `api/CLAUDE.md`.
- The app starts against MySQL with `./gradlew bootRun` in `factory-api`, and the `@Scheduled`
  interval parses.
- On a ready checkout, `POST …/fetch` answers `200` and moves `lastFetchedAt`. With
  `GIT_FETCH_INTERVAL=1m`, the log shows the scheduled fetch run without the token.

## Out of scope

- Worktrees (task 05).
- Push (task 06).
- `409 project_archived` (task 07).
- The app's Fetch button (task 09).
