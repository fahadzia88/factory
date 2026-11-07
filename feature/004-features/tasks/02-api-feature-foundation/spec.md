---
feature: 004-features
task: 02-api-feature-foundation
layer: api
status: approved
approved: 2026-11-06
branch: feat/004-features/02-api-feature-foundation
base: feat/004-features/01-infra-workflow-file
covers: [AC-1, AC-2, AC-7, AC-8]
---

# 02 — `feature` package foundation

## Goal

Lay the groundwork the later api tasks build on: the workflow file's model, reader and validator;
the name, slug and number rules; the `Feature`, `FeatureStep` and `FeatureMessage` entities and
their repositories; and `FeatureProperties`. No routes yet. AC-1, AC-2, AC-7 and AC-8 are only
partly covered here (the rules); task 04 applies them on create.

## Contract

Provides, copied from task-breakdown.md:

### Workflow file (project repo ↔ api ↔ agent)

`feature/workflow.json` in the project's repository, read from the main worktree
(`/repos/<project-id>/feature/workflow.json`) on create:

```json
{
  "featureStates": ["requirements", "breakdown", "specs", "implementation", "done", "abandoned"],
  "steps": [
    { "key": "requirements", "title": "Requirements", "skill": "feature-requirements", "states": ["draft", "approved"] },
    { "key": "breakdown", "title": "Task breakdown", "skill": "feature-breakdown", "states": ["draft", "approved"] },
    { "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] }
  ]
}
```

- Valid: parses as JSON; `featureStates` and `steps` non-empty; every state matches
  `^[a-z][a-z0-9-]{0,39}$`, no duplicates within a list; every step has a unique `key` (same
  pattern), a non-blank `title` (≤ 100), a `skill` (same pattern) and non-empty `states`; every
  `.claude/skills/<skill>/SKILL.md` exists in the main worktree. Unknown properties are ignored.
- Also required on create: `feature/_templates/requirements.md`.
- The validated workflow is stored on the feature as JSON and is what statuses are checked
  against. The API returns it as `workflow` in the Feature JSON.

### Numbering, slug, branch, folder

- `number` = 1 + max(the project's feature numbers, every `NNN` of a `feature/<NNN>-*` folder in
  `git ls-tree -d --name-only origin/<default> feature/` in the clone, after a fetch). Shown as
  three digits (`%03d`).
- `slug`: name lowercased, runs of `[^a-z0-9]` → `-`, trimmed of `-`, cut to 40 characters, then
  trimmed of `-` again. Empty → `400 invalid_name`.
- `code` = `<NNN>-<slug>`; `branch` = `feat/<code>/00-docs`; `folder` = `feature/<code>`.
- One create at a time per project (the checkout's lock from 003 covers the fetch, number and
  worktree add).

### Database (api only)

- `feature`: `id`, `project_id`, `number` (unique with `project_id`), `name` (≤ 100), `slug`
  (≤ 40), `status` (≤ 40), `workflow` (text, JSON), `branch`, `folder`, `agent_session_id`
  (null until the first chat turn), `created_at`, `updated_at`.
- `feature_step`: `id`, `feature_id`, `position` (1, 2, … in the order added), `type_key`,
  `title` (≤ 200), `document_path` (≤ 300, null allowed), `status`, `created_at`, `updated_at`.
- `feature_message`: `id`, `feature_id`, `role` (`USER`, `ASSISTANT`), `text` (text),
  `created_at`.
- The worktree is found by `branch` in the project's checkout; there is no foreign key, so
  removing the worktree (003) or archiving the project leaves the feature.

### api config

| Property | Env | Default | For |
| -------- | --- | ------- | --- |
| `app.feature.commit-name` | `FEATURE_COMMIT_NAME` | `factory` | Author and committer of the API's create commit. |
| `app.feature.commit-email` | `FEATURE_COMMIT_EMAIL` | `factory@users.noreply.github.com` | Same. |
| `app.feature.chat-idle-timeout` | `FEATURE_CHAT_IDLE_TIMEOUT` | `5m` | Longest silence on the agent's stream (a long tool call) before the turn fails. |

## Files

Main code is under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `config/FeatureProperties.kt` — new: `@ConfigurationProperties(prefix = "app.feature")`.
- `service/Workflow.kt` — new: `Workflow`, `WorkflowStep` and the JSON codec.
- `service/WorkflowReader.kt` — new: reads and validates the workflow file and the template from a
  worktree folder.
- `service/FeatureNaming.kt` — new: name, slug, number, code, branch and folder rules.
- `domain/Feature.kt`, `domain/FeatureStep.kt`, `domain/FeatureMessage.kt`,
  `domain/MessageRole.kt` — new: entities and the role enum.
- `domain/FeatureRepository.kt`, `domain/FeatureStepRepository.kt`,
  `domain/FeatureMessageRepository.kt` — new.
