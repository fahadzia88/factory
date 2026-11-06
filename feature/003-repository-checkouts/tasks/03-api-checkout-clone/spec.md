---
feature: 003-repository-checkouts
task: 03-api-checkout-clone
layer: api
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/03-api-checkout-clone
base: feat/003-repository-checkouts/02-api-git-runner
covers: [AC-2, AC-3, AC-4, AC-5, AC-26, AC-27, AC-28, AC-29]
---

# 03 — Checkout: clone in the background

## Goal

`GET` and `POST /api/projects/{id}/checkout`: the clone runs in the background, a failed checkout
can be retried, and clones interrupted by a restart are marked failed on startup. Also adds the
per-checkout lock and the exception handler with every shared error code that later tasks use.

## Contract

Provides, copied from task-breakdown.md:

### Checkout (JSON)

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

In this task `missing` is always `false` and `head` always `null`; task 05 fills them in.

### Routes (this task's rows)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET /api/projects/{id}/checkout` | — | `200` Checkout | `404 project_not_found`, `404 checkout_not_found` (the app shows "Check out repository") |
| `POST /api/projects/{id}/checkout` | — | `202` Checkout (`cloning`); the clone runs in the background | `409 project_archived`, `409 checkout_exists` (cloning or ready; a `failed` one is replaced, which is Retry), `503 git_not_configured` |

On every route above, in addition to the listed errors:

- `404 project_not_found` when no project has the id; on the `…/checkout/fetch` and
  `…/worktrees…` routes, `404 checkout_not_found` when the project has no checkout.
- `400 invalid_request` for a non-numeric id, an unreadable JSON body, or a missing required
  parameter or field.
- `500 git_error` when a local git command (no network) fails unexpectedly; `detail` is a plain
  message with no git output.

- Git failures map as: auth rejected or other remote error → `502 git_remote_error` with a plain
  message (no stderr containing URLs or headers); timeout → `504 git_timeout`. Any text taken
  from git output is scrubbed of the token.
- One git operation at a time per checkout (an in-process lock keyed by checkout id).

`409 project_archived` is added in task 07, with the other archived-project refusals. The handler
maps `502 git_remote_error` and `504 git_timeout` here, though only tasks 04–06 throw them.

From "Database (api only)":

- On startup, `CLONING` rows are set to `FAILED` with "The clone was interrupted by a restart."

From "api config": `app.git.clone-timeout` (`GIT_CLONE_TIMEOUT`, default `10m`) — clone.

