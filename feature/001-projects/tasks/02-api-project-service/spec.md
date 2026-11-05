---
feature: 001-projects
task: 02-api-project-service
layer: api
status: approved
approved: 2026-11-04
branch: feat/001-projects/02-api-project-service
base: feat/001-projects/01-api-github-check
covers: [AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-19, AC-20, AC-21]
---

# 02 — Project entity and service

## Goal

The `project` package's domain and business rules: the `Project` entity and repository, and a
`ProjectService` that creates, updates, archives and restores projects with validation,
uniqueness and the GitHub check. No HTTP yet; task 03 adds the controller.

## Contract

The rules from task-breakdown.md this task implements (the HTTP shapes are task 03's).

**Project fields:**
- `id`: number;
- `name`;
- `repositoryUrl`;
- `description`: string or `null`;
- `status`: one of `planning`, `active`, `maintenance`, `retired`;
- `archived`;
- `repositoryVerified`: `false` when GitHub couldn't be asked;
- `createdAt` and `updatedAt`: UTC timestamps.

**Create** (`name`, `repositoryUrl`, optional `description`, optional `status` defaulting to
`planning`):
- The name is required, trimmed, and at most 100 characters.
- The URL is required, trimmed, and must match `https://github.com/<owner>/<repo>`.
- The description is at most 2,000 characters; blank is stored as `null`.
- Rejected with a field error for invalid input, and for a repository GitHub reports not found
  (`repositoryUrl: "Repository not found on GitHub."`).
- Rejected as a conflict, with a field error, for a duplicate name (ignoring case, archived
  included) or a duplicate repository URL (exact text).
- GitHub unreachable, rate-limited, slower than 5 s, or no token configured: not an error. The
  project saves with `repositoryVerified: false`.

**Update:** the same fields, with `status` required. GitHub is called only when the URL changed
or the project is unverified. Editing an archived project is rejected ("Archived projects can't
be edited. Restore it first."). An unknown id is "not found".

**Archive and restore:** idempotent, and they don't change `status`.

Field keys for errors: `name`, `repositoryUrl`, `description`, `status`.

## Files

- `api/src/main/kotlin/com/toptier/custom/project/domain/ProjectStatus.kt` — new: enum
  `PLANNING, ACTIVE, MAINTENANCE, RETIRED`, with `json` names (`planning`, …) and `fromJson`.
- `api/src/main/kotlin/com/toptier/custom/project/domain/Project.kt` — new: JPA entity.
- `api/src/main/kotlin/com/toptier/custom/project/domain/ProjectRepository.kt` — new: Spring
  Data repository.
- `api/src/main/kotlin/com/toptier/custom/project/service/ProjectService.kt` — new: the use cases,
  plus `ProjectInput` (the validated command).
- `api/src/main/kotlin/com/toptier/custom/project/service/GitHubRepositoryUrl.kt` — new: parses
  `https://github.com/<owner>/<repo>`.
- `api/src/main/kotlin/com/toptier/custom/project/exception/ProjectExceptions.kt` — new: sealed
  feature exceptions.
- `api/src/test/kotlin/com/toptier/custom/project/ProjectServiceTests.kt` — new.
- `api/src/test/kotlin/com/toptier/custom/project/ProjectRepositoryTests.kt` — new:
  `@DataJpaTest`.

## Steps

1. **`ProjectStatus`** with lifecycle order (declaration order) and `val json: String`. Add
   `companion fun fromJson(value: String): ProjectStatus?`.
2. **`Project`** entity, table `project`:
   - `id: Long?` (`@GeneratedValue(IDENTITY)`);
   - `name: String` (length 100, not null);
   - `nameKey: String`: lowercased name, `unique = true`, used for case-insensitive uniqueness;
   - `repositoryUrl: String` (length 300, `unique = true`);
   - `description: String?` (length 2000);
   - `status: ProjectStatus` (`@Enumerated(STRING)`, length 20);
   - `statusOrder: Int`: `status.ordinal`, set by the service whenever it sets `status`, so task
     04 can sort by lifecycle order;
   - `archived: Boolean = false`;
   - `repositoryVerified: Boolean`;
   - `createdAt: Instant`, `updatedAt: Instant`.

   Use `var` fields (the `allOpen` and `jpa` plugins are configured). Add KDoc. Set `nameKey` from
   `name` with `name.lowercase(Locale.ROOT)` in the service, not in a JPA callback.
3. **`ProjectRepository : JpaRepository<Project, Long>`**:
   - `existsByNameKey(nameKey)`, `existsByNameKeyAndIdNot(nameKey, id)`;
   - `existsByRepositoryUrl(url)`, `existsByRepositoryUrlAndIdNot(url, id)`.

   Task 04 adds queries for the list.
4. **`GitHubRepositoryUrl.parse(url: String): GitHubRepositoryUrl?`** returns `owner` and `repo`
   for exactly `https://github.com/<owner>/<repo>`:
   - owner `[A-Za-z0-9-]{1,39}`;
   - repo `[A-Za-z0-9._-]{1,100}`;
   - an optional `.git` suffix and an optional trailing `/` are allowed, and the repo name sent to
     GitHub drops `.git`;
   - no query, fragment or extra path segments.

   The stored URL is the trimmed input, unchanged. Uniqueness is exact text, per the contract.
5. **`ProjectExceptions.kt`**, `sealed class ProjectException(message) : RuntimeException`:
   - `ProjectValidationException(val errors: Map<String, String>)`, mapped to 400 in task 03;
   - `ProjectConflictException(val errors: Map<String, String>)`, mapped to 409 with field errors;
   - `ProjectArchivedException(id)` with the message "Archived projects can't be edited. Restore
     it first.", mapped to 409 without field errors;
   - `ProjectNotFoundException(id)`, mapped to 404.
6. **`ProjectInput`** (`name: String?`, `repositoryUrl: String?`, `description: String?`,
   `status: String?`) and a private `validate(input, requireStatus: Boolean)` that collects every
   field error before throwing `ProjectValidationException`:
   - blank name → "Enter a name.";
   - name over 100 → "Use 100 characters or fewer.";
   - blank URL → "Enter the repository URL.";
   - URL not parseable → "Use a GitHub URL like https://github.com/owner/repo.";
   - description over 2000 → "Use 2,000 characters or fewer.";
   - unknown status, or missing on update → "Choose a status.".

   Trim name and URL; blank description becomes `null`.
7. **`@Service @Transactional class ProjectService(repository, checker: GitHubRepositoryChecker,
   clock: Clock)`**:
   - `get(id)`: `findById` or throw `ProjectNotFoundException`.
   - `create(input)`:
     1. validate;
     2. check uniqueness: duplicate `nameKey` → conflict
        `{name: "A project with this name already exists."}`; duplicate URL → conflict
        `{repositoryUrl: "Another project already uses this repository."}` (both, if both);
     3. call `checker.check(owner, repo)`: `NOT_FOUND` → validation error
        `{repositoryUrl: "Repository not found on GitHub."}`; otherwise set
        `repositoryVerified = (result == FOUND)`;
     4. set `createdAt = updatedAt = clock.instant()`, status default `PLANNING`, then save.
   - `update(id, input)`: load (404), and if archived throw `ProjectArchivedException`. Validate
     with status required, check uniqueness excluding `id`, then:
     - call GitHub only when `url != existing.repositoryUrl || !existing.repositoryVerified`, and
       apply the same `NOT_FOUND` / verified rules as create;
     - otherwise keep `repositoryVerified`.

     Update the fields and set `updatedAt = clock.instant()`.
   - `archive(id)` and `restore(id)`: load (404). If the flag already has the value, return
     unchanged without touching `updatedAt`. Otherwise flip it and set `updatedAt`. Never change
     `status`.
   - Run the uniqueness checks before the GitHub call, so duplicates don't spend a request. Also
     catch `DataIntegrityViolationException` on save and rethrow it as `ProjectConflictException`
     for the name (a race between two saves).
8. KDoc on every class and public function, tabs for indentation.

## Tests

**`ProjectServiceTests`**: `@DataJpaTest` with `@Import(ProjectService::class)`, so the
repository and constraints are real (H2). Add a `@MockitoBean` `GitHubRepositoryChecker`, stubbed
with BDDMockito `given`, and a `MutableClock` bean from a `@TestConfiguration`.
- **Create:** saves with status `planning` by default, `repositoryVerified = true` when `FOUND`,
  and timestamps from the clock (AC-4).
- **Validation:** blank name, 101-character name, blank URL, `https://gitlab.com/a/b`,
  `https://github.com/a`, `https://github.com/a/b/tree/main`, and a 2,001-character description
  each give the right field error. Several errors at once are all reported (AC-5).
- **Duplicate name:** "Factory" vs "factory" is a conflict on `name`, including when the first
  project is archived (AC-6).
- **Duplicate URL:** the exact URL is a conflict on `repositoryUrl`;
  `https://github.com/acme/App.git` vs `https://github.com/acme/app` is accepted (AC-7).
- **Not found:** `NOT_FOUND` gives a validation error on `repositoryUrl` and saves nothing (AC-8).
- **Unavailable:** `UNAVAILABLE` saves with `repositoryVerified = false` (AC-9).
- **Re-check:** updating an unverified project calls the checker even with an unchanged URL;
  `FOUND` sets verified, and `NOT_FOUND` rejects the update (AC-10).
- **Update:** changes fields and `updatedAt`. A verified project with only a name change doesn't
  call the checker (`then(checker).shouldHaveNoInteractions()`). A changed URL is checked (AC-11).
- **Archive:** sets `archived`, keeps `status`, and is idempotent (AC-19).
- **Archived edit:** updating an archived project throws `ProjectArchivedException` (AC-20).
- **Restore:** clears `archived` and keeps `status` (AC-21).
- **Unknown id:** `get`, `update`, `archive` and `restore` on an unknown id throw
  `ProjectNotFoundException`.

**`ProjectRepositoryTests`** (`@DataJpaTest`):
- The unique constraints on `nameKey` and `repositoryUrl` reject duplicates at the database
  level.

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`.
- The app starts against MySQL and Hibernate creates the `project` table (`ddl-auto=update`):
  `docker-compose up -d --no-deps mysql`, then `./gradlew bootRun` in `skeleton-api`, and check
  that the log shows no schema errors. Stop what you started.

## Out of scope

- Controller, DTOs, JSON and HTTP status mapping (task 03).
- List, search, sort, paging and summary (task 04).
- Deleting projects (never).
