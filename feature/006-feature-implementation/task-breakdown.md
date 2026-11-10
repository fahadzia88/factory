---
feature: 006-feature-implementation
status: in-progress
approved: 2026-11-09
---

# Feature Implementation: task breakdown

Requirements: [requirements.md](requirements.md)

## Contracts

The interfaces between layers, fixed before the tasks that depend on them. Every route needs
`Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as `ProblemDetail` with a
`code` and a `detail` the app and agent show as is. On every route below: `404 project_not_found`,
`404 feature_not_found`, `400 invalid_request` (non-numeric id, unreadable body, missing field),
as in 004. GitHub failures use 002's mapping (`502`, `504` on timeout, `429`-style rate limit as
002 does); a blank `GIT_TOKEN` answers `503 git_not_configured`.

### Design decisions

- **No change to the locked agent files.** `chat.ts` only streams `tool` events (id, name,
  status), not tool input. So the agent's `ask_questions` tool posts the questions to the API,
  and the API adds a `questions` line to the NDJSON it relays to the app (see "Questions").
- **The API does the git and GitHub work; the agent does the file work.** The agent writes and
  commits in a task worktree (local `git`, agent author, as `feature_commit` in 004) and pushes
  through the existing `POST …/checkout/worktrees/{worktreeId}/push`. Starting a task, opening
  a PR, restacking and finishing are API routes the agent calls.
- **Stack model.** A task row exists per started task, plus row `00` for the docs branch. The
  *effective base* of task N is the branch of the nearest lower task whose PR isn't merged, or
  the default branch when every lower PR has merged (row `00` included). A branch is created
  from, targets and is restacked onto its effective base.
- **Token.** PR create, retarget and read use the API's `GIT_TOKEN` (`GitProperties`), which gets
  Pull requests read and write added. The 002 read routes keep `API_GITHUB_TOKEN`.
- **Polling.** Nothing is pushed to the agent. Task routes read GitHub when called; the agent
  calls `feature_tasks` at the start of an implementation turn and when asked; the page reloads
  tasks on load, after each chat turn and on a Refresh button.

### Database (api only)

- `feature_task`: `id`, `feature_id`, `number` (0 = docs; unique with `feature_id`), `layer`
  (`docs`, `api`, `app`, `agent`, `infra`), `slug`, `branch`, `base_branch`, `base_head` (the SHA
  of the base the branch is built on: the lower branch's tip, or the default branch's tip),
  `spec_path` (null for 0), `step_id` (null for 0), `pull_number` (null until opened), `created_at`,
  `updated_at`. Row 0 is created the first time the tasks are read or the docs PR is opened.
- `feature_question`: `id`, `feature_id`, `payload` (text, the questions as JSON), `status`
  (`pending`, `answered`, `superseded`), `answers` (text, JSON, null when answered by a typed
  reply), `after_message_id` (null until the turn ends), `answer_message_id`, `created_at`,
  `answered_at`.
- The worktree of a task is found by `branch` in the project's checkout, as for features.

### Task (JSON)

```json
{
  "number": 2,
  "layer": "api",
  "slug": "task-start",
  "branch": "feat/006-feature-implementation/02-api-task-start",
  "baseBranch": "feat/006-feature-implementation/01-api-task-foundation",
  "specPath": "feature/006-feature-implementation/tasks/02-api-task-start/spec.md",
  "stepId": 14,
  "worktree": { "id": 12, "path": "/repos/8.worktrees/feat__006…", "missing": false },
  "pull": {
    "number": 51, "title": "006 02 api: Start a task", "state": "open", "draft": false,
    "htmlUrl": "https://github.com/o/r/pull/51", "baseRef": "feat/006…/01-…", "headSha": "ab12cd3",
    "mergedAt": null,
    "checks": {
      "state": "failure",
      "runs": [{ "name": "api", "status": "completed", "conclusion": "failure",
                 "htmlUrl": "https://github.com/…", "title": "Tests failed", "summary": "3 failed (≤1000 chars)" }]
    }
  },
  "pullError": null,
  "needsRestack": false,
  "createdAt": "2026-11-10T09:00:00Z",
  "updatedAt": "2026-11-10T09:00:00Z"
}
```

- `pull` is null when no PR was opened or GitHub couldn't be read; then `pullError` is the
  problem's `detail` (only in the list route, so one bad read doesn't hide the other tasks).
- `state`: `open`, `closed` or `merged` (as 002). `checks.state`: `none` (no runs), `pending`
  (any run not completed), `failure` (any conclusion other than success, neutral, skipped),
  `success`. Runs are GitHub check runs for the PR's head SHA.
- `needsRestack` is true for an open task whose PR isn't merged or closed and either its stored
  `baseBranch` is no longer its effective base, or its base is a task branch whose PR head SHA
  differs from `base_head`. Computed from the PR reads; no git or fetch.
- Row 0 has `layer: "docs"`, `slug: "docs"`, `specPath: null`, `stepId: null`, `worktree` of the
  feature's branch.

### Task routes (`/api/projects/{id}/features/{featureId}`)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET …/tasks` | — | `200` `[Task]`, number ascending, row 0 first | — |
| `GET …/tasks/{n}` | — | `200` Task, GitHub failure answers `502`/`504` | `404 task_not_found` |
| `POST …/tasks` | `{ "dir": "02-api-task-start" }` | `201` Task | `400 invalid_task` (dir not `NN-<layer>-<slug>` or no `spec.md` there), `409 specs_not_approved` (problem has `unapproved`: spec paths), `409 task_exists`, `409 task_out_of_order` (`expected`: the next number), `409 previous_task_unfinished` (the task below has no PR), `409 worktree_missing`, plus the checkout's git codes |
| `POST …/tasks/{n}/pull` | `{ "task": "Start a task", "acs": ["AC-3", "AC-4"] }`; none for `n` = 0 | `201` Task | `404 task_not_found`, `409 pull_exists`, `409 task_not_pushed` (branch not on `origin`), `400 invalid_request` (blank `task`, no ACs, a value that isn't `AC-<n>`), `409 worktree_missing` |
| `POST …/tasks/{n}/restack` | — | `200` Task | `404 task_not_found`, `409 restack_not_needed`, `409 task_dirty` (uncommitted changes), `409 task_closed` (PR merged or closed), `409 restack_conflict` (`files`: conflicting paths; nothing pushed, branch as before), `409 worktree_missing`, git codes |
| `POST …/complete` | — | `200` Feature | `409 tasks_unfinished` (`unfinished`: task numbers and reasons: spec folder without a task, no PR, PR not merged), `409 invalid_status` (workflow has no `done`) |

Behaviour:

- **Start** (`POST …/tasks`): reads the feature's docs worktree; refuses unless every
  `tasks/*/spec.md` has `status: approved` in its frontmatter. Requires the next number (rows
  0…N-1 exist, and N-1 has a PR). Fetches, adds the worktree on
  `feat/<code>/<NN>-<layer>-<slug>` from the effective base (003 `WorktreeService.add`), stores
  `base_head`, adds a step of type `implementation` titled `Task <NN>: <layer> <slug>` with
  `documentPath` = the spec, and sets the feature to `implementation` when it isn't already there
  or `done`. Any failure after the worktree exists removes the worktree and branch and the row.
- **Pull** (`…/pull`): title `<NNN> <NN> <layer>: <task>` (row 0: `<NNN> <feature name>: docs`);
  head = branch, base = effective base; body = goal line, link to the spec on the docs branch on
  GitHub (row 0: to the feature folder), `ACs covered: AC-3, AC-4`, "Stacked on #<lower PR>" when
  the base is a task branch. Sets the task step to `pr-open`. Creating a feature calls this for
  row 0 after the create sequence; a failure there doesn't fail the create: the `201` Feature
  has `docsPullError` (the problem's `detail`) and no PR.
- **Restack**: fetch; new base = effective base; `git rebase --onto origin/<newBase> <base_head>
  <branch>` in the task's worktree; on conflict `git rebase --abort`. Then push with
  force-with-lease (`WorktreeService.push(force = true)`), retarget the PR when the base changed
  (`PATCH /repos/…/pulls/{n}` `base`), store `base_branch` and `base_head`.
- **Complete**: every spec folder has a row, every row has a merged PR. Sets the feature to
  `done` and removes the worktrees of rows 1… (`WorktreeService.remove`, no force); a worktree it
  can't remove stays and is named in the response's `keptWorktrees`. The docs worktree stays.
  The user's confirmation of the ACs is asked by the agent; the API can't know it.
- Step status (`in-progress`, `pr-open`, `merged`, `stopped`, `skipped`) is set by the agent
  through the existing step routes, except `pr-open`, which the pull route sets.

Feature JSON gains nothing; the create response gains `docsPullError` (string or null).

### GitHub (api ↔ GitHub), `GIT_TOKEN`

| Call | Used by |
| ---- | ------- |
| `POST /repos/{o}/{r}/pulls` `{title, head, base, body}` | pull |
| `PATCH /repos/{o}/{r}/pulls/{n}` `{base}` | restack |
| `GET /repos/{o}/{r}/pulls/{n}` | all reads (state, head SHA, merged) |
| `GET /repos/{o}/{r}/commits/{sha}/check-runs` (up to 100) | all reads |

### Questions (agent ↔ api ↔ app)

Question set, as the agent sends it and the app shows it:

```json
{ "questions": [
  { "header": "Token",
    "question": "Which token does the API use for pull requests?",
    "multiSelect": false,
    "options": [
      { "label": "Keep GIT_TOKEN", "description": "Add Pull requests read and write to it." },
      { "label": "New token", "description": "A separate token only for pull requests." }
    ] }
] }
```

- Rules (checked by the agent tool's schema and again by the API, `400 invalid_questions` with
  a `detail` naming the problem): 1–4 questions; `header` 1–12 characters; `question` 1–300;
  2–4 options, the first is the recommended one; `label` 1–60 and unique within a question and
  never "Other"; `description` 1–200. "Other" is not sent: the app adds a free-text answer to
  every card.
- `POST …/questions` (agent → api): the body above → `201` `{ "id": 7 }`. Earlier pending sets
  of the feature become `superseded`.
- **Relay.** When the API relays the agent's NDJSON and sees `tool` with name
  `mcp__app__ask_questions` and status `completed`, it writes before that line
  `{"type":"questions","id":7,"questions":[…]}` for the newest pending set. When the turn's
  `done` message is stored, the sets created in the turn get `after_message_id` = that message.
- **Answer.** `POST …/messages` accepts `{ "message": "…" }` as before, or
  `{ "questionsId": 7, "answers": [{ "header": "Token", "selected": ["Keep GIT_TOKEN"], "other": null }] }`
  (`message` then optional). Each answer needs a `selected` label (exactly one unless
  `multiSelect`) or `other` text, or both. Refused before streaming: `400 invalid_answers`,
  `409 questions_not_pending`. The API turns structured answers into the user's message text:

  ```
  Answers to your questions:
  1. Token: Keep GIT_TOKEN (recommended)
  2. Turn size: Other: split into two turns
  ```

  stores it as the user message, marks the set `answered` with the answers and
  `answer_message_id`, and sends it to the agent. A plain typed message while a set is pending
  marks that set `answered` with no answers (the reply is the answer) the same way.
- `GET …/messages` now returns items in order, each message followed by the question sets whose
  `after_message_id` is it (sets with none go by `created_at`):

  ```json
  [ { "kind": "message", "id": 31, "role": "assistant", "text": "…", "createdAt": "…" },
    { "kind": "questions", "id": 7, "status": "pending", "questions": [ … ],
      "answers": null, "answerMessageId": null, "createdAt": "…" } ]
  ```

  `id` of messages is unchanged; `kind: "message"` is the only addition for them. The app is
  the only reader of this route.

### Agent tools (agent → api, agent → `/repos`)

New `mcp__app__` tools in `agent/src/tools/`; every one takes `projectId` and `featureId`.

| Tool | Input | Does |
| ---- | ----- | ---- |
| `ask_questions` | ids, `questions` | Zod-checks the rules above, `POST …/questions`, answers "Shown to the user. End your turn and wait for the answers." |
| `feature_tasks` | ids | `GET …/tasks`. |
| `task_start` | ids, `dir` | `POST …/tasks`. |
| `task_list_files` | ids, `number`, `dir?` | Files in that task's worktree (≤ 500, `.git` excluded). |
| `task_read_file` | ids, `number`, `path` | A text file, ≤ 200 KB. |
| `task_write_file` | ids, `number`, `path`, `content` | Writes a file anywhere in the task's worktree. |
| `task_edit_file` | ids, `number`, `path`, `oldText`, `newText` | One exact unique replacement. |
| `task_commit` | ids, `number`, `message` | Checks `HEAD` is the task's branch, `git add -A`, `git commit` (agent author), push via the checkout push route. Returns the SHA or "nothing to commit". |
| `task_open_pull` | ids, `number`, `task?`, `acs?` | `POST …/tasks/{n}/pull` (also row 0). |
| `task_restack` | ids, `number` | `POST …/tasks/{n}/restack`. |
| `feature_complete` | ids | `POST …/complete`. |

- Task number and worktree come from `GET …/tasks`, never from the model's paths.
- Path rules (code, not prompt): relative paths; the real path (symlinks followed) is inside the
  task's worktree; never in `.git`; a path matching a `locked` pattern in the worktree's
  `.claude/base-files` (and not listed in `.claude/base-overrides`) is refused for writes. Row 0
  is not writable with these tools (the docs branch uses the `feature_*` file tools).
- There is no tool that runs a command; `chat.ts` keeps `tools: []`.
- `task_*` file tools refuse a task other than the newest one without a merged or closed PR
  ("only one task at a time"), so the agent can't edit a task above or below the active one,
  except to fix a failed check or restack on a task whose PR is open.

### app → api

`lib/features.ts` gains the Task, Pull, Question types and calls. `sendFeatureMessage` takes
either text or `{ questionsId, answers }`; the stream reader handles the `questions` event; the
stored message list parses the `kind` union. Errors show the problem's `detail`; `5xx` codes get
"Try again".

## Tasks

Stack order, bottom first. Each task is one PR based on the row above it.

| #  | Layer | Task | Depends on | ACs | Branch |
| -- | ----- | ---- | ---------- | --- | ------ |
| 00 | docs  | Feature folder | — | — | `feat/006-feature-implementation/00-docs` |
| 01 | infra | Remove `GITHUB_TOKEN` and `GITHUB_REPO` from the agent service; update the `API_GIT_TOKEN` comment (Pull requests read and write). | 00 | AC-12 | `feat/006-feature-implementation/01-infra-drop-agent-github-token` |
| 02 | api   | GitHub PR write/read client on `GIT_TOKEN`: create, retarget, get with head SHA, check runs; shared failure mapping; `PullState` with checks summary. Client tests with `MockRestServiceServer`. | 01 | AC-13 (client), AC-14 (title/body builder), AC-15, AC-16 | `feat/006-feature-implementation/02-api-github-pull-client` |
| 03 | api   | `FeatureTask` entity and repository, spec reader (frontmatter status), effective-base rule, `needsRestack`, Task JSON, `GET …/tasks` and `GET …/tasks/{n}` (row 0 created on read). | 02 | AC-17, AC-18 | `feat/006-feature-implementation/03-api-task-foundation` |
| 04 | api   | Start a task: `POST …/tasks` (specs approved, order, worktree from the effective base, step, feature → `implementation`, rollback). Tests against local bare repos. | 03 | AC-2, AC-3, AC-4 | `feat/006-feature-implementation/04-api-task-start` |
| 05 | api   | Open a PR: `POST …/tasks/{n}/pull`, title/body, step → `pr-open`; docs PR on feature create with `docsPullError`. | 04 | AC-13, AC-14, AC-16 | `feat/006-feature-implementation/05-api-task-pull` |
| 06 | api   | Restack: `POST …/tasks/{n}/restack` (rebase `--onto`, conflict abort, force-with-lease push, retarget). Tests with local bare repos incl. a conflict. | 05 | AC-8, AC-9 | `feat/006-feature-implementation/06-api-task-restack` |
| 07 | api   | Complete: `POST …/complete` (all PRs merged, status `done`, remove task worktrees). | 06 | AC-10 | `feat/006-feature-implementation/07-api-feature-complete` |
| 08 | api   | Question store: `FeatureQuestion` entity, validation, `POST …/questions`, superseding. | 07 | AC-19 (api) | `feat/006-feature-implementation/08-api-question-store` |
| 09 | api   | Questions in the chat: `questions` relay line, `after_message_id`, structured answers and typed replies on `POST …/messages`, `GET …/messages` union. | 08 | AC-19, AC-21, AC-22, AC-23 | `feat/006-feature-implementation/09-api-question-chat` |
| 10 | app   | `lib/features.ts` task types and calls; "Pull requests" card on the feature page: each task with its PR link (in-app route), state, checks, needs-restack, Refresh; docs-PR "Open pull request" retry and error. Reloads after each chat turn. | 09 | AC-13 (retry), AC-17, AC-25 | `feat/006-feature-implementation/10-app-feature-tasks` |
| 11 | app   | Question cards in the feature chat: types and stream/history handling in `lib/features.ts` and `use-feature-chat.ts`, `question-cards.tsx` (cards, recommended first, multi-select, "Other", submit, answered read-only), typed reply marks answered, keyboard and 375 px. | 10 | AC-19–AC-25 | `feat/006-feature-implementation/11-app-question-cards` |
| 12 | agent | `ask_questions` tool and the "Questions" section of `src/instructions.md` (use it for choices; an "Approve" option is a yes; typed replies are answers). | 11 | AC-19, AC-24 | `feat/006-feature-implementation/12-agent-ask-questions` |
| 13 | agent | Task file tools: `task_list_files`, `task_read_file`, `task_write_file`, `task_edit_file`, `task_commit`, path and locked-file guards, worktree lookup. `node:test` incl. escapes, `.git`, symlinks, locked paths, wrong branch. | 12 | AC-5, AC-6 | `feat/006-feature-implementation/13-agent-task-files` |
| 14 | agent | Task flow tools: `feature_tasks`, `task_start`, `task_open_pull`, `task_restack`, `feature_complete`; the "Implementation" section of `src/instructions.md`. | 13 | AC-2–AC-4, AC-7, AC-8, AC-10, AC-11, AC-15, AC-16 | `feat/006-feature-implementation/14-agent-task-flow` |
| 15 | infra | `implementation` step type in `feature/workflow.json` (states `in-progress`, `pr-open`, `merged`, `stopped`, `skipped`) and the chat skill `.claude/skills/feature-implementation/SKILL.md`. Makes implementation startable. | 14 | AC-1, AC-2, AC-3, AC-7, AC-11, AC-15 | `feat/006-feature-implementation/15-infra-implementation-skill` |
| 16 | app   | Markdown in the chat: agent messages (stored and streaming) render through the existing `Markdown` component from `components/pull-requests/markdown.tsx`, user messages stay plain text; wide tables and code blocks scroll inside the message; tests that raw HTML and `javascript:` links stay inert. | 15 | AC-26, AC-27, AC-28 | `feat/006-feature-implementation/16-app-chat-markdown` |

Infra tasks 01 and 15 are done by the coordinator with the user's go-ahead. `src/agent.ts` and
`src/instructions.md` (12–14), `docker-compose.yml` (01) and `application.properties` (none
expected) are `shared` base files: changes are additive and the user confirms each edit. No
`locked` file changes: `chat.ts`, `server.ts`, `agent/README.md` and `components/agent/*` stay
as they are (the new tools are documented in `src/instructions.md`, not the locked README).

## AC coverage

| AC    | Tasks            | Verified by |
| ----- | ---------------- | ----------- |
| AC-1  | 15               | Manual: the file parses and its skill folder has `SKILL.md`; creating a feature on the factory project (runs the API validator) still works |
| AC-2  | 04, 14, 15       | Service test: one spec `draft` → `409 specs_not_approved`, no worktree/branch/step/row; manual in chat |
| AC-3  | 04, 14, 15       | Service test: first start sets `implementation`, only task 01 exists; manual |
| AC-4  | 04, 14           | Service test against a bare remote: branch `feat/<code>/<NN>-<layer>-<slug>` from the effective base, step with spec path; manual |
| AC-5  | 13               | Path-guard tests: `..`, absolute, symlink out, `.git`, another task's worktree, locked pattern → refused, nothing written; commit on a wrong branch refused |
| AC-6  | 13, 15           | Test: the registered tool list has no command-running tool; `chat.ts` unchanged (`tools: []`); skill says checks run in CI only |
| AC-7  | 05, 14, 15       | Pull test; manual: a turn ends with the PR reported and no second task started |
| AC-8  | 06, 14           | Restack service test: lower branch moved / lower merged → rebased, pushed with lease, PR retargeted; `needsRestack` test (03) |
| AC-9  | 06               | Restack conflict test: `409 restack_conflict`, no push, branch and worktree as before |
| AC-10 | 07, 14           | Complete test: all merged → `done`, task worktrees gone, docs worktree kept; unmerged → `409 tasks_unfinished`; manual |
| AC-11 | 10, 14, 15       | Manual: stop/retry/skip in chat updates the step; page has no such controls |
| AC-12 | 01               | Manual: `docker-compose.yml` agent service has no `GITHUB_TOKEN`; agent still runs |
| AC-13 | 02, 05, 10       | Create test: docs PR opened, and with a failing client the feature exists with `docsPullError`; manual retry button |
| AC-14 | 02, 05           | Title/body builder unit tests; pull test asserts base, title, spec link, ACs |
| AC-15 | 02, 03, 14, 15   | Check-run mapping tests (failing check by name, summary); manual: agent names the check and fixes on the same branch |
| AC-16 | 02, 05, 14       | Client tests: 403/404/timeout/blank token → `502`/`504`/`503`; agent tool returns the `detail` |
| AC-17 | 03, 10           | List test with PR state and checks; manual page; task with no PR shows none |
| AC-18 | 03–09            | `@WebMvcTest` `401` on every new route |
| AC-19 | 08, 09, 11, 12   | Validation tests (counts, header, options, "Other"); tool schema test; manual cards |
| AC-20 | 11               | Component/manual: multi-select vs single |
| AC-21 | 09, 11           | Chat test: answers sent as the next user message; manual |
| AC-22 | 09, 11           | Chat test: a typed message marks the pending set answered; manual |
| AC-23 | 09, 11           | `GET messages` order test; manual reload shows answered cards read-only |
| AC-24 | 11, 12           | Manual: "Approve" option → agent records approval; no option → no approval |
| AC-25 | 10, 11           | Manual at 375 px and with the keyboard |
| AC-26 | 16               | Component test: heading, list, table, code block and link render as elements, also from a partial streamed string; manual after reload |
| AC-27 | 16               | Component test: `<script>`, `<img onerror>` render as text, a `javascript:` link has no `href`; user message with `**x**` shows the asterisks |
| AC-28 | 16               | Manual at 375 px with a wide table and a long code line |

## Rollout

- 01–09 add routes, tables and a client nothing calls yet. The schema comes from the entities
  (`ddl-auto=update`).
- 10 adds the "Pull requests" card to the feature page; it only reads, and shows the docs PR
  as "No pull request" until one exists.
- 11 renders question cards but the agent can't send any until 12 registers `ask_questions` and
  its instructions, so the app lands first. 13–14 register the task tools, which do nothing until
  the user asks for implementation, and the skill that starts it (15) merges last of the
  implementation work.
- 16 is independent of the rest: it changes only how agent messages are drawn in the chat, reuses
  the existing renderer (no new dependency), and needs no API or agent change.
- Before 05 is useful: give `API_GIT_TOKEN` Pull requests read and write on the project
  repositories. Without it PR calls answer `502`/`503` and nothing else breaks.
- Release: remove `GITHUB_TOKEN`/`GITHUB_REPO` from `.env` if set only for the agent (01).

## Progress

| #  | Spec | PR | Status |
| -- | ---- | -- | ------ |
| 01 | [spec](tasks/01-infra-drop-agent-github-token/spec.md) | | not started |
| 02 | [spec](tasks/02-api-github-pull-client/spec.md) | | not started |
| 03 | [spec](tasks/03-api-task-foundation/spec.md) | | not started |
| 04 | [spec](tasks/04-api-task-start/spec.md) | | not started |
| 05 | [spec](tasks/05-api-task-pull/spec.md) | | not started |
| 06 | [spec](tasks/06-api-task-restack/spec.md) | | not started |
| 07 | [spec](tasks/07-api-feature-complete/spec.md) | | not started |
| 08 | [spec](tasks/08-api-question-store/spec.md) | | not started |
| 09 | [spec](tasks/09-api-question-chat/spec.md) | | not started |
| 10 | [spec](tasks/10-app-feature-tasks/spec.md) | | not started |
| 11 | [spec](tasks/11-app-question-cards/spec.md) | | not started |
| 12 | [spec](tasks/12-agent-ask-questions/spec.md) | | not started |
| 13 | [spec](tasks/13-agent-task-files/spec.md) | | not started |
| 14 | [spec](tasks/14-agent-task-flow/spec.md) | | not started |
| 15 | [spec](tasks/15-infra-implementation-skill/spec.md) | | not started |
| 16 | [spec](tasks/16-app-chat-markdown/spec.md) | | not started |

## Changelog

- 2026-11-09 — Created.
- 2026-11-09 — Added task 16 (Markdown in the chat) for the new AC-26 to AC-28.
- 2026-11-09 — Approved.
- 2026-11-10 — Task specs approved; implementation started.