- `exception/FeatureExceptions.kt` — new: `sealed class FeatureException(message)`,
  `InvalidFeatureNameException`, `WorkflowInvalidException`.
- `api/src/main/resources/application.properties` — change (**shared** base file: additive, the
  user confirms the edit): the three `app.feature.*` lines.

Tests are under `api/src/test/kotlin/com/toptier/custom/feature/`: `WorkflowReaderTests.kt`,
`FeatureNamingTests.kt`, `FeatureRepositoryTests.kt`, `FeaturePropertiesTests.kt` (new).

## Steps

1. **`FeatureProperties`** (picked up by `@ConfigurationPropertiesScan`), modelled on
   `checkout/config/GitProperties.kt`:
   - `commitName: String = "factory"`, `commitEmail: String = "factory@users.noreply.github.com"`,
     `chatIdleTimeout: Duration = Duration.ofMinutes(5)`, each with a KDoc line.
   - `init`: `commitName` and `commitEmail` not blank, `commitEmail` contains `@`,
     `chatIdleTimeout` positive; messages like `"app.feature.commit-name must be set"`.
2. **`application.properties`**, after the `app.git.*` block:

   ```properties
   # Author and committer of the commit the API makes when it creates a feature (feature package)
   app.feature.commit-name=${FEATURE_COMMIT_NAME:factory}
   app.feature.commit-email=${FEATURE_COMMIT_EMAIL:factory@users.noreply.github.com}
   # Longest silence on the agent's chat stream (a long tool call) before a feature chat turn fails
   app.feature.chat-idle-timeout=${FEATURE_CHAT_IDLE_TIMEOUT:5m}
   ```

3. **`Workflow`**:
   - `data class WorkflowStep(val key: String, val title: String, val skill: String, val states: List<String>)`.
   - `data class Workflow(val featureStates: List<String>, val steps: List<WorkflowStep>)` with
     `initialState get() = featureStates.first()`, `step(key): WorkflowStep?`.
   - A `@Component class WorkflowCodec(private val jsonMapper: JsonMapper)` (`tools.jackson.databind.json.JsonMapper`,
     the bean `AgentService` already injects) with `write(workflow): String` and
     `read(json): Workflow` for the `feature.workflow` column. `read` uses the same parsing as
     the reader (step 4), so a stored workflow round-trips exactly.
