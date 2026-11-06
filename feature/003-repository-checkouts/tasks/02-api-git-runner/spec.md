---
feature: 003-repository-checkouts
task: 02-api-git-runner
layer: api
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/02-api-git-runner
base: feat/003-repository-checkouts/01-infra-repos-volume
covers: [AC-27, AC-28]
---

# 02 — Git runner, paths and checkout tables

## Goal

The foundation of a new `checkout` package: typed `app.git.*` properties, a `GitRunner` that runs
CLI git with the token as a per-command header, timeouts and token scrubbing, a path builder from
project id and branch, and the `repository_checkout` and `repository_worktree` entities with their
repositories. No HTTP routes yet.

## Contract

Provides, copied from task-breakdown.md:

### Volume (infra ↔ api ↔ agent)

- Docker volume `repos`, mounted at **`/repos` in both `api` and `agent`**. The path must be the
  same in both: a worktree's `.git` file and the clone's `.git/worktrees/*/gitdir` hold absolute
  paths.
- `/repos/<project-id>/`: a normal (non-bare) clone whose working tree is the main worktree, on
  the default branch. `origin` is the project's `repositoryUrl` with no credentials in it.
- `/repos/<project-id>.worktrees/<branch with "/" as "__">/`: each additional worktree
  (`git worktree add`).
- Only the API runs git commands that touch the network or the set of worktrees. The agent runs
  local git (status, add, commit, rebase, diff, log, show, grep) inside feature-branch
  worktrees, and read-only commands in the clone.

### api config

| Property | Env | Default | For |
| -------- | --- | ------- | --- |
| `app.git.token` | `GIT_TOKEN` | blank | Contents read+write token. Sent as `http.https://github.com/.extraheader` through `GIT_CONFIG_*` env per command; never on disk. |
| `app.git.root` | `REPOS_ROOT` | `/repos` | The shared volume. |
| `app.git.network-timeout` | `GIT_NETWORK_TIMEOUT` | `2m` | fetch and push. |
| `app.git.clone-timeout` | `GIT_CLONE_TIMEOUT` | `10m` | clone. |
| `app.git.fetch-interval` | `GIT_FETCH_INTERVAL` | `15m` | The scheduled fetch (runs only when `app.scheduling.enabled`). |

Compose maps `GIT_TOKEN: ${API_GIT_TOKEN:-}`.

### Database (api only)

- `repository_checkout`: `id`, `project_id` (unique), `path`, `default_branch` (null until
  cloned), `state` (`CLONING`, `READY`, `FAILED`), `error` (≤ 500, null unless failed),
  `created_at`, `last_fetched_at`.
- `repository_worktree`: `id`, `checkout_id`, `branch` (unique per checkout), `path`, `main`
  (bool), `base_branch` (null for tracked and main), `created_at`.
- On startup, `CLONING` rows are set to `FAILED` with "The clone was interrupted by a restart."

This task adds the tables and the reset query; task 03 runs it on startup.

### Git failures (from Routes)

- Git failures map as: auth rejected or other remote error → `502 git_remote_error` with a plain
  message (no stderr containing URLs or headers); timeout → `504 git_timeout`. Any text taken
  from git output is scrubbed of the token.

This task provides the typed failures; tasks 03–06 map them to those codes.

## Files

All main code under `api/src/main/kotlin/com/toptier/custom/checkout/`:

- `config/GitProperties.kt` — new: `@ConfigurationProperties(prefix = "app.git") data class
  GitProperties(token, root, networkTimeout, cloneTimeout, fetchInterval)`.
- `service/GitRunner.kt` — new: `@Component class GitRunner(props: GitProperties)`, plus
  `data class GitResult(exitCode, stdout, stderr)`.
