---
feature: 004-features
task: 04-api-feature-create
layer: api
status: approved
approved: 2026-11-06
branch: feat/004-features/04-api-feature-create
base: feat/004-features/03-api-feature-read
covers: [AC-1, AC-2, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10]
---

# 04 — Create a feature

## Goal

`POST /api/projects/{id}/features` runs the create sequence: under the checkout's lock it fetches,
reads and validates the project's workflow and template, numbers the feature, adds its worktree,
writes and commits `requirements.md` from the template, pushes, and saves the record. Any failure
after the worktree is added undoes it. AC-4 is covered on the API side (`409`); the app hides the
action in 11.

## Contract

Provides, copied from task-breakdown.md:

### Numbering, slug, branch, folder

- `number` = 1 + max(the project's feature numbers, every `NNN` of a `feature/<NNN>-*` folder in
  `git ls-tree -d --name-only origin/<default> feature/` in the clone, after a fetch). Shown as
  three digits (`%03d`).
- `slug`: name lowercased, runs of `[^a-z0-9]` → `-`, trimmed of `-`, cut to 40 characters, then
  trimmed of `-` again. Empty → `400 invalid_name`.
- `code` = `<NNN>-<slug>`; `branch` = `feat/<code>/00-docs`; `folder` = `feature/<code>`.
- One create at a time per project (the checkout's lock from 003 covers the fetch, number and
  worktree add).

### Create sequence (api)

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

Any failure in 5–8 removes the worktree (`force`) and the branch, saves nothing, and answers with
the failure's own code (`502 git_remote_error`, `504 git_timeout`, `500 git_error`).

### Routes (this task's row)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST /api/projects/{id}/features` | `{ "name": "Features" }` | `201` Feature | `400 invalid_name`, `409 project_archived`, `409 checkout_not_ready`, `409 workflow_invalid`, `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout`, `500 git_error` |

Plus, on every route: `404 project_not_found`, `400 invalid_request` (unreadable body). The
response is the Feature JSON from task 03.

## Files

Main code under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `service/FeatureCreator.kt` — new: `@Service` with `create(projectId, name: String?): FeatureView`.
- `dto/FeatureDtos.kt` — change: `CreateFeatureRequest(val name: String? = null)`.
- `controller/FeatureController.kt` — change: `POST ""` → `201`.
- `controller/FeatureExceptionHandler.kt` — change: map the create errors (below).
- `exception/FeatureExceptions.kt` — change: `FeatureProjectArchivedException`.

Tests under `api/src/test/kotlin/com/toptier/custom/feature/`: `FeatureCreateTests.kt`,
`FeatureConcurrencyTests.kt` (new), `FeatureControllerTests.kt` (change), and a
`FeatureFixtures.kt` helper (new) that seeds a bare remote with a workflow.

## Steps

