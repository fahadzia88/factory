---
feature: 003-repository-checkouts
task: 05-api-worktrees
layer: api
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/05-api-worktrees
base: feat/003-repository-checkouts/04-api-checkout-fetch
covers: [AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12, AC-13, AC-29]
---

# 05 — Worktrees: add and remove

## Goal

Add and remove worktrees under `/api/projects/{id}/checkout/worktrees`, fill in each worktree's
`missing` and `head` in the Checkout response, and compute unsaved work (changed files, unpushed
commits) for the removal check. Task 07 exposes unsaved work as a route.

## Contract

Provides, copied from task-breakdown.md:

### Checkout (JSON), worktree fields

- `state`: `cloning`, `ready` or `failed`. `worktrees` is empty unless `ready`, main first,
  then by `createdAt`.
- `missing`: the folder (or its `.git`) isn't on disk; `head` is then `null`.
- `head` is read from disk on each request (local, no network).

The Worktree object is the one in the Checkout JSON example: `id`, `branch`, `path`, `main`,
`baseBranch`, `missing`, `head: { sha, subject }` and `createdAt`. Task 03 already serializes it.

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

### Routes (this task's rows)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST /api/projects/{id}/checkout/worktrees` | `{ "branch": "feat/x", "base": "main" }` (`base` optional) | `201` Worktree | `400 invalid_branch` (fails `check-ref-format --branch`), `400 base_not_found`, `409 branch_checked_out` (in a worktree already, the main one included), `409 checkout_not_ready`, `409 project_archived`, `502`/`503`/`504` as fetch |
| `DELETE /api/projects/{id}/checkout/worktrees/{worktreeId}` | `?force=true` optional | `204` | `404 worktree_not_found`, `409 main_worktree`, `409 worktree_unsaved` (problem has `unsaved`: Unsaved work), `409 project_archived` |

On every route above, in addition to the listed errors:

- `404 project_not_found` when no project has the id; on the `…/checkout/fetch` and
  `…/worktrees…` routes, `404 checkout_not_found` when the project has no checkout.
- `400 invalid_request` for a non-numeric id, an unreadable JSON body, or a missing required
  parameter or field.
- `500 git_error` when a local git command (no network) fails unexpectedly; `detail` is a plain
  message with no git output.

- Add worktree: fetch first. If a local branch exists: `worktree add <dir> <branch>`; else if
  `origin/<branch>` exists: `worktree add --track -b <branch>`; else from `origin/<base>` (or a
  local `<base>`), default the checkout's default branch, with `--no-track`.
- Folder names replace `/` with `__`, so `a/b` and `a__b` share a folder: adding the second
  answers `409 branch_checked_out`.
- Remove: `git worktree remove --force <dir>` (after the unsaved check, unless `force`), then
  `git branch -D` only if the branch is on origin. For a `missing` worktree:
  `git worktree prune` and delete the record.
- Git failures map as: auth rejected or other remote error → `502 git_remote_error` with a plain
  message (no stderr containing URLs or headers); timeout → `504 git_timeout`. Any text taken
  from git output is scrubbed of the token.
- One git operation at a time per checkout (an in-process lock keyed by checkout id).

`409 project_archived` is added in task 07.

## Files

Main code is under `api/src/main/kotlin/com/toptier/custom/checkout/`:

- `service/WorktreeInspector.kt` (new): a `@Component` with `missing(path)`, `head(path)` and
  `unsaved(worktree)`, all local git.
- `service/WorktreeService.kt` (new): `add`, `remove` and a shared `locate`.
- `service/CheckoutService.kt` (change):
  - `view` uses the inspector;
  - add `data class UnsavedWork(worktreeId, branch, changedFiles: List<String>, unpushedCommits:
    List<HeadCommit>)` with `isEmpty()`.
- `dto/WorktreeDtos.kt` (new): `AddWorktreeRequest(branch: String?, base: String?)`,
  `UnsavedWorkResponse` and `CommitResponse(sha, subject)`.
- `exception/CheckoutExceptions.kt` (change): add these exceptions:
  - `InvalidBranchException(name)`;
  - `BaseNotFoundException(base)`;
  - `BranchCheckedOutException(branch)`;
  - `WorktreeNotFoundException`;
  - `MainWorktreeException`;
  - `WorktreeUnsavedException(unsaved)`.
- `controller/WorktreeController.kt` (new): `@RequestMapping("/api/projects/{id}/checkout/worktrees")`.
- `controller/CheckoutExceptionHandler.kt` (change): map the new exceptions.

Tests are under `api/src/test/kotlin/com/toptier/custom/checkout/`:

- `WorktreeServiceTests.kt` and `WorktreeControllerTests.kt` (new);
- `GitFixtures.kt` (change): add a `scratchClone(remote)` helper if it's missing.