- `service/CheckoutPaths.kt` — new: `@Component class CheckoutPaths(props: GitProperties)`.
- `exception/GitExceptions.kt` — new: sealed `GitException` with `GitNotConfiguredException`,
  `GitTimeoutException(timeout)`, `GitCommandException(command, exitCode, stderr)`, and
  `enum class GitFailure` with `GitFailure.of(stderr)`.
- `domain/CheckoutState.kt` — new: `enum class CheckoutState(val json: String) { CLONING("cloning"),
  READY("ready"), FAILED("failed") }`.
- `domain/RepositoryCheckout.kt` — new: entity for `repository_checkout`.
- `domain/RepositoryWorktree.kt` — new: entity for `repository_worktree`.
- `domain/RepositoryCheckoutRepository.kt` — new.
- `domain/RepositoryWorktreeRepository.kt` — new.
- `api/src/main/resources/application.properties` — change (shared file; additive, the user
  confirms the edit): the five `app.git.*` properties with comments.
- Tests under `api/src/test/kotlin/com/toptier/custom/checkout/`:
  - `GitFixtures.kt` — new: test helper for local bare remotes (the locked `support/` package
    can't take it).
  - `GitRunnerTests.kt`, `CheckoutPathsTests.kt`, `CheckoutRepositoryTests.kt` — new.

## Steps

1. **Properties.** `GitProperties`, KDoc on each field (wording from the contract table):
   - `token: String = ""`, `root: String = "/repos"`,
     `networkTimeout: Duration = Duration.ofMinutes(2)`,
     `cloneTimeout: Duration = Duration.ofMinutes(10)`,
     `fetchInterval: Duration = Duration.ofMinutes(15)`.
   - `init`: `root` is not blank and is absolute (`Path.of(root).isAbsolute`); the three durations
     are positive. Don't check that `root` exists: tests and the throwaway Gradle container have
     no `/repos`. Folders are created when a clone starts (task 03).
   - `application.properties`, after the `app.github.*` block:
     ```properties
     # Git token for project checkouts (Contents: read and write); sent per git command as an HTTP header, never stored. Blank refuses clone, fetch and push
     app.git.token=${GIT_TOKEN:}
     # Shared volume with the project checkouts; the agent mounts it at the same path
     app.git.root=${REPOS_ROOT:/repos}
     # Longest wait for a fetch, push or any other git command
     app.git.network-timeout=${GIT_NETWORK_TIMEOUT:2m}
     # Longest wait for a clone
     app.git.clone-timeout=${GIT_CLONE_TIMEOUT:10m}
     # How often ready checkouts are fetched (only when app.scheduling.enabled)
     app.git.fetch-interval=${GIT_FETCH_INTERVAL:15m}
     ```
2. **Exceptions** (`GitExceptions.kt`). Messages are safe to log and never contain the token.
   - `sealed class GitException(message: String) : RuntimeException(message)`.
   - `GitNotConfiguredException` — "The API has no git token configured. Set API_GIT_TOKEN in
     the root .env."
   - `GitTimeoutException(val timeout: Duration)` — "git didn't finish within <timeout>."
     (format minutes as `2 minutes`, seconds otherwise; a small private `describe(Duration)`).
   - `GitCommandException(val command: String, val exitCode: Int, val stderr: String)` —
     `command` is the git subcommand only (`clone`, `fetch`, `push`, …), never the full argument
     list. Message: "git <command> failed (exit <code>)". `val failure: GitFailure get() =
     GitFailure.of(stderr)`.
   - `enum class GitFailure { NOT_FOUND, AUTH, REJECTED, STALE_LEASE, UNREACHABLE, OTHER }` and
     `companion fun of(stderr: String): GitFailure`, checked in this order, case-insensitive
     (the runner forces `LC_ALL=C`, so git's English messages are stable):
     - `STALE_LEASE`: `(stale info)`;
     - `REJECTED`: a `! [rejected]` line, `non-fast-forward` or `(fetch first)`;
     - `NOT_FOUND`: `Repository not found`, `does not appear to be a git repository`, or
       `fatal: repository '…' not found`;
     - `AUTH`: `Authentication failed`, `could not read Username`, `terminal prompts disabled`,
       `returned error: 401`, `returned error: 403`;
     - `UNREACHABLE`: `Could not resolve host`, `Failed to connect`, `Connection refused`,
       `Connection timed out`, `Operation timed out`, `unable to access`;
     - else `OTHER`. (`[remote rejected]`, e.g. branch protection, is `OTHER`: it isn't a
       non-fast-forward.)
3. **GitRunner** — port of `agent/agent/lib/repo.ts`'s `gitEnv`/`git()`/`refExists` to
   `ProcessBuilder`:
   - `fun run(dir: Path?, args: List<String>, network: Boolean = false, timeout: Duration =
     props.networkTimeout): GitResult` — never throws on a non-zero exit.
     - If `network` and `props.token` is blank, throw `GitNotConfiguredException` without
       starting a process.
     - Command `listOf("git") + args`, `directory(dir?.toFile())`.
     - Environment: `pb.environment().clear()`, then only:
       - `PATH` = `System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"`,
       - `HOME` = `System.getenv("HOME") ?: "/root"`,
       - `LC_ALL=C`, `GIT_TERMINAL_PROMPT=0`,
       - when `network`: `GIT_CONFIG_COUNT=1`,
         `GIT_CONFIG_KEY_0=http.https://github.com/.extraheader`,
         `GIT_CONFIG_VALUE_0=Authorization: Basic <base64("x-access-token:" + token)>`.
       The header is scoped to `https://github.com/`, so `file://` remotes in tests ignore it.
       Nothing token-related is passed as an argument or written to config.
     - Close `process.outputStream` at once (git never waits on stdin).
     - Read stdout and stderr on two virtual threads (`Thread.ofVirtual().start { … }`) into
       buffers capped at 1 MiB each (keep draining past the cap so git never blocks on a full
       pipe). Decode as UTF-8.
     - `if (!process.waitFor(timeout.toMillis(), MILLISECONDS))`:
       `process.descendants().forEach { it.destroyForcibly() }` (git-remote-https, `sh` from
       aliases), then `process.destroyForcibly()`, `process.waitFor(5, SECONDS)`, join the
       readers for at most 2 s, and throw `GitTimeoutException(timeout)`.
     - Join the readers (at most 2 s each), and return `GitResult(exitCode, scrub(stdout),
       scrub(stderr))`.
     - Log at debug: `git <args.first()> in <dir> exited <code> in <ms> ms`. Never log the
       environment or the full argument list.
   - `fun runOrThrow(dir, args, network = false, timeout = props.networkTimeout): String` —
     `run`, and on a non-zero exit throw `GitCommandException(args.first(), exitCode,
     stderr.take(4000))`; returns stdout.
   - `fun refExists(dir: Path, ref: String): Boolean` — `rev-parse --verify --quiet
     <ref>^{commit}`; true when the exit code is 0 and stdout isn't blank.
   - `fun isValidBranchName(name: String): Boolean` — false for blank names or names starting
     with `-` or `@{`; otherwise `check-ref-format --branch <name>` (with `dir = null`) exits 0
     and its trimmed stdout equals `name` (so `@{-1}`-style expansions don't pass).
   - `fun requireToken()` — throws `GitNotConfiguredException` when the token is blank, for
     services that must refuse before writing anything.
   - `private fun scrub(text: String): String` — when the token is set, replace the token, the
     base64 header value and `x-access-token:<token>` with `***`. Applied to everything the
     runner returns or puts in an exception.
4. **CheckoutPaths.**
   - `root: Path = Path.of(props.root)`.
   - `checkoutDir(projectId: Long): Path` = `root.resolve(projectId.toString())`.
   - `worktreesDir(projectId)` = `root.resolve("$projectId.worktrees")`.
   - `worktreeDir(projectId, branch)` = `worktreesDir(projectId).resolve(folderName(branch))`
     where `folderName(branch) = branch.replace("/", "__")`.
     `require(folder.isNotEmpty() && folder != "." && folder != "..")` and require the
     normalized result's parent to equal `worktreesDir(projectId)`. Callers validate branch
     names first (task 05); this is the last guard against a path outside the volume.
5. **Entities** (`kotlin-jpa`/`allOpen` already set up; follow `Project.kt`):
   - `RepositoryCheckout`, `@Table(name = "repository_checkout")`:
     - `id: Long?` (identity);
     - `projectId: Long` — `@Column(name = "project_id", nullable = false, unique = true)`. A
       plain id column with no JPA relation to `Project`: the checkout package reads projects
       only through `ProjectService`;
     - `path: String` (length 300, not null);
     - `defaultBranch: String?` (`default_branch`, length 255);
     - `state: CheckoutState` (`@Enumerated(STRING)`, length 10, not null);
     - `error: String?` (length 500);
     - `createdAt: Instant` (`created_at`, not null), `lastFetchedAt: Instant?` (`last_fetched_at`).
   - `RepositoryWorktree`, `@Table(name = "repository_worktree", uniqueConstraints =
     [UniqueConstraint(name = "uk_worktree_checkout_branch", columnNames = ["checkout_id",
     "branch"])])`:
     - `id: Long?`; `checkoutId: Long` (`checkout_id`, not null, plain id);
     - `branch: String` (255), `path: String` (600), `main: Boolean` (`@Column(name = "main",
       nullable = false)`; not reserved in MySQL 8.4 or H2);
     - `baseBranch: String?` (`base_branch`, 255); `createdAt: Instant`.
6. **Repositories.**
   - `RepositoryCheckoutRepository : JpaRepository<RepositoryCheckout, Long>`:
     - `findByProjectId(projectId: Long): RepositoryCheckout?`;
     - `findAllByState(state: CheckoutState): List<RepositoryCheckout>`;
     - `@Transactional @Modifying(clearAutomatically = true) @Query("update RepositoryCheckout c set c.state = :to, c.error =
       :error where c.state = :from") fun updateState(from: CheckoutState, to: CheckoutState,
       error: String): Int` — used by task 03's startup reset (`CLONING` → `FAILED`).
   - `RepositoryWorktreeRepository : JpaRepository<RepositoryWorktree, Long>`:
     - `findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc(checkoutId: Long):
       List<RepositoryWorktree>` (main first, then by creation);
     - `findByIdAndCheckoutId(id: Long, checkoutId: Long): RepositoryWorktree?`;
     - `existsByCheckoutIdAndBranch(checkoutId: Long, branch: String): Boolean`;
     - `@Transactional @Modifying fun deleteByCheckoutId(checkoutId: Long)` (via a JPQL
       `delete from RepositoryWorktree w where w.checkoutId = :checkoutId`).
7. KDoc on every class and public function; tabs.

## Tests

`GitFixtures` (test helper, not a test class):

- `fun git(dir: Path, vararg args: String): String` — runs git directly with `ProcessBuilder`
  (not `GitRunner`), `-c user.name=Test -c user.email=test@example.com`, fails the test on a
  non-zero exit, returns trimmed stdout.
- `fun bareRemote(parent: Path, name: String = "remote.git", branch: String = "main"): Path` —
  `git init --bare --initial-branch=<branch>`, then seeds it from a scratch clone with one commit
  (`README.md`) pushed to `<branch>`.
- `fun commit(dir: Path, file: String, content: String, message: String): String` — writes,
  `add`, `commit`, returns the short SHA.
- `fun fileUrl(path: Path) = "file://${path.toAbsolutePath()}"`.

Remotes are always local bare repos in a `@TempDir`, reached through `file://` URLs; nothing
calls GitHub.

`GitRunnerTests` (plain JUnit; `GitRunner(GitProperties(token = TOKEN, root = tempDir))` with
`TOKEN = "ghp_testTOKEN123"`):

- `runOrThrow` returns stdout: `rev-parse --abbrev-ref HEAD` in a clone → `main`.
- A non-zero exit: `run` returns the code; `runOrThrow` throws `GitCommandException` with
  `command`, `exitCode` and stderr.
- The auth header is set by env for network commands only (AC-27):
  - `run(null, listOf("config", "--get", "http.https://github.com/.extraheader"), network =
    true)` prints `Authorization: Basic ***`; the output contains neither the token nor its
    base64;
  - the same with `network = false` exits 1 with empty stdout.
- Token text in git output is scrubbed (AC-27): `runOrThrow(null, listOf("ls-remote",
  "file://$tmp/$TOKEN/missing.git"), network = true)` throws; neither `stderr` nor `message`
  contains the token, and `stderr` contains `***`.
- A blank token with `network = true` → `GitNotConfiguredException`, no process started (AC-27).
- A clone from a `file://` remote leaves no token in `.git/config` (read the file), and
  `origin`'s URL equals the given URL (AC-27).
- Timeout (AC-28): `run(dir, listOf("-c", "alias.wait=!sleep 30", "wait"), timeout =
  Duration.ofMillis(300))` throws `GitTimeoutException` and returns in under 5 s (the child
  `sleep` is killed, so the readers don't hang).
- `refExists`: true for `refs/heads/main` and `refs/remotes/origin/main` in a clone, false for
  `refs/heads/nope`.
- `isValidBranchName`: true for `main`, `feat/003-x/02-api-y`; false for `""`, `-x`, `a b`,
  `bad..name`, `@{-1}`, `x.lock`, `x/`.
- `GitFailure.of` on real-shaped stderr samples: GitHub's "remote: Repository not found." →
  `NOT_FOUND`; "fatal: Authentication failed for 'https://github.com/…'" and "could not read
  Username" → `AUTH`; "Could not resolve host: github.com" → `UNREACHABLE`;
  " ! [rejected]        x -> x (non-fast-forward)" → `REJECTED`;
  " ! [rejected]        x -> x (stale info)" → `STALE_LEASE`; anything else → `OTHER` (AC-28
  support).

`CheckoutPathsTests` (plain JUnit):

- `checkoutDir(12)` = `<root>/12`, `worktreesDir(12)` = `<root>/12.worktrees`.
- `worktreeDir(12, "feat/003-x/01-api-y")` = `<root>/12.worktrees/feat__003-x__01-api-y`.
- `..` and `.` as a branch → `IllegalArgumentException`.

`CheckoutRepositoryTests` (`@DataJpaTest` on H2):

- A second checkout with the same `projectId` → `DataIntegrityViolationException`.
- A second worktree with the same `(checkoutId, branch)` → `DataIntegrityViolationException`; the
  same branch in another checkout is fine.
- `updateState(CLONING, FAILED, msg)` changes only the `CLONING` rows and sets `error`.
- `findByCheckoutIdOrderByMainDescCreatedAtAscIdAsc` returns main first, then oldest first.
- `deleteByCheckoutId` removes only that checkout's worktrees.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q` (stop any running
  `bootRun` first), or the throwaway container from `api/CLAUDE.md`:
  `docker run --rm -v "$PWD":/workspace -w /workspace -v skeleton-gradle-cache:/home/gradle/.gradle gradle:jdk25 ./gradlew test --no-daemon -q`
  in `api/`. `git --version` in the container is 2.53.
- Config change: the app starts against MySQL (`./gradlew bootRun` in `factory-api`), and the two
  new tables appear (`show tables`).

## Out of scope

- Services, routes, the exception handler and error codes (task 03).
- Fetch (task 04), worktree add/remove (task 05) and push (task 06).
- The startup reset call (task 03) and the scheduled fetch (task 04); this task only adds the query.