Every route needs `Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as
`ProblemDetail` (`application/problem+json`) with a `code` property the app and agent switch on,
and a `detail` they can show as is.

## Files

Main code under `api/src/main/kotlin/com/toptier/custom/checkout/`:

- `config/CheckoutConfig.kt` — new: the `checkoutExecutor` bean.
- `service/CheckoutLocks.kt` — new: per-checkout lock.
- `service/CheckoutService.kt` — new: `get`, `start`, `clone`, `failInterruptedClones`, and the
  `CheckoutView` / `WorktreeView` / `HeadCommit` data classes it returns.
- `dto/CheckoutDtos.kt` — new: `CheckoutResponse`, `WorktreeResponse`, `HeadResponse`.
- `exception/CheckoutExceptions.kt` — new: sealed `CheckoutException` with
  `CheckoutNotFoundException`, `CheckoutExistsException`, `CheckoutNotReadyException`,
  `GitRemoteException(failure: GitFailure)`.
- `controller/CheckoutController.kt` — new: `@RequestMapping("/api/projects/{id}/checkout")`.
- `controller/CheckoutExceptionHandler.kt` — new:
  `@RestControllerAdvice(basePackageClasses = [CheckoutController::class])`, which also covers
  the controllers that tasks 05–07 add to the same package.
- Tests under `api/src/test/kotlin/com/toptier/custom/checkout/`:
  - `CheckoutTestConfig.kt` — new: shared `@TestConfiguration`, reused by tasks 04–07;
  - `CheckoutServiceTests.kt`, `CheckoutControllerTests.kt` — new.

## Steps

1. **Executor.** `CheckoutConfig`: `@Bean("checkoutExecutor") fun checkoutExecutor():
   ThreadPoolTaskExecutor`, with core and max pool 2, queue capacity 20, thread prefix
   `checkout-clone-` and `setWaitForTasksToCompleteOnShutdown(false)`. A restart leaves the
   clone `CLONING`, and the startup reset fails it. `CheckoutService` takes it as
   `@Qualifier("checkoutExecutor") executor: TaskExecutor`, so tests can supply a
   `SyncTaskExecutor` under that name.
2. **Locks.** `CheckoutLocks.withLock(checkoutId: Long, block: () -> T): T` uses a
   `ConcurrentHashMap<Long, ReentrantLock>` with `computeIfAbsent`, then
   `lock()`/`try`/`finally unlock()`. It's reentrant so that later tasks can fetch while
   holding it. There's no wait limit: each git call inside has its own timeout.
3. **Service shape.** The constructor is `CheckoutService(projects: ProjectService, checkouts,
   worktrees, runner: GitRunner, paths: CheckoutPaths, props: GitProperties, locks, executor,
   transactionManager: PlatformTransactionManager, clock: Clock)`.
   - **No class-level `@Transactional`.** A transaction must never stay open across a git call,
     and the clone must start only after its `CLONING` row is committed. Single writes use
     `saveAndFlush`; the clone's final write uses `TransactionTemplate(transactionManager)`.
   - Data classes: `HeadCommit(sha, subject)`, `WorktreeView(worktree, missing: Boolean, head:
     HeadCommit?)` and `CheckoutView(checkout, worktrees: List<WorktreeView>)`.
   - `fun view(c): CheckoutView` loads worktrees with
     `findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc` only when the checkout is `READY`, and
     returns an empty list otherwise. Each one becomes `WorktreeView(it, false, null)`; task 05
     replaces these placeholders.
4. **`get(projectId)`.** Call `projects.get(projectId)`, which throws
   `ProjectNotFoundException`. Then `findByProjectId`, or throw `CheckoutNotFoundException`.
   Return `view(...)`.
5. **`start(projectId)`.**
   1. Call `projects.get(projectId)`.
   2. Call `runner.requireToken()`. A blank token throws `GitNotConfiguredException` (503)
      before anything is written (AC-27).
   3. If a row exists in `CLONING` or `READY`, throw `CheckoutExistsException` (AC-4). A
      `FAILED` row is reused (Retry, AC-5): set `state = CLONING`, `error = null`,
      `defaultBranch = null`, `lastFetchedAt = null`, `createdAt = now`, and call
      `worktrees.deleteByCheckoutId(id)`.
   4. If there's no row, create `RepositoryCheckout(projectId, path =
      paths.checkoutDir(projectId).toString(), state = CLONING, createdAt = now)`.
   5. Call `saveAndFlush`. A `DataIntegrityViolationException` (two requests at once) becomes
      `CheckoutExistsException`.
   6. Call `executor.execute { clone(id, project.repositoryUrl) }`. On `TaskRejectedException`,
      set the row `FAILED` with "Too many clones are running. Retry in a minute."
   7. Return `view(row)`. The controller answers `202`.
6. **`clone(checkoutId, url)`** is public so tests can call it.
   1. Take `locks.withLock(checkoutId)`. Reload the row, and return if it's gone or isn't
      `CLONING`.
   2. Set `dir = paths.checkoutDir(projectId)` and `trees = paths.worktreesDir(projectId)`.
      Delete both recursively if they exist (`FileSystemUtils.deleteRecursively`); they are
      leftovers of a failed or interrupted clone. Then `Files.createDirectories(paths.root)`.
   3. Run `runner.runOrThrow(null, listOf("clone", "--", url, dir.toString()), network = true,
      timeout = props.cloneTimeout)`. `origin` is the stored URL, with no credentials.
   4. Read the default branch with `runner.runOrThrow(dir, listOf("symbolic-ref", "--short",
      "HEAD")).trim()`.
   5. In one `TransactionTemplate` block, reload the row. If it's gone (task 07: the project was
      archived meanwhile), delete `dir` and `trees` and stop. Otherwise set `state = READY`,
      `defaultBranch`, `lastFetchedAt = now` and `error = null`, and save the main
      `RepositoryWorktree(checkoutId, branch = defaultBranch, path = dir.toString(), main =
      true, baseBranch = null, createdAt = now)` (AC-2).
   6. On any `Exception`, delete `dir` and `trees`. If the row still exists, set it `FAILED`
      with `cloneFailure(ex, props.cloneTimeout).take(500)`. Log at warn with the checkout id
      and the message (already scrubbed); log an unexpected exception at error with its stack.
   7. `internal fun cloneFailure(ex: Exception, timeout: Duration): String` gives the reason in
      plain words (AC-5):
      - `GitTimeoutException` → "The clone took longer than 10 minutes." (from `timeout`);
      - `GitCommandException` by `failure`:
        - `NOT_FOUND` → "GitHub couldn't find the repository, or the git token can't see it.";
        - `AUTH` → "GitHub rejected the git token. Check that API_GIT_TOKEN has Contents: read
          and write on this repository.";
        - `UNREACHABLE` → "GitHub couldn't be reached.";
        - otherwise "The clone failed (git exit <code>).";
      - `GitNotConfiguredException` → its message;
      - anything else → "The clone failed unexpectedly."
7. **`failInterruptedClones()`** is an `@EventListener(ApplicationReadyEvent::class)`. It calls
   `checkouts.updateState(CLONING, FAILED, "The clone was interrupted by a restart.")` and logs
   the count at info when it's above 0. A Retry's clone cleans up the folders (step 6.2).
8. **DTOs.** `CheckoutResponse.from(view)` has the fields of the contract, with `state =
   checkout.state.json`. `WorktreeResponse.from(WorktreeView)` sets `head` to `HeadResponse(sha,
   subject)` or `null`. Nulls are serialized, so `error: null` and `head: null` appear.
9. **Exceptions** (each message is the response's `detail`):
   - `CheckoutNotFoundException`: "This project has no repository checkout."
   - `CheckoutExistsException`: "This project already has a checkout."
   - `CheckoutNotReadyException`: "The checkout isn't ready yet."
   - `GitRemoteException(failure)`, by `failure`:
     - `NOT_FOUND` / `AUTH`: as in step 6.7;
     - `UNREACHABLE`: "GitHub couldn't be reached. Try again.";
     - otherwise: "Git couldn't complete the request with GitHub. Try again."
10. **Controller.** `GET` returns `CheckoutResponse`; `POST` returns
    `ResponseEntity.status(ACCEPTED).body(...)`. KDoc on both.
11. **Handler.** One private `problem(status, code, detail)` calls
    `ProblemDetail.forStatusAndDetail` and `setProperty("code", code)`.
    - `ProjectNotFoundException` → `404 project_not_found`, "Project not found." It is thrown
      by the project package and caught here because this advice is scoped to the checkout
      controllers.
    - `CheckoutNotFoundException` → `404 checkout_not_found`.
    - `CheckoutExistsException` → `409 checkout_exists`.
    - `CheckoutNotReadyException` → `409 checkout_not_ready`.
    - `GitRemoteException` → `502 git_remote_error`.
    - `GitNotConfiguredException` → `503 git_not_configured`.
    - `GitTimeoutException` → `504 git_timeout`, "GitHub didn't answer within <timeout>. Try
      again."
    - `MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException` and
      `MissingServletRequestParameterException` → `400 invalid_request`.
    - `GitCommandException` that escapes a service → `500 git_error`, "A git command failed on
      the server." Log it at error.

    Log the 502, 503 and 504 responses at info, with the exception message.

## Tests

`CheckoutTestConfig` is a `@TestConfiguration` that provides:

- `MutableClock`;
- `GitProperties(token = "test-token", root = ROOT)`, where `ROOT` comes from
  `Files.createTempDirectory` in the companion;
- `@Bean("checkoutExecutor") SyncTaskExecutor()`, so clones finish inside `start`.

`CheckoutServiceTests` is a `@DataJpaTest` with `@Import(CheckoutService::class,
ProjectService::class, CheckoutLocks::class, GitRunner::class, CheckoutPaths::class,
CheckoutTestConfig::class)`, plus `@MockitoBean GitHubRepositoryChecker` (which `ProjectService`
needs). `@AfterEach` empties `ROOT`.

How the GitHub-only URL rule is bypassed: `ProjectService` accepts only
`https://github.com/<owner>/<repo>`, but `CheckoutService` clones whatever `repositoryUrl` is
stored. The tests save `Project` rows directly through `ProjectRepository`, with
`repositoryUrl = GitFixtures.fileUrl(bareRemote)`. No production path accepts another URL, and
no test calls GitHub.