4. **`WorkflowReader`** (`@Component`, injects `JsonMapper`):
   - `const val WORKFLOW_FILE = "feature/workflow.json"`, `TEMPLATE_FILE = "feature/_templates/requirements.md"`.
   - `read(worktree: Path): Workflow`:
     1. Missing file → `WorkflowInvalidException("feature/workflow.json: the file is missing. Add it to the repository's default branch.")`.
     2. Parse with `jsonMapper.readTree(text)`; a `JacksonException` → `"feature/workflow.json: isn't valid JSON (line <n>)."`
        (line from the exception's location when present). A non-object root → `"feature/workflow.json: must be a JSON object."`.
     3. Walk the tree by hand (not data binding) so each problem names its path:
        - `featureStates`: an array of strings, non-empty → else
          `"featureStates must be a non-empty list of states."`;
        - each state matches `NAME = Regex("^[a-z][a-z0-9-]{0,39}$")` → else
          `"featureStates[<i>] \"<value>\" must be lowercase letters, digits and dashes, up to 40 characters."`;
        - duplicates → `"featureStates has \"<value>\" twice."`;
        - `steps`: a non-empty array of objects; for each `steps[<i>]`: `key`, `skill` match
          `NAME`; `title` a string, trimmed, 1–100; `states` as `featureStates` (path
          `steps[<i>].states`); duplicate keys → `"steps has the key \"<key>\" twice."`.
        - Unknown properties are ignored.
        Every message is prefixed `feature/workflow.json: `.
     4. For each step, `worktree.resolve(".claude/skills/<skill>/SKILL.md")` must be a regular file
        → else `"feature/workflow.json: steps[<i>].skill \"<skill>\" has no .claude/skills/<skill>/SKILL.md."`.
   - `requireTemplate(worktree: Path): String` returns the template text, or throws
     `WorkflowInvalidException("feature/_templates/requirements.md: the file is missing. Add it to the repository's default branch.")`.
   - Read files as UTF-8; refuse a workflow file over 64 KB with
     `"feature/workflow.json: is larger than 64 KB."`.
5. **`FeatureNaming`** (an `object`, pure functions):
   - `name(raw: String?): String`: trimmed; empty → `InvalidFeatureNameException("Enter a name.")`;
     over 100 → `"Keep the name to 100 characters or fewer."`; `slug(name)` empty →
     `"The name needs at least one letter or digit."`.
   - `slug(name)`: `lowercase(Locale.ROOT)`, `Regex("[^a-z0-9]+")` → `-`, `trim('-')`,
     `take(40)`, `trim('-')`.
   - `nextNumber(recordMax: Int?, folders: List<String>)`: folder entries are the names
     `git ls-tree` prints (`feature/006-x`, or just `006-x`); take the last path segment,
     match `^(\d{3,})-`, and parse; return `1 + max(recordMax ?: 0, folder max ?: 0)`.
   - `code(number, slug) = "%03d-%s".format(number, slug)`, `branch(code) = "feat/$code/00-docs"`,
     `folder(code) = "feature/$code"`.
6. **Entities** (plain ids, no JPA relations, as in `checkout/domain/`; `Instant` times set by the
   services from `Clock`):
   - `Feature` → table `feature`, unique constraint `uk_feature_project_number` on
     (`project_id`, `number`); columns per the contract: `name` 100, `slug` 40, `status` 40,
     `workflow` `@Lob`/`columnDefinition = "text"` (use `@Column(columnDefinition = "TEXT")` so
     H2 and MySQL both accept it), `branch` 255, `folder` 255, `agent_session_id` 64 nullable,
     `created_at`, `updated_at`.
   - `FeatureStep` → table `feature_step`, unique (`feature_id`, `position`); `type_key` 40,
     `title` 200, `document_path` 300 nullable, `status` 40, times.
   - `FeatureMessage` → table `feature_message`; `role` `@Enumerated(EnumType.STRING)` length 16,
     `text` TEXT, `created_at`.
   - `enum class MessageRole { USER, ASSISTANT }`.
7. **Repositories:**
   - `FeatureRepository`: `findByProjectIdOrderByNumberDesc(projectId)`,
     `findByIdAndProjectId(id, projectId): Feature?`,
     `@Query("select max(f.number) from Feature f where f.projectId = :projectId") fun maxNumber(projectId: Long): Int?`.
   - `FeatureStepRepository`: `findByFeatureIdOrderByPositionAsc(featureId)`,
     `findByIdAndFeatureId(id, featureId): FeatureStep?`,
     `@Query("select max(s.position) …") fun maxPosition(featureId: Long): Int?`.
   - `FeatureMessageRepository`: `findByFeatureIdOrderByCreatedAtAscIdAsc(featureId)`.
8. **Exceptions** (`FeatureExceptions.kt`), messages are the `detail`:
   - `InvalidFeatureNameException(message)`, `WorkflowInvalidException(message)`.
   - Later tasks add theirs to the same sealed class.

## Tests

- `WorkflowReaderTests` (plain JUnit, `@TempDir`, a `JsonMapper` built with `JsonMapper.builder().build()`):
  - the contract's example plus its three `SKILL.md` files reads into the expected `Workflow`,
    `initialState == "requirements"` (AC-1);
  - each problem is refused with its message (AC-2): no file; bad JSON; a root array; empty
    `featureStates`; empty `steps`; a state `Draft`; a duplicate state; duplicate step keys; a
    blank title; a 101-character title; a step with empty `states`; a skill without `SKILL.md`;
  - an unknown property (`"version": 2`) is ignored;
  - `requireTemplate` returns the text, and throws when the file is missing (AC-2);
  - `WorkflowCodec.write` then `read` gives back the same workflow (AC-1).
- `FeatureNamingTests`:
  - `"Feature records"` → `feature-records`; `"  Ünïcode & spaces!! "` → `n-code-spaces`;
    a 60-character name → a 40-character slug with no trailing `-` (AC-8);
  - `""`, `"   "`, 101 characters and `"!!!"` are refused with their messages (AC-8);
  - `nextNumber(4, listOf("feature/006-x", "feature/_templates", "feature/rules.md"))` → 7;
    `nextNumber(null, emptyList())` → 1 (AC-7);
  - `code(7, "x") == "007-x"`, `branch` and `folder` as in the contract.
- `FeatureRepositoryTests` (`@DataJpaTest`): saving two features with the same project and number
  fails with `DataIntegrityViolationException`; `findByProjectIdOrderByNumberDesc`, `maxNumber`,
  `findByIdAndProjectId` (other project → null), steps in position order and `maxPosition`,
  messages oldest first; a 10 000-character `workflow` and message `text` save and load.
- `FeaturePropertiesTests`: defaults; blank `commitName`, an email without `@` and a zero
  timeout throw `IllegalArgumentException`.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q` (stop a running `bootRun`
  first), or the throwaway `gradle:jdk25` container from `api/CLAUDE.md`.
- The app starts against MySQL (`./gradlew bootRun` in `factory-api`) and Hibernate creates the
  three tables.

## Out of scope

- Routes, the exception handler and Feature JSON (03).
- Create, git, locking (04). Status and step validation against the workflow (05). The chat (06).
- A test that validates the factory's own `feature/workflow.json`: the `api` container mounts only
  `./api`, so the file isn't visible to the tests; task 01 verifies AC-3.
