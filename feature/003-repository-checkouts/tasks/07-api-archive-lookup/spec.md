---
feature: 003-repository-checkouts
task: 07-api-archive-lookup
layer: api
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/07-api-archive-lookup
base: feat/003-repository-checkouts/06-api-worktree-push
covers: [AC-19, AC-20, AC-21, AC-22, AC-24]
---

# 07 — Unsaved work, archive deletes the checkout, lookup by repository URL

## Goal

`GET /api/projects/{id}/checkout/unsaved` for the archive warning; archiving a project deletes its
checkout (folders and rows) through a `ProjectArchivedEvent`; archived projects get
`409 project_archived` from the checkout routes that change something; and
`GET /api/checkouts?repositoryUrl=` lets the agent find the factory repository's checkout. AC-21,
AC-22 and AC-24 are covered on the API side only (the app and agent parts are tasks 09 and 11).

## Contract

Provides, copied from task-breakdown.md:

### Unsaved work (JSON)

```json
{
  "worktreeId": 9,
  "branch": "feat/003-x/01-api-y",
  "changedFiles": ["api/src/main/kotlin/…/Foo.kt"],
  "unpushedCommits": [{ "sha": "a1b2c3d", "subject": "WIP" }]
}
```

- `changedFiles`: `git status --porcelain` paths (tracked changes and untracked files), at most
  50.
- `unpushedCommits`: `git log <branch> --not --remotes=origin`, at most 50.

### Routes (this task's rows, and `project_archived` on the earlier ones)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET /api/projects/{id}/checkout/unsaved` | — | `200` `[Unsaved work]`, only worktrees with something; `[]` when there's no checkout | `404 project_not_found` |
| `GET /api/checkouts?repositoryUrl=<url>` | exact match on the project's `repositoryUrl` | `200` Checkout | `404 project_not_found`, `404 checkout_not_found` |