## Steps

1. **WorktreeInspector.**
   - `missing(path: Path)` returns `!Files.exists(path.resolve(".git"))`. This also covers a
     folder that is gone.
   - `head(path): HeadCommit?`:
     - returns null when the worktree is missing;
     - otherwise runs `runner.run(path, listOf("log", "-1", "--format=%h%x00%s"))`;
     - returns null on a non-zero exit, else splits the output on `\u0000` into SHA and subject.
   - `unsaved(worktree): UnsavedWork`:
     - Changed files come from `runner.run(path, listOf("status", "--porcelain=v1", "-z"))`.
       Split the output on NUL. Each entry is `XY path`, so take `substring(3)`. For `R` and `C`
       entries, skip the next field, which holds the original path. Keep the first 50.
     - Unpushed commits come from `runner.run(path, listOf("log", "--format=%h%x00%s", "-n",
       "50", "refs/heads/<branch>", "--not", "--remotes=origin"))`.
2. **`CheckoutService.view`.** Each worktree becomes `WorktreeView(it, inspector.missing(p),
   inspector.head(p))`.
3. **`locate(projectId, worktreeId?)`.**
   1. `projects.get(projectId)`.
   2. Load the checkout; if there is none, throw `CheckoutNotFoundException`.
   3. When `worktreeId` is given, load `findByIdAndCheckoutId(worktreeId, checkoutId)`; if there
      is none, throw `WorktreeNotFoundException` (AC-29).
4. **`add(projectId, branch: String?, base: String?): WorktreeView`.**
   1. Locate the checkout. If it isn't `READY`, throw `CheckoutNotReadyException`.
   2. Validate the names:
      - set `name = branch?.trim()`;
      - a missing name throws `InvalidBranchException`, and so does `!runner.isValidBranchName(name)`;
      - a non-blank `base` that isn't a valid name throws `BaseNotFoundException` (AC-8).
   3. If `name == checkout.defaultBranch` or `existsByCheckoutIdAndBranch` is true, throw
      `BranchCheckedOutException` (AC-8).
   4. Set `dir = paths.worktreeDir(projectId, name)`. If `dir` already exists, throw
      `BranchCheckedOutException`; this is the `__` collision. Its detail names the folder.
   5. Call `runner.requireToken()`. Then, inside `locks.withLock(checkout.id)`:
      1. Repeat step 3, because another request may have won the race.
      2. Call `checkoutService.fetchUnderLock(checkout)`, which fetches first.
      3. Run `worktree prune` and `Files.createDirectories(paths.worktreesDir(projectId))`.
      4. Choose the source in the clone directory (`Path.of(checkout.path)`), in the
         contract's order:
         - If `refs/heads/<name>` exists, run `worktree add <dir> <name>` and set `baseBranch =
           null`.
         - Else, if `refs/remotes/origin/<name>` exists, run `worktree add --track -b <name>
           <dir> origin/<name>` and set `baseBranch = null` (AC-6).
         - Else, set `from = base ?: defaultBranch`. Use `origin/<from>` if that ref exists,
           else a local `<from>`, else throw `BaseNotFoundException`. Then run `worktree add
           --no-track -b <name> <dir> <ref>` and set `baseBranch = from` (AC-7).

         Use `runOrThrow` for all of these. Names never start with `-`, and `dir` is absolute.
      5. `saveAndFlush` the row with `main = false`. On `DataIntegrityViolationException`, run
         `worktree remove --force <dir>` and throw `BranchCheckedOutException`.
   6. Return the `WorktreeView`. The controller answers `201`.
5. **`remove(projectId, worktreeId, force: Boolean)`.** No network is involved, so there is no
   token check.
   1. Locate the worktree. If it is `main`, throw `MainWorktreeException` (AC-12).
   2. Inside `locks.withLock`:
      1. If the worktree is `missing`, run `worktree prune` in the clone, delete the row and
         return (AC-13).
      2. If `!force` and `inspector.unsaved(...)` isn't empty, throw
         `WorktreeUnsavedException(unsaved)` (AC-11).
      3. Run `runOrThrow(clone, listOf("worktree", "remove", "--force", dir))`. If that fails
         because the folder isn't registered, delete the folder and run `worktree prune`.
      4. If `refs/remotes/origin/<branch>` exists, run `branch -D <branch>`. Otherwise keep the
         local branch, so commits that were forced away aren't lost.
      5. Delete the row (AC-10).