- `start` clones. The row is `READY`, `defaultBranch` is `main`, `lastFetchedAt` is now, there's
  one main worktree row at `<root>/<id>`, and `rev-parse HEAD` in the folder equals the remote's
  `main` (AC-2, AC-3).
- A remote whose default branch is `trunk` gives `defaultBranch = "trunk"`. The clone's
  `.git/config` has `origin` set to the stored URL and no token (AC-2, AC-27).
- `start` on a `READY` checkout throws `CheckoutExistsException`, and the rows are unchanged
  (AC-4).
- A missing remote ends `FAILED` with the "couldn't find" reason and leaves no folder. After the
  URL is fixed, `start` reuses the same row id and ends `READY` (AC-5).
- `cloneFailure` gives the timeout, `AUTH` and `UNREACHABLE` reasons (AC-5).
- `failInterruptedClones` turns a `CLONING` row `FAILED` with the restart message and leaves a
  `READY` row alone (AC-5).
- An unknown project throws `ProjectNotFoundException`, and `get` without a checkout throws
  `CheckoutNotFoundException` (AC-29).

`CheckoutControllerTests` is a `@WebMvcTest(CheckoutController::class)` importing
`SecurityConfig` and `CheckoutExceptionHandler`, with
`@EnableConfigurationProperties(SecurityProperties::class)`,
`app.security.api-token=test-token` and `@MockitoBean CheckoutService`.

