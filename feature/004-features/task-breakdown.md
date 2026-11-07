---
feature: 004-features
status: in-progress
approved: 2026-11-06
---

# Features: task breakdown

Requirements: [requirements.md](requirements.md)

## Contracts

The interfaces between layers, fixed before the tasks that depend on them. Every route needs
`Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as `ProblemDetail`
(`application/problem+json`) with a `code` property the app and agent switch on and a `detail`
they can show as is. On every route: `404 project_not_found`, `404 feature_not_found` (no such
feature, or it belongs to another project), `400 invalid_request` (non-numeric id, unreadable
body, missing required field).

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

### Feature (JSON)

```json
{
  "id": 21,
  "projectId": 12,
  "number": 4,
  "code": "004-features",
  "name": "Features",
  "slug": "features",
  "status": "requirements",
  "branch": "feat/004-features/00-docs",
  "folder": "feature/004-features",
  "worktree": { "id": 9, "path": "/repos/12.worktrees/feat__004-features__00-docs", "missing": false },
  "workflow": { "featureStates": ["…"], "steps": [{ "key": "…", "title": "…", "skill": "…", "states": ["…"] }] },
  "steps": [
    {
      "id": 5,
      "position": 1,
      "type": "requirements",
      "typeTitle": "Requirements",
      "title": "Requirements",
      "documentPath": "feature/004-features/requirements.md",
      "status": "draft",
      "states": ["draft", "approved"],
      "createdAt": "2026-11-06T10:00:00Z",
      "updatedAt": "2026-11-06T10:00:00Z"
    }
  ],
  "createdAt": "2026-11-06T10:00:00Z",
  "updatedAt": "2026-11-06T10:00:00Z"
}
```

- `worktree` is `null` when no worktree with `branch` exists; the app shows that as missing too.
  `missing` is 003's flag (folder not on disk).
- Feature summary (list rows): `id`, `number`, `code`, `name`, `status`, `updatedAt`.

### Routes

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET /api/projects/{id}/features` | — | `200` `[Feature summary]`, newest number first | — |
| `POST /api/projects/{id}/features` | `{ "name": "Features" }` | `201` Feature | `400 invalid_name`, `409 project_archived`, `409 checkout_not_ready`, `409 workflow_invalid`, `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout`, `500 git_error` |
| `GET /api/projects/{id}/features/{featureId}` | — | `200` Feature | — |
| `PUT /api/projects/{id}/features/{featureId}/status` | `{ "status": "breakdown" }` | `200` Feature | `409 invalid_status` (problem has `allowed`: the workflow's states) |
| `POST /api/projects/{id}/features/{featureId}/steps` | `{ "type": "spec", "title": "Spec: 01-api-x", "documentPath": "feature/004-features/tasks/01-api-x/spec.md" }` (`documentPath` optional) | `201` Step | `409 invalid_step_type` (`allowed`: step keys), `400 invalid_step` (blank or long title, path not under `folder`) |
| `PATCH /api/projects/{id}/features/{featureId}/steps/{stepId}` | any of `status`, `title`, `documentPath` | `200` Step | `404 step_not_found`, `409 invalid_status` (`allowed`: the type's states), `400 invalid_step` |
| `GET /api/projects/{id}/features/{featureId}/messages` | — | `200` `[{ "id", "role": "user"\|"assistant", "text", "createdAt" }]`, oldest first | — |
| `POST /api/projects/{id}/features/{featureId}/messages` | `{ "message": "Start the requirements" }` | `200` `application/x-ndjson`: the agent's events (below) | before the stream: `400 invalid_request` (blank), `409 chat_busy`, `502 agent_unavailable` |
| `POST /api/projects/{id}/features/{featureId}/messages/cancel` | — | `200` `{ "cancelled": true }` | — |

There is no rename, archive or delete route for features or steps. Status and step writes don't
need the project to be unarchived (the agent may record a finished state after archive).

### Feature chat (app ↔ api ↔ agent)

The API brokers the chat so no locked agent or app file changes:

- `POST …/messages`: the API stores the user's message, then posts to the agent's
  `POST /agent/v1/chat` (agent/README.md, "HTTP API") with `sessionId` = the feature's
  `agent_session_id` (omitted the first time) and the message prefixed with one context line:

  ```
  [Feature context: projectId=12 featureId=21 code=004-features. Use the feature_* tools with these ids.]

  Start the requirements
  ```

- It relays each NDJSON line to the app as it arrives: `session`, `text` (`delta`), `tool` (`id`,
  `name`, `status`), and exactly one of `done` (`text`), `error` (`message`), `cancelled`. On
  `session` it stores `agent_session_id`; on `done` it stores the assistant's `text`. An agent
  `404 session_not_found` starts a new session once and retries.
- Agent `409 session_busy` → `409 chat_busy`; agent unreachable or `5xx` → `502
  agent_unavailable`; silence longer than `chat-idle-timeout` → an `error` line, then the stream
  ends.
- `…/messages/cancel` calls the agent's `POST /agent/v1/session/<id>/cancel`.
- The API calls the agent with its own `RestClient` bean (`featureAgentRestClient`): the same base
  URL and bearer token as the locked `agentRestClient`, but the idle timeout above, since
  `agentRestClient` gives up after 30 s of silence.

### Agent tools (agent → api, agent → `/repos`)

New `mcp__app` tools in `agent/src/tools/`. Every tool takes `projectId` and `featureId` and first
loads the feature with `GET /api/projects/{id}/features/{featureId}`; paths come only from that
response.

| Tool | Input | Does |
| ---- | ----- | ---- |
| `feature_get` | ids | The Feature JSON (status, steps, workflow, branch, folder, worktree). |
| `feature_read_skill` | ids, `step` (a step key) | `<worktree>/.claude/skills/<skill>/SKILL.md` for that step's `skill`. Unknown key → error listing the keys. |
| `feature_list_files` | ids, `dir?` | Files under `dir` (default the root) in the worktree, `.git` excluded, at most 500. |
| `feature_read_file` | ids, `path` | A text file in the worktree, at most 200 KB. |
| `feature_write_file` | ids, `path`, `content` | Writes a file; `path` must be under `folder`. |
| `feature_edit_file` | ids, `path`, `oldText`, `newText` | Replaces one exact, unique occurrence; `path` under `folder`. |
| `feature_commit` | ids, `message` | `git add -- <folder>`, `git commit` (author from `GIT_USER_NAME`/`GIT_USER_EMAIL`, as 003), then `POST …/checkout/worktrees/{worktree.id}/push` (`force: false`). Returns the head SHA, or "nothing to commit". |
| `feature_set_status` | ids, `status` | `PUT …/status`. |
| `feature_add_step` | ids, `type`, `title`, `documentPath?` | `POST …/steps`. |
| `feature_update_step` | ids, `stepId`, `status?`, `title?`, `documentPath?` | `PATCH …/steps/{stepId}`. |

- Path rules (code, not prompt): relative paths only; the resolved real path (symlinks followed)
  must be inside the worktree for reads and inside `<worktree>/<folder>/` for writes; `.git`
  is never readable or writable. Violations fail with a plain message and touch nothing.
- `worktree` `null` or `missing` → every file and commit tool fails with "The worktree for
  <branch> is missing. Check it out again on the project page." (`feature_get` and the status
  and step tools still work).
- API errors surface as the problem's `detail`.
- `instructions.md` gains a "Feature workflow" section: the context line, running a step by
  reading its skill with `feature_read_skill` and following it, `rules.md` and templates read
  from the worktree, Claude Code wording mapped (AskUserQuestion → ask in the chat, a slash
  command → name the next step), approvals recorded only after an explicit yes in the chat,
  commit after each document change, and record steps and statuses with the tools.

### app → api

`lib/features.ts` with the types and calls through `lib/api.ts`. The chat reads the NDJSON
stream with `fetch` (same-origin `/api`, bearer token from `lib/password.ts`), since `api()`
reads whole bodies. Errors show the problem's `detail`; `5xx` codes get "Try again".

## Tasks

Stack order, bottom first. Each task is one PR based on the row above it.

| #  | Layer | Task | Depends on | ACs | Branch |
| -- | ----- | ---- | ---------- | --- | ------ |
| 00 | docs  | Feature folder | — | — | `feat/004-features/00-docs` |
| 01 | infra | The factory's `feature/workflow.json` (requirements, breakdown, spec steps; the feature states above). | 00 | AC-3 | `feat/004-features/01-infra-workflow-file` |
| 02 | api   | `feature` package foundation: workflow model, parser and validator (from a directory), name/slug/number rules, `Feature`, `FeatureStep`, `FeatureMessage` entities and repositories, `FeatureProperties`. Unit tests, `@DataJpaTest`. | 01 | AC-1, AC-2, AC-7, AC-8 (rules) | `feat/004-features/02-api-feature-foundation` |
| 03 | api   | Read routes: `GET` list and one, Feature JSON with steps and the worktree lookup (`null`/`missing`), exception handler with the shared codes. `@WebMvcTest` incl. `401`, `404` for another project's feature, no `DELETE`. | 02 | AC-21 (api), AC-24 (api), AC-25 (api), AC-26, AC-27 | `feat/004-features/03-api-feature-read` |
| 04 | api   | Create: `POST` with the create sequence (lock, fetch, workflow and template check, number, worktree, template fill, commit, push, save, rollback). Service tests against local bare repos (incl. push failure rollback and two concurrent creates), controller tests. | 03 | AC-1, AC-2, AC-4 (api), AC-5, AC-6, AC-7, AC-8, AC-9, AC-10 | `feat/004-features/04-api-feature-create` |
| 05 | api   | Status and steps: `PUT …/status`, `POST …/steps`, `PATCH …/steps/{id}` with workflow checks and `allowed` in the problem; `updatedAt` on the feature bumps with each. Tests. | 04 | AC-11, AC-12, AC-13, AC-14 | `feat/004-features/05-api-feature-steps` |
| 06 | api   | Chat broker: `featureAgentRestClient`, `GET`/`POST …/messages` (store, context prefix, relay NDJSON, session id, retry on `session_not_found`, error mapping, idle timeout), `…/messages/cancel`. Tests with `MockRestServiceServer` streams. | 05 | AC-22 (api), AC-23 (api) | `feat/004-features/06-api-feature-chat` |
| 07 | agent | Read tools: `src/feature-api.ts` (load a feature, path guards), `feature_get`, `feature_read_skill`, `feature_list_files`, `feature_read_file`; registered in `src/agent.ts`. `node:test` with stubbed `fetch` and a temp worktree. | 06 | AC-16, AC-18, AC-21 | `feat/004-features/07-agent-feature-read-tools` |
| 08 | agent | Write tools: `feature_write_file`, `feature_edit_file`, `feature_commit` (local git + API push), `feature_set_status`, `feature_add_step`, `feature_update_step`; the "Feature workflow" section of `src/instructions.md`. Tests incl. writes outside the folder, `..`, symlinks and `.git` refused. | 07 | AC-11–AC-14 (agent), AC-16, AC-17, AC-19, AC-20 | `feat/004-features/08-agent-feature-write-tools` |
| 09 | app   | `lib/features.ts`; feature page `/projects/:id/features/:featureId` (unlinked): header with name, number, status, branch, worktree path or "Missing", times; steps list in position order with type, title, status, updated; no status controls; "Feature not found"; error with Try again. | 08 | AC-5 (page), AC-11–AC-15 (display), AC-21, AC-25, AC-28, AC-29 | `feat/004-features/09-app-feature-page` |
| 10 | app   | Feature chat on that page: project-owned `components/features/feature-chat.tsx`, history from `GET …/messages`, send with streamed reply and tool status, Stop (cancel), `chat_busy` and agent errors in an `Alert`; refreshes the feature after each turn so new statuses and steps show. | 09 | AC-16, AC-20, AC-22, AC-23, AC-28, AC-29 | `feat/004-features/10-app-feature-chat` |
| 11 | app   | "Features" card on `/projects/:id` (list, empty state, "New feature" only when not archived and the checkout is ready) and the "New feature" form (`/projects/:id/features/new`; name field errors, `workflow_invalid` and git errors with retry; lands on the feature page). Makes the feature reachable. | 10 | AC-2 (app), AC-4, AC-5, AC-8, AC-9, AC-24, AC-28, AC-29 | `feat/004-features/11-app-features-card` |

Infra task 01 is done by the coordinator with the user's go-ahead. `src/agent.ts` and
`src/instructions.md` (07, 08) and `application.properties` (02) are `shared` base files: the
changes are additive, and the user confirms each edit. No `locked` file changes.

## AC coverage

| AC    | Tasks          | Verified by |
| ----- | -------------- | ----------- |
| AC-1  | 02, 04         | Parser tests; `FeatureServiceTests` new feature in `featureStates[0]`, stored workflow equals the file |
| AC-2  | 02, 04, 11     | Validator tests per problem (bad JSON, empty lists, duplicates, missing `SKILL.md`, missing template); create test: `409 workflow_invalid`, no worktree, branch or row; manual form message |
| AC-3  | 01, 04         | Manual: the file parses (01); once 01 and 04 are merged, creating a feature on the factory project succeeds, which runs the API's validator on it (the `api` container can't read the repo root, so no automated test) |
| AC-4  | 04, 11         | Controller tests `409 project_archived`, `409 checkout_not_ready`; manual card action hidden |
| AC-5  | 04, 09, 11     | Create test response; manual: form → feature page with number, name, state, branch, path |
| AC-6  | 04             | Service test against a local bare remote: file content filled, commit on the branch, remote branch at that commit; manual on the checkout card |
| AC-7  | 02, 04         | Numbering test with records up to 4 and a `feature/006-x/` folder → 7 |
| AC-8  | 02, 04, 11     | Name/slug tests; controller `400 invalid_name`; manual field error keeps input |
| AC-9  | 04, 11         | Service test with a rejecting remote: `502`, worktree, branch and row gone; manual retry |
| AC-10 | 04             | Concurrency test: two creates in threads → different numbers and branches |
| AC-11 | 05, 08, 09     | `FeatureStepServiceTests` allowed and refused states, `allowed` in the problem; agent tool test; manual page |
| AC-12 | 05, 08, 09     | Step add tests (first state, position order, unknown type `409`); manual |
| AC-13 | 05, 08, 09     | Step update tests; manual |
| AC-14 | 05, 09         | Two steps of one type listed |
| AC-15 | 09             | Manual: no status controls on the page |
| AC-16 | 07, 08, 10     | Tool tests read `SKILL.md` from the worktree; manual: chat runs the requirements step and edits the file |
| AC-17 | 08             | `feature_commit` test against a temp worktree with a stubbed push route; manual: the branch on GitHub has the change |
| AC-18 | 07             | Tool test: skill read from the feature's worktree, not the factory's; manual with a project whose skill differs |
| AC-19 | 08             | Path-guard tests: outside the folder, `..`, absolute, symlink out, `.git` → refused, nothing written |
| AC-20 | 08, 10         | Manual: approval recorded only after an explicit yes |
| AC-21 | 03, 07, 09     | Read test with the worktree removed → `worktree: null`; tool test message; manual "Missing" |
| AC-22 | 06, 10         | Broker test: the agent receives the context line; manual |
| AC-23 | 06, 10         | Broker test: messages stored, session id reused; manual reload shows history, another feature shows its own |
| AC-24 | 03, 11         | List test order; manual card and empty state |
| AC-25 | 03, 09         | Controller `404` for missing and other project's feature; manual "Feature not found" |
| AC-26 | 03, 09, 11     | Controller tests: `DELETE` and `PUT` on the feature → `405`; manual: no actions |
| AC-27 | 03, 05, 06     | `@WebMvcTest` `401` on every route |
| AC-28 | 09, 10, 11     | Manual with the API stopped: errors with Try again, the project page still works |
| AC-29 | 09, 10, 11     | Manual at 375 px |