6. **Exception details.**
   - `InvalidBranchException`: "\"<name>\" isn't a valid branch name."
   - `BaseNotFoundException`: "Base branch <base> doesn't exist on GitHub or in the checkout."
   - `BranchCheckedOutException`: "<branch> is already checked out in a worktree." For the
     collision: "<branch> would use the folder <folder>, which another worktree has."
   - `WorktreeNotFoundException`: "Worktree not found."
   - `MainWorktreeException`: "The main worktree can't be removed."
   - `WorktreeUnsavedException`: "This worktree has changes that aren't on GitHub. Remove it
     with force to discard them."
7. **Handler.**

   | Exception | Response |
   | --------- | -------- |
   | `InvalidBranchException` | `400 invalid_branch` |
   | `BaseNotFoundException` | `400 base_not_found` |
   | `BranchCheckedOutException` | `409 branch_checked_out` |
   | `WorktreeNotFoundException` | `404 worktree_not_found` |
   | `MainWorktreeException` | `409 main_worktree` |
   | `WorktreeUnsavedException` | `409 worktree_unsaved`, plus `setProperty("unsaved", UnsavedWorkResponse.from(ex.unsaved))` |

8. **Controller.**
   - `POST` takes `@RequestBody AddWorktreeRequest` and answers `201` with `WorktreeResponse`.
   - `DELETE /{worktreeId}` takes `@RequestParam(required = false) force: Boolean?` and answers
     `204`.

## Tests

`WorktreeServiceTests` is a `@DataJpaTest` set up like `CheckoutServiceTests`: it imports
`CheckoutTestConfig`, `WorktreeService` and `WorktreeInspector`. The GitHub-only URL rule is
bypassed the same way: `Project` rows are saved with `file://` remotes. Each test starts from a
`READY` checkout of a bare remote that also has a branch `feat/existing`.

- A remote branch gets the folder `<root>/<id>.worktrees/feat__existing`, tracks
  `origin/feat/existing`, and gets a row with `baseBranch = null`. `get` lists it after main,
  with `head` set (AC-6).
- A commit that the scratch clone pushed before the add is the new worktree's head, because add
  fetches first (AC-6).
- A new branch with no base starts from `origin/main` and gets `baseBranch = "main"`. With
  `base = "feat/existing"` it starts from that branch (AC-7).
- These refusals leave nothing on disk or in the table (AC-8):
  - `bad..name` is refused with `InvalidBranchException`;
  - `base = "nope"` is refused with `BaseNotFoundException`;
  - the same branch twice is refused with `BranchCheckedOutException`, and so is `main`;
  - `a__b` after `a/b` is also refused with `BranchCheckedOutException`.
- A file written in one worktree is absent from another worktree and from main (AC-9).
- Removing a clean, pushed worktree deletes its folder, its row and the local branch (AC-10).
- For a worktree with an untracked file and an unpushed commit (AC-11):
  - removing without force throws `WorktreeUnsavedException`, which lists both, and the folder
    stays;
  - removing with force works, and the local branch is kept.
- A renamed file appears in `changedFiles` under its new path.
- Removing main throws `MainWorktreeException` (AC-12).
- After a worktree's folder is deleted by hand, `get` shows `missing = true` and `head = null`,
  and `remove` prunes and deletes the row (AC-13).
- Adding with the remote deleted throws `GitRemoteException` (AC-28).
- An unknown worktree id, or one from another project's checkout, throws
  `WorktreeNotFoundException` (AC-29).

`WorktreeControllerTests` is a `@WebMvcTest(WorktreeController::class)` importing
`SecurityConfig` and `CheckoutExceptionHandler`, with `@MockitoBean WorktreeService`.

- Add and remove both answer `401` without a token (AC-26).
- Add answers `201` with the Worktree JSON. `branch` and `base` reach the service, and a missing
  `base` arrives as null (AC-6, AC-7).
- `invalid_branch` and `base_not_found` answer `400`; `branch_checked_out` answers `409`
  (AC-8).
- Remove answers `204`, and `?force=true` reaches the service (AC-10).
- `worktree_unsaved` answers `409`, with `unsaved.changedFiles` and
  `unsaved.unpushedCommits[0].sha` in the body (AC-11).
- `main_worktree` answers `409` (AC-12).
- `project_not_found`, `checkout_not_found` and `worktree_not_found` answer `404`, and
  `/worktrees/abc` answers `400 invalid_request` (AC-29).
- On add, `GitTimeoutException` answers `504` and `GitNotConfiguredException` answers `503`.

## Checks

- Run `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`, or use the throwaway
  `gradle:jdk25` container described in `api/CLAUDE.md`.
- The app starts against MySQL with `./gradlew bootRun` in `factory-api`.
- On a ready checkout, add a worktree for a new `feat/…` branch, then `GET` the checkout and see
  it with `head`. Remove it.

## Out of scope

- Push (task 06).
- The unsaved route, archive handling and `409 project_archived` (task 07).
- The app's worktree actions (task 10).