- Both routes answer `401` without a token (AC-26).
- `GET` answers `200` with every contract field and the main worktree first; `POST` answers `202`
  with `state: "cloning"` and `worktrees: []` (AC-2).
- `404 project_not_found`, `404 checkout_not_found` and a `400 invalid_request` for
  `/api/projects/abc/checkout` (AC-29).
- `409 checkout_exists` (AC-4) and `503 git_not_configured` (AC-27).
- On `GET`, a mocked `GitRemoteException` gives `502 git_remote_error` with no git output in the
  detail, `GitTimeoutException` gives `504 git_timeout`, and `GitCommandException` gives
  `500 git_error` (AC-28).
- Errors have content type `application/problem+json` and carry `code`.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`, or the throwaway
  `gradle:jdk25` container from `api/CLAUDE.md`.
- The app starts against MySQL (`./gradlew bootRun` in `factory-api`).
- With `API_GIT_TOKEN` in the root `.env`, check against a real project:
  - `POST …/checkout` answers `202`, and `GET` then turns `ready`;
  - `docker exec factory-api ls /repos/<id>` and `docker exec factory-agent ls /repos/<id>` match
    (AC-3);
  - `grep -r <token> /repos/<id>/.git` finds nothing.
- Without the token, `POST` answers `503 git_not_configured`.

## Out of scope

- Fetch and the scheduled fetch (task 04).
- Worktrees, `missing` and `head` (task 05), and push (task 06).
- Unsaved work, archive, `409 project_archived` and `GET /api/checkouts` (task 07).