## Rollout

- The API routes (02–06) and agent tools (07–08) are harmless when unused.
- The feature page (09) and chat (10) are on a route nothing links to until 11 adds the Features
  card and the form, so the feature is only reachable once 11 merges.
- Task 01 adds the factory's `feature/workflow.json`. To try 04–11 against the factory project
  before 01 is on `main`, run a fetch after merging 01, or use a test project whose default
  branch has the file.
- Release: nothing in `.env` is required; the three `app.feature.*` properties have defaults.
  The schema comes from the entities (`ddl-auto=update`).

## Progress

| #  | Spec | PR | Status |
| -- | ---- | -- | ------ |
| 00 | — | [#38](https://github.com/fahadzia88/factory/pull/38) | pr open |
| 01 | [spec](tasks/01-infra-workflow-file/spec.md) | [#39](https://github.com/fahadzia88/factory/pull/39) | pr open (coordinator) |
| 02 | [spec](tasks/02-api-feature-foundation/spec.md) | [#40](https://github.com/fahadzia88/factory/pull/40) | pr open (factory-3d) |
| 03 | [spec](tasks/03-api-feature-read/spec.md) | [#41](https://github.com/fahadzia88/factory/pull/41) | pr open (factory-3d) |
| 04 | [spec](tasks/04-api-feature-create/spec.md) | [#42](https://github.com/fahadzia88/factory/pull/42) | pr open (factory-3d) |
| 05 | [spec](tasks/05-api-feature-steps/spec.md) | [#44](https://github.com/fahadzia88/factory/pull/44) | pr open (factory-3d) |
| 06 | [spec](tasks/06-api-feature-chat/spec.md) | [#45](https://github.com/fahadzia88/factory/pull/45) | pr open (factory-3d) |
| 07 | [spec](tasks/07-agent-feature-read-tools/spec.md) | [#46](https://github.com/fahadzia88/factory/pull/46) | pr open (factory-3d) |
| 08 | [spec](tasks/08-agent-feature-write-tools/spec.md) | [#47](https://github.com/fahadzia88/factory/pull/47) | pr open (factory-3d) |
| 09 | [spec](tasks/09-app-feature-page/spec.md) | | in progress (factory-3d) |
| 10 | [spec](tasks/10-app-feature-chat/spec.md) | | not started |
| 11 | [spec](tasks/11-app-features-card/spec.md) | | not started |

## Changelog

- 2026-11-06 — Created.
- 2026-11-06 — Approved.
- 2026-11-06 — Back to draft: AC-3 is verified manually (the `api` container can't read the repo root).
- 2026-11-06 — Approved again; task specs approved.