1. **`FeatureCreator`** injects `ProjectService`, `RepositoryCheckoutRepository`,
   `CheckoutService`, `WorktreeService`, `CheckoutLocks`, `GitRunner`, `WorkflowReader`,
   `WorkflowCodec`, `FeatureRepository`, `FeatureService` (for `view`), `FeatureProperties`,
   `Clock`. Not `@Transactional`; no transaction spans a git call. `create(projectId, rawName)`:
   1. `project = projects.get(projectId)`; `project.archived` → `FeatureProjectArchivedException`
      ("This project is archived. Restore it to start a feature.").
   2. `checkout = checkouts.findByProjectId(projectId)`; `null` or `state != READY` →
      `CheckoutNotReadyException()` (the checkout package's, so the message matches 003) (AC-4).
   3. `name = FeatureNaming.name(rawName)`, `slug = FeatureNaming.slug(name)` (AC-8).
   4. `runner.requireToken()` (→ `503 git_not_configured` before anything is written).
   5. `locks.withLock(checkout.id!!) { … }` (reentrant, so `WorktreeService.add` and `push`, which
      take the same lock, run inside it) (AC-10):
      1. `checkoutService.fetchUnderLock(checkout)`.
      2. `clone = Path.of(checkout.path)`; `workflow = reader.read(clone)`;
         `template = reader.requireTemplate(clone)` (AC-2). Nothing has been created yet, so a
         `WorkflowInvalidException` leaves no trace.
      3. `folders = runner.runOrThrow(clone, listOf("ls-tree", "-d", "--name-only",
         "origin/${checkout.defaultBranch}", "feature/")).lines().filter { it.isNotBlank() }`;
         `number = FeatureNaming.nextNumber(features.maxNumber(projectId), folders)` (AC-7);
         `code`, `branch`, `folder` from `FeatureNaming`.
      4. `worktree = worktreeService.add(projectId, branch, checkout.defaultBranch).worktree`. A
         `BranchCheckedOutException` here (a leftover branch) passes through as `409
         branch_checked_out`.
      5. Inside `try`:
         - `dir = Path.of(worktree.path)`; `Files.createDirectories(dir.resolve(folder))`;
           write `dir.resolve("$folder/requirements.md")` with
           `template.replace("<NNN>-<slug>", code).replace("<Feature name>", name).replace("<date>",
           LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString())` (AC-6).
         - `runner.runOrThrow(dir, listOf("add", "--", folder))`;
           `runner.runOrThrow(dir, listOf("-c", "user.name=${props.commitName}", "-c",
           "user.email=${props.commitEmail}", "-c", "commit.gpgsign=false", "commit", "-m",
           "$code: start feature"))` (`-c user.*` sets author and committer).
         - `worktreeService.push(projectId, worktree.id!!, force = false)`; remember
           `pushed = true`.
         - Save `Feature` with `status = workflow.initialState`, `workflow = codec.write(workflow)`,
           `branch`, `folder`, `createdAt = updatedAt = clock.instant()`, via `saveAndFlush`
           (AC-1, AC-5).
      6. `catch (ex: Exception)`: `undo(projectId, clone, worktree, pushed)`, then rethrow `ex`.
   6. Return `featureService.view(saved)`.
2. **`undo`** (private; each part logged and swallowed so the original error is what the caller
   sees) (AC-9):
   - `worktreeService.remove(projectId, worktree.id!!, force = true)`;
   - `runner.run(clone, listOf("branch", "-D", branch))` when `refs/heads/<branch>` still exists
     (`remove` keeps a branch origin doesn't have);
   - if `pushed`: `runner.run(clone, listOf("push", "origin", "--delete", branch), network = true)`.
3. **Controller:** `@PostMapping` with `@RequestBody CreateFeatureRequest` →
   `ResponseEntity.status(HttpStatus.CREATED).body(FeatureResponse.from(creator.create(id, request.name)))`.
4. **`FeatureExceptionHandler`** additions (same `problem` helper; git messages exactly as
   `CheckoutExceptionHandler` builds them):

   | Exception | Response |
   | --------- | -------- |
   | `InvalidFeatureNameException` | `400 invalid_name`, plus `setProperty("errors", mapOf("name" to message))` so the form can put it on the field |
   | `WorkflowInvalidException` | `409 workflow_invalid` |
   | `FeatureProjectArchivedException`, `CheckoutProjectArchivedException` | `409 project_archived` |
   | `CheckoutNotReadyException`, `CheckoutNotFoundException` | `409 checkout_not_ready`, detail "The project's repository checkout isn't ready. Check it out on the project page first." |
   | `BranchCheckedOutException` | `409 branch_checked_out` |
   | `GitRemoteException` | `502 git_remote_error` |
   | `GitNotConfiguredException` | `503 git_not_configured` |
   | `GitTimeoutException` | `504 git_timeout`, "GitHub didn't answer within <describe(timeout)>. Try again." |
   | `GitCommandException` | `500 git_error`, "A git command failed on the server." (stderr logged, never returned) |

   `describe` is `internal` in the checkout package; it's the same Gradle module, so it's
   callable.

## Tests

`FeatureFixtures.seededRemote(tmp)`: a `GitFixtures.bareRemote`, then a scratch clone that commits
the contract's `feature/workflow.json`, the three `.claude/skills/<skill>/SKILL.md` files, a
`feature/_templates/requirements.md` copied from the real template's first lines
(`feature: <NNN>-<slug>`, `# <Feature name>`, `- <date> — Created.`), and an optional extra folder
(`feature/006-x/requirements.md`), and pushes.

`FeatureCreateTests` (`@DataJpaTest`, imports as `WorktreePushTests` plus the feature services,
`WorkflowReader`, `WorkflowCodec`, `JsonMapper` via `JacksonAutoConfiguration` or a test bean,
`FeatureProperties` via `@EnableConfigurationProperties`; ready checkout of the seeded remote in
`@BeforeEach`):

- create "Feature records" → number 1, `status = "requirements"`, stored workflow equals the
  file, branch `feat/001-feature-records/00-docs` (AC-1, AC-5);
- the worktree row exists, `feature/001-feature-records/requirements.md` in it has
  `feature: 001-feature-records`, `# Feature records` and today's date; `git log -1` there has
  author and committer `factory <factory@users.noreply.github.com>` and subject
  `001-feature-records: start feature`; the remote's `refs/heads/feat/001-feature-records/00-docs`
  equals the worktree's `HEAD` (AC-6);
- with features up to 4 saved and `feature/006-x/` on the remote's `main` → number 7 (AC-7);
- the next create after a successful one gets number 2;
- each workflow problem (no file, bad JSON, a missing `SKILL.md`, no template) →
  `WorkflowInvalidException` with the file named; no worktree row, no `feat/*` branch in the clone
  or the remote, no feature row (AC-2);
- `""`, 101 characters, `"!!!"` → `InvalidFeatureNameException`, nothing created (AC-8);
- archived project → `FeatureProjectArchivedException`; no checkout, and a `FAILED` checkout →
  `CheckoutNotReadyException` (AC-4);
- a remote with a `pre-receive` hook that exits 1 (installed after the checkout is ready) →
  `GitRemoteException`; the worktree row and folder are gone, the local branch is gone, no feature
  row (AC-9);
- a failing save (a pre-existing row with the same project and number inserted by a test-only
  `maxNumber` stub, or a `@MockitoSpyBean FeatureRepository` that throws on `saveAndFlush`) → the
  remote branch is deleted too.

`FeatureConcurrencyTests` (`@DataJpaTest` with `@Transactional(propagation =
Propagation.NOT_SUPPORTED)` on the class so the threads see each other's commits; tables and the
root folder cleaned in `@AfterEach`): two creates of "Same name" started together from two
threads (a `CountDownLatch`) both succeed with numbers 1 and 2 and branches
`feat/001-same-name/00-docs` and `feat/002-same-name/00-docs` (AC-10).

`FeatureControllerTests` additions (`@MockitoBean FeatureCreator`):

- `POST` answers `401` without a token (AC-27), `201` with the Feature JSON, and passes `name`
  through (AC-5);
- `invalid_name` → `400` with `errors.name` (AC-8); `workflow_invalid`, `project_archived`,
  `checkout_not_ready` (both exceptions) → `409` (AC-2, AC-4);
- `GitRemoteException` → `502`, `GitNotConfiguredException` → `503`, `GitTimeoutException` →
  `504`, `GitCommandException` → `500` with no stderr in the body (AC-9);
- a body that isn't JSON → `400 invalid_request`.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.
- The app starts against MySQL. With a test project whose default branch has the workflow file,
  `POST /api/projects/<id>/features` with `{"name":"Probe"}` answers `201`, the checkout card lists
  the worktree, and the branch is on GitHub. Delete the probe branch and worktree afterwards.

## Out of scope

- Status and step routes (05), the chat (06).
- Removing features or their worktrees (non-goal); recovering a feature whose worktree was later
  removed (the agent says it's missing; 07).
- Avoiding the second fetch inside `WorktreeService.add` (it always fetches; the cost is one extra
  `git fetch` per create).