`409 project_archived` on: `POST /api/projects/{id}/checkout`, `POST …/checkout/fetch`,
`POST …/checkout/worktrees`, `DELETE …/checkout/worktrees/{worktreeId}`,
`POST …/checkout/worktrees/{worktreeId}/push` (from those routes' rows).

On every route above, in addition to the listed errors:

- `404 project_not_found` when no project has the id; on the `…/checkout/fetch` and
  `…/worktrees…` routes, `404 checkout_not_found` when the project has no checkout.
- `400 invalid_request` for a non-numeric id, an unreadable JSON body, or a missing required
  parameter or field.
- `500 git_error` when a local git command (no network) fails unexpectedly; `detail` is a plain
  message with no git output.

### Archive (api ↔ app, extends feature 001)

- `POST /api/projects/{id}/archive` is unchanged for callers, but now also deletes the
  checkout: both folders and both tables' rows, whatever their state. It doesn't ask for force;
  the app warns first using `GET …/checkout/unsaved`.
- Restore doesn't touch checkouts.

### agent → api (consumed by task 11)

- Resolve: `GET /api/checkouts?repositoryUrl=https://github.com/<GITHUB_REPO>`. A `404`, or a
  state other than `ready` → the tool fails with "Register the factory repository
  (https://github.com/<GITHUB_REPO>) as a project and check it out on its project page first."

## Files

Project package (feature 001; none of these files are in `.claude/base-files`, so they're project
code):

- `api/src/main/kotlin/com/toptier/custom/project/service/ProjectArchivedEvent.kt` — new:
  `data class ProjectArchivedEvent(val projectId: Long)`.
- `api/src/main/kotlin/com/toptier/custom/project/service/ProjectService.kt` — change: inject
  `ApplicationEventPublisher`; `archive` publishes the event.
- `api/src/main/kotlin/com/toptier/custom/project/domain/ProjectRepository.kt` — change: add
  `findByRepositoryUrl(repositoryUrl: String): Project?`.
- `api/src/main/kotlin/com/toptier/custom/project/controller/ProjectController.kt` — change: KDoc
  on `archive` says it also deletes the repository checkout.

Checkout package (`api/src/main/kotlin/com/toptier/custom/checkout/`):

- `service/CheckoutArchiver.kt` — new: `@Component` with the `ProjectArchivedEvent` listener.
- `service/CheckoutService.kt` — change: `unsaved(projectId)`, `findByRepositoryUrl(url)`, the
  archived check in `start` and `fetch`.
- `service/WorktreeService.kt` — change: the archived check in `add`, `remove`, `push`.
- `exception/CheckoutExceptions.kt` — change: add `CheckoutProjectArchivedException(projectId)`.
- `controller/CheckoutController.kt` — change: `GET /unsaved`.
- `controller/CheckoutLookupController.kt` — new: `@RequestMapping("/api/checkouts")`.
- `controller/CheckoutExceptionHandler.kt` — change: `project_archived`.

Tests:

- `api/src/test/kotlin/com/toptier/custom/checkout/CheckoutArchiveTests.kt` — new.
- `api/src/test/kotlin/com/toptier/custom/checkout/CheckoutControllerTests.kt`,
  `WorktreeControllerTests.kt` — change.
- `api/src/test/kotlin/com/toptier/custom/project/ProjectServiceTests.kt` — change: the event.

## Steps

1. **Event.** `ProjectArchivedEvent(projectId)` in the project package: the project package
   publishes it and knows nothing about checkouts; the checkout package already depends on
   `ProjectService`.
2. **ProjectService.archive.** Add `private val events: ApplicationEventPublisher` to the
   constructor. `archive(id)`: `val project = setArchived(id, true)`, then
   `events.publishEvent(ProjectArchivedEvent(id))`, every time, including an already archived
   project (archive is idempotent, so a repeat archive also retries a cleanup that failed). The
   event is published inside `archive`'s transaction. `restore` is unchanged.
3. **CheckoutArchiver** (`checkouts`, `worktrees`, `paths`, `locks`):
   - `@EventListener fun onProjectArchived(event: ProjectArchivedEvent)` — runs synchronously in
     the archive's transaction, so the row deletes commit or roll back with the archive:
     1. `checkouts.findByProjectId(event.projectId)` or return (no checkout: nothing to do, which
        is every project before task 09).
     2. `worktrees.deleteByCheckoutId(id)`; `checkouts.delete(checkout)`; `flush`.
     3. Folder deletion runs after the commit: `TransactionSynchronizationManager
        .registerSynchronization(object : TransactionSynchronization { override fun afterCommit()
        { deleteFolders(checkout) } })` when a transaction is active, else call it directly.
   - `deleteFolders(checkout)`:
     - `CLONING`: do nothing. The clone holds the lock for up to 10 minutes; task 03's
       `clone` finds its row gone when it finishes and deletes both folders itself.
     - Otherwise `locks.withLock(id)` (waits for a fetch or push in progress, at most the
       network timeout), then delete `paths.worktreesDir(projectId)` and
       `paths.checkoutDir(projectId)` recursively (`Files.walk` in reverse order, or Spring's
       `FileSystemUtils.deleteRecursively`). No git command is needed: both folders go.
     - Catch `IOException`, log at warn with the project id. The rows are already gone; leftover
       folders are cleaned by the next clone (task 03 step 6.2).
   - Unsaved work isn't checked: archive deletes regardless (AC-20); the app warns first.
4. **Archived refusals.** A private `requireActive(project)` in each service:
   `if (project.archived) throw CheckoutProjectArchivedException(project.id)` — right after
   `projects.get(...)` in `CheckoutService.start`, `fetch`, and `WorktreeService.add`, `remove`,
   `push` (AC-21). `get`, `unsaved` and the lookup still answer for archived projects (an archived
   project has no checkout, so `get` gives `404 checkout_not_found`). Detail: "This project is
   archived. Restore it to use its repository checkout."
5. **`CheckoutService.unsaved(projectId): List<UnsavedWork>`** (AC-20):
   - `projects.get(projectId)`; no checkout, or not `READY` → `emptyList()`.
   - `locks.withLock(id)` (`git status` refreshes the index, so don't race a fetch).
   - For each worktree, main included, that isn't `missing`: `inspector.unsaved(...)`; keep the
     non-empty ones, in the Checkout order (main first).
6. **`CheckoutService.findByRepositoryUrl(url: String): CheckoutView`** (AC-22, AC-24):
   `projectRepository.findByRepositoryUrl(url)` (exact text) or `ProjectNotFoundException`; then
   as `get(project.id)`. Inject `ProjectRepository` for this one query (`ProjectService` has no
   lookup by URL, and the project package's list filters don't match exactly).
   `ProjectNotFoundException` needs an id: pass `-1`; its message isn't shown (the handler's
   detail is "Project not found.").
7. **Controllers.**
   - `CheckoutController`: `@GetMapping("/unsaved") fun unsaved(@PathVariable id: Long):
     List<UnsavedWorkResponse>`.
   - `CheckoutLookupController`: `@GetMapping fun find(@RequestParam repositoryUrl: String):
     CheckoutResponse`. KDoc says it's for the agent's workflow tools.
8. **Handler.** `CheckoutProjectArchivedException` → `409 project_archived`. A missing
   `repositoryUrl` is already `400 invalid_request` (task 03 maps
   `MissingServletRequestParameterException`). The handler's `basePackageClasses = [CheckoutController::class]` already covers
   `CheckoutLookupController` (same package).

## Tests

`CheckoutArchiveTests` — `@DataJpaTest` with `@Transactional(propagation = NOT_SUPPORTED)` on the
class, so `ProjectService.archive` commits for real and the `afterCommit` folder deletion runs
(rows are cleaned in `@AfterEach` with `deleteAll`). Imports `ProjectService`, `CheckoutService`,
`WorktreeService`, `WorktreeInspector`, `CheckoutArchiver`, `CheckoutLocks`, `GitRunner`,
`CheckoutPaths` and `CheckoutTestConfig` (task 03: `SyncTaskExecutor`, `MutableClock`,
`GitProperties` with a temp root and `test-token`); `@MockitoBean
GitHubRepositoryChecker`. Projects are saved through `ProjectRepository` with `file://` remotes
(the GitHub-only URL rule lives in `ProjectService.create`/`update`, which these tests don't use
to create projects).

- A project with a ready checkout and a clean, pushed worktree: `archive` → `<root>/<id>` and
  `<root>/<id>.worktrees` are gone, and both tables have no rows for it (AC-19).
- A worktree with an uncommitted file and an unpushed commit: `archive` still deletes everything
  (AC-20).
- A project with no checkout: `archive` works and changes nothing on disk.
- A `CLONING` row (saved directly, folder created by hand): `archive` deletes the row and leaves
  the folder; then `checkoutService.clone(id, url)` returns without cloning and deletes the folder.
- `restore` after `archive` creates nothing; `checkoutService.get` →
  `CheckoutNotFoundException` (AC-21).

The unsaved, archived-refusal and lookup service tests are in the same class, so they share its
git setup:

- `unsaved` with a dirty worktree, an unpushed-commit worktree and a clean one → two entries, in
  order, with their `changedFiles` and `unpushedCommits`; a renamed file lists the new path
  (AC-20).
- `unsaved` with no checkout → `[]`; unknown project → `ProjectNotFoundException`.
- An archived project: `start` and `fetch` → `CheckoutProjectArchivedException` (AC-21).
- `findByRepositoryUrl` returns the checkout for the exact URL; a URL differing only by a
  trailing `/` → `ProjectNotFoundException`; a project with no checkout →
  `CheckoutNotFoundException` (AC-22, AC-24).

- An archived project (flag set through `ProjectRepository`, so the checkout is still there):
  `WorktreeService.add`, `remove`, `push` → `CheckoutProjectArchivedException` (AC-21).

`CheckoutControllerTests` — now `@WebMvcTest(CheckoutController::class,
CheckoutLookupController::class)`:

- `401` without a token on `GET …/unsaved` and `GET /api/checkouts` (AC-26).
- `GET …/unsaved` → `200` array with `worktreeId`, `branch`, `changedFiles`, `unpushedCommits`;
  `[]` (AC-20).
- `CheckoutProjectArchivedException` on `POST …/checkout` and `…/fetch` → `409 project_archived`
  (AC-21).
- `GET /api/checkouts?repositoryUrl=https://github.com/acme/factory` passes the URL through and
  returns the Checkout; `project_not_found` and `checkout_not_found` → `404`; no parameter →
  `400 invalid_request` (AC-22, AC-24).

`WorktreeControllerTests` additions: `409 project_archived` on add, remove and push (AC-21).

`ProjectServiceTests` addition (with `@RecordApplicationEvents` and an `ApplicationEvents`
field): `archive` publishes one `ProjectArchivedEvent` with the project's id; `restore`
publishes none.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q` (or the throwaway
  `gradle:jdk25` container from `api/CLAUDE.md`).
- The app starts against MySQL (`./gradlew bootRun` in `factory-api`).
- With a ready checkout: `GET …/checkout/unsaved` lists a dirty worktree; `POST
  /api/projects/<id>/archive` removes `/repos/<id>` and `/repos/<id>.worktrees` and the rows;
  `POST …/checkout` then answers `409 project_archived`; after restore it answers `202`.
- `GET /api/checkouts?repositoryUrl=https://github.com/<GITHUB_REPO>` returns the factory
  project's checkout once it's registered and checked out.

## Out of scope

- The archive dialog's warning (task 08) and the card's archived state (task 09).
- The agent's use of the lookup (task 11).
- Normalizing repository URLs for the lookup (the contract says exact match).
