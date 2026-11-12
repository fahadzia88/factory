---
feature: 007-bugs-updates
status: draft
approved:
---

# bugs-updates: task breakdown

Requirements: [requirements.md](requirements.md)

## Contracts

The interfaces between layers, fixed before the tasks that depend on them. Every route needs
`Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as `ProblemDetail` with a
`code` and a `detail` the app and agent show as is. On every route below: `404 project_not_found`,
`404 ticket_not_found` (also for a ticket of another project), `400 invalid_request` (non-numeric
id, unreadable body, missing field). Routes that change anything answer `409 project_archived` for
an archived project; reads still work. GitHub failures use 002's mapping (`502`, `504`, rate limit
as 002); a blank `GIT_TOKEN` answers `503 git_not_configured`.

### Design decisions

- **A new `ticket` package in the api**, beside `feature`. It reuses `WorktreeService` (add, push),
  `GitRunner`, `CheckoutLocks`, `GitHubPullWriteClient` and `FeatureTasks.repository`-style URL
  parsing. It does not touch features or tasks.
- **The API does the git and GitHub work; the agent does the file work.** The API creates the
  worktree, commits `details.md`, pushes, commits on the **Commit** button, pushes on **Push** and
  opens the pull request. The agent only reads and writes files in the ticket's worktree and has
  no commit, push or pull request tool (the user has the buttons).
- **Modes are enforced in code, not in the prompt.** A ticket is in `investigator` mode until the
  user approves. The agent's write tools call `GET …/tickets/{id}` before every write and refuse
  anything but `details.md` in `investigator` mode.
- **Findings.** `details.md` is created from a template. "Findings written" means its content
  differs from the template the API wrote (the API stores the template's SHA-256 as
  `details_baseline`). Approve is refused until then.
- **History is one table, written in the same transaction as the change** (a workspace result is
  written in its own transaction after the git work, since git is not transactional).
- **Chat.** One chat per ticket, kept as `ticket_message` rows, relayed as NDJSON exactly like the
  feature chat (`FeatureChat`), in the ticket's own agent session. No question cards.
- **Rollout flag is the missing link.** Routes and pages exist from task 09 but nothing links to
  them until task 13.

### Vocabulary

- `type`: `bug`, `improvement`. `priority`: `low`, `medium`, `high`, `critical`.
- `status` (JSON values): `filed`, `open`, `in_progress`, `in_review`, `merged`, `deployed`,
  `closed`, `reopened`, `incorrect_expectation`, `out_of_scope`, `backlog`. Improvements never use
  `reopened` or `incorrect_expectation`.
- `mode`: `investigator`, `implementer`.

Transitions (from → allowed to). `closed` is allowed from every status except `closed`.

| From | To (bug) | To (improvement) |
| ---- | -------- | ---------------- |
| `filed` | `open`, `incorrect_expectation`, `out_of_scope`, `backlog`, `closed` | `open`, `out_of_scope`, `backlog`, `closed` |
| `backlog` | `open`, `closed` | same |
| `open` | `in_progress`, `closed` | same |
| `in_progress` | `in_review`, `closed` | same |
| `in_review` | `merged`, `closed` | same |
| `merged` | `deployed`, `closed` | same |
| `deployed` | `closed`, `reopened` | `closed` |
| `closed` | `reopened` | none |
| `reopened` | `in_progress`, `closed` | not used |
| `incorrect_expectation`, `out_of_scope` | `closed` | `closed` |

Status descriptions (the API returns them, the app shows them):

| Status | Description |
| ------ | ----------- |
| `filed` | Reported, not yet looked at. |
| `open` | Accepted and ready to be worked on. |
| `in_progress` | Someone is working on it. |
| `in_review` | The fix or change is waiting for review. |
| `merged` | The change is merged and not yet released. |
| `deployed` | The change is released. |
| `closed` | Finished or dropped; no more work planned. |
| `reopened` | Bugs only. A deployed or closed bug that needs more work. |
| `incorrect_expectation` | Bugs only. The behaviour is as designed; the report was a misunderstanding. |
| `out_of_scope` | Will not be done as part of this project. |
| `backlog` | Valid, but parked for later. |

### Database (api only)

- `ticket`: `id`, `project_id`, `number` (per project, from 1, unique with `project_id`), `type`,
  `title`, `description` (nullable), `priority`, `status`, `pull_request_url` (nullable),
  `pull_number` (nullable; set when created through the button), `slug` (nullable until the
  workspace), `branch` (nullable), `folder` (nullable), `workspace_state` (`none`, `ready`,
  `failed`), `workspace_error` (nullable), `mode`, `approved_at` (nullable), `details_baseline`
  (nullable), `agent_session_id` (nullable), `created_at`, `updated_at`.
- `ticket_event`: `id`, `ticket_id`, `kind` (`created`, `status`, `edit`, `workspace`, `approve`,
  `commit`, `push`, `pull`), `field` (nullable), `old_value`, `new_value` (nullable text), `detail`
  (nullable text), `created_at`. Never updated or deleted.
- `ticket_message`: `id`, `ticket_id`, `role`, `text`, `created_at` (as `feature_message`).
- The worktree of a ticket is found by `branch` in the project's checkout, as for features.

### Ticket (JSON)

```json
{
  "id": 12, "projectId": 8, "number": 3, "type": "bug",
  "title": "Login fails on Safari", "description": "…", "priority": "high",
  "status": "in_progress", "statusDescription": "Someone is working on it.",
  "allowedStatuses": ["in_review", "closed"],
  "pullRequestUrl": "https://github.com/o/r/pull/9", "pullNumber": 9,
  "workspace": {
    "state": "ready", "error": null,
    "branch": "bugs/3-login-fails-on-safari",
    "folder": "feature/bugs/3-login-fails-on-safari",
    "worktree": { "id": 20, "path": "/repos/8.worktrees/bugs__3-…", "missing": false }
  },
  "mode": "investigator", "approvedAt": null, "hasFindings": false,
  "createdAt": "2026-11-11T09:00:00Z", "updatedAt": "2026-11-11T09:00:00Z"
}
```

- `workspace` is `{ "state": "none", … }` with null branch, folder and worktree until the first
  `in_progress`. `allowedStatuses` is empty for an archived project.
- `hasFindings` is true when `details.md` differs from its baseline; false without a workspace.

### Ticket routes (`/api/projects/{id}/tickets`)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET …/tickets?type=&status=` | — | `200` `[Ticket]`, number descending | `400 invalid_request` (unknown type or status) |
| `POST …/tickets` | `{ "type", "title", "description?", "priority?" }` | `201` Ticket (status `filed`, priority default `medium`) | `400 invalid_ticket` (blank or over 200 chars title, missing or unknown type or priority, description over 10000), `409 project_archived` |
| `GET …/tickets/{tid}` | — | `200` Ticket | — |
| `PATCH …/tickets/{tid}` | any of `{ "title", "description", "priority", "pullRequestUrl" }` | `200` Ticket | `400 invalid_ticket` (as above; link not an http(s) URL; `type` in the body is refused with `ticket_type_fixed`), `409 project_archived` |
| `GET …/tickets/statuses` | — | `200` `[{ "status", "description", "types": ["bug","improvement"] }]` | — |
| `GET …/tickets/{tid}/history` | — | `200` `[Event]`, oldest first | — |
| `POST …/tickets/{tid}/status` | `{ "status" }` | `200` Ticket | `400 invalid_ticket` (unknown status), `409 invalid_transition` (problem has `from`, `to`, `allowed`), `409 project_archived` |
| `POST …/tickets/{tid}/workspace` | — (retry) | `200` Ticket | `409 workspace_not_needed` (never in progress, or already ready), `409 project_archived`, git codes |
| `GET …/tickets/{tid}/details` | — | `200` `{ "path", "content", "hasFindings" }` | `409 workspace_missing` (no workspace or worktree missing) |
| `POST …/tickets/{tid}/approve` | — | `200` Ticket (`mode` `implementer`) | `409 workspace_missing`, `409 no_findings`, `409 already_approved`, `409 project_archived` |
| `POST …/tickets/{tid}/commit` | `{ "message?" }` (default `#<number> <title>`) | `200` `{ "committed": true, "sha": "ab12cd3" }` or `{ "committed": false, "sha": null }` when there is nothing to commit | `409 workspace_missing`, `400 invalid_request` (message over 200 chars), `409 project_archived` |
| `POST …/tickets/{tid}/push` | — | `200` `{ "branch", "head" }` | `409 workspace_missing`, `409 nothing_committed` (no commit beyond the default branch), `409 push_rejected` and 003's other push codes, `409 project_archived` |
| `POST …/tickets/{tid}/pull` | `{ "title?", "replaceLink?": false }` | `201` Ticket | `409 workspace_missing`, `409 branch_not_pushed`, `409 nothing_to_compare` (branch has no commits beyond the default), `409 pull_exists`, `409 pull_link_set` (a hand-entered link exists and `replaceLink` is not true), `409 project_archived`, GitHub codes |
| `GET …/tickets/{tid}/messages` | — | `200` `[{ "id", "role", "text", "createdAt" }]` | — |
| `POST …/tickets/{tid}/messages` | `{ "message" }` | `200` NDJSON stream as the feature chat | `400 blank_message`, `409 workspace_missing`, `409 chat_busy`, `409 project_archived`, `502 agent_unavailable` |
| `POST …/tickets/{tid}/cancel` | — | `200` `{ "cancelled" }` | — |

Event (JSON): `{ "id", "kind", "field", "oldValue", "newValue", "detail", "createdAt" }`.

Behaviour:

- **Create** writes the ticket (`number` = highest in the project + 1; a unique-key clash retries
  once) and one `created` event whose `newValue` is the JSON of the initial values.
- **Edit** writes one `edit` event per changed field (`field`, old, new); an edit that changes
  nothing writes none and returns the ticket. A rejected request writes nothing.
- **Status** checks the table; on success writes one `status` event (`field` `status`). The first
  change to `in_progress` (no workspace yet, `workspace_state` `none`) then runs the **workspace
  sequence** below, after the status is saved. A later change to `in_progress` with a ready
  workspace does nothing more; with a `failed` workspace it runs the sequence again.
- **Workspace sequence** (also behind the retry route): slug = title lowercased, runs of anything
  but `a-z0-9` replaced by `-`, trimmed of `-`, cut to 40 characters, `ticket` when empty. Branch
  `bugs/<number>-<slug>` or `updates/<number>-<slug>`; folder `feature/bugs/<number>-<slug>` or
  `feature/updates/<number>-<slug>`. Steps, each skipped when already done (so retry never
  duplicates): add the worktree from the default branch (`WorktreeService.add`); write
  `<folder>/details.md` from the template (below) and commit it (`#<number> <title>: details`);
  push (`WorktreeService.push`). Each step's outcome is a `workspace` event (`detail`: what was
  done or the error). On success `workspace_state` = `ready`; on a failure the state is `failed`
  with `workspace_error`, and the status change still stands. Slug, branch and folder are stored
  on the first attempt and never recomputed.
- **Template** of `details.md`: `# #<number> <title>`, a line with type, priority and status at
  creation, `## Description` with the ticket's description (or "No description."), `## Findings`
  with "Not investigated yet.", `## Plan` with "Not written yet.". `details_baseline` is the
  SHA-256 of the file as written.
- **Approve** needs `hasFindings`; sets `mode` `implementer`, `approved_at`, and writes an
  `approve` event. Approval is kept when the ticket is reopened.
- **Commit** runs `git add -A` and `git commit` in the worktree (as `FeatureCreator` does), writes
  a `commit` event with the SHA, and returns `committed: false` (no event) when the tree is clean.
- **Push** pushes the branch with `WorktreeService.push`, requires at least one commit beyond the
  default branch, writes a `push` event.
- **Pull** opens a pull request from the branch into the default branch with
  `GitHubPullWriteClient.create`. Title defaults to `#<number> <title>`. Body: the description (or
  "No description."), then `Ticket #<number> (<type>)` and a link to `details.md` on the branch.
  Checks, in order: workspace ready, no `pull_number` (`pull_exists`), no hand-entered link unless
  `replaceLink`, branch on `origin`, at least one commit beyond the default branch. Saves
  `pull_number` and `pull_request_url` and writes a `pull` event; the status does not change.
- **Chat** stores the user message and the agent's final reply, sends the agent a context line
  `[Ticket context: projectId=8 ticketId=12 number=3 type=bug. Use the ticket_* tools with these ids.]`
  and relays its events. Session handling and busy/cancel rules are `FeatureChat`'s.

### Agent tools (agent → api, agent → `/repos`)

New `mcp__app__` tools in `agent/src/tools/`; every one takes `projectId` and `ticketId`.

| Tool | Input | Does |
| ---- | ----- | ---- |
| `ticket_get` | ids | `GET …/tickets/{tid}` (fields, mode, workspace, `hasFindings`). |
| `ticket_list_files` | ids, `dir?` | Files in the ticket's worktree (≤ 500, `.git` excluded). |
| `ticket_read_file` | ids, `path` | A text file in the worktree, ≤ 200 KB. |
| `ticket_write_details` | ids, `content` | Writes the whole `<folder>/details.md`. Allowed in both modes. |
| `ticket_write_file` | ids, `path`, `content` | Writes any file in the worktree. Refused in `investigator` mode. |
| `ticket_edit_file` | ids, `path`, `oldText`, `newText` | One exact unique replacement. Refused in `investigator` mode. |

- The worktree and folder come from `GET …/tickets/{tid}`, never from the model's paths. Without
  a ready workspace every tool answers that the ticket has none yet.
- Path rules (in code): relative paths; the real path (symlinks followed) is inside the worktree;
  never in `.git`; a path matching a `locked` pattern in `.claude/base-files` (and not in
  `.claude/base-overrides`) is refused for writes; in `investigator` mode only the ticket's
  `details.md` can be written. The mode is read from the API on every write call.
- There is no tool that runs a command, commits, pushes or opens a pull request; `chat.ts` keeps
  `tools: []`.
- `src/instructions.md` gains a "Tickets" section: a message starting `[Ticket context: …]` is
  about that ticket; start in investigator mode by reading the code and recording findings and a
  plan in `details.md`; only change code after `mode` is `implementer`; tell the user to use
  Approve, Commit, Push and Create pull request.

### app → api

`lib/tickets.ts` holds the Ticket, Event, Details types and calls for every route above, status
and type labels, and the allowed-status helper (`allowedStatuses` comes from the API; the app
never recomputes the table). Errors show the problem's `detail`; `5xx` codes get "Try again".
Routes: `projects/:id/tickets` (list, with create form at `projects/:id/tickets/new`) and
`projects/:id/tickets/:ticketId` (detail).

## Tasks

Stack order, bottom first. Each task is one PR based on the row above it.

| #  | Layer | Task | Depends on | ACs | Branch |
| -- | ----- | ---- | ---------- | --- | ------ |
| 00 | docs  | Feature folder | — | — | `feat/007-bugs-updates/00-docs` |
| 01 | api   | Ticket foundation: `Ticket`, `TicketEvent` entities and repositories, create, list (type and status filters), get, edit with per-field history, history route, validation, archived refusal, exception handler. | 00 | AC-1, AC-2, AC-4, AC-15, AC-16, AC-17, AC-18, AC-19, AC-35, AC-36 | `feat/007-bugs-updates/01-api-ticket-foundation` |
| 02 | api   | Status rules and change: transition table by type, `allowedStatuses`, `statusDescription`, `GET …/tickets/statuses`, `POST …/status` with history. | 01 | AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12, AC-13, AC-14, AC-18, AC-19, AC-35 | `feat/007-bugs-updates/02-api-ticket-status` |
| 03 | api   | Workspace: slug, worktree, `details.md` template, commit, push, per-step history, failure kept on the ticket, idempotent retry route, hooked to the first `in_progress`. Tests against local bare repos. | 02 | AC-20, AC-21, AC-22, AC-23, AC-35 | `feat/007-bugs-updates/03-api-ticket-workspace` |
| 04 | api   | Details and approval: `GET …/details`, `hasFindings`, `POST …/approve`, `mode`, `approve` event. | 03 | AC-27, AC-28, AC-35 | `feat/007-bugs-updates/04-api-ticket-details` |
| 05 | api   | Commit and push routes with history. | 04 | AC-29, AC-30, AC-35 | `feat/007-bugs-updates/05-api-ticket-commit-push` |
| 06 | api   | Create pull request route, preconditions, saved link, history. | 05 | AC-31, AC-32, AC-33, AC-34, AC-35 | `feat/007-bugs-updates/06-api-ticket-pull` |
| 07 | api   | Ticket chat: `ticket_message`, `GET/POST …/messages`, cancel, relay, context line. | 06 | AC-25 (api), AC-26 (api), AC-35 | `feat/007-bugs-updates/07-api-ticket-chat` |
| 08 | agent | Ticket tools (`ticket_get`, `ticket_list_files`, `ticket_read_file`, `ticket_write_details`, `ticket_write_file`, `ticket_edit_file`), path and mode guards, "Tickets" section of `src/instructions.md`. `node:test` incl. escapes, `.git`, symlinks, locked paths, investigator refusals. | 07 | AC-26, AC-28 | `feat/007-bugs-updates/08-agent-ticket-tools` |
| 09 | app   | `lib/tickets.ts`; unlinked routes: ticket list with type and status filters and empty state, create form, not-found page. | 08 | AC-1, AC-2, AC-3, AC-4, AC-35, AC-36, AC-37 | `feat/007-bugs-updates/09-app-ticket-list` |
| 10 | app   | Ticket detail page: fields and edit form, status control with descriptions and only the allowed statuses, history list, archived read-only. | 09 | AC-5, AC-6, AC-13, AC-14, AC-15, AC-17, AC-18, AC-35, AC-36, AC-37 | `feat/007-bugs-updates/10-app-ticket-detail` |
| 11 | app   | Workspace panel: workspace state, error and retry, `details.md` as markdown (existing `Markdown` component), Approve (disabled without findings), Commit (message), Push, Create pull request (title, replace-link confirm, disabled reasons), pull link. | 10 | AC-23, AC-24, AC-25, AC-27, AC-28, AC-29, AC-30, AC-31, AC-32, AC-33, AC-34, AC-35, AC-37 | `feat/007-bugs-updates/11-app-ticket-workspace` |
| 12 | app   | Agent window to the right of the markdown (below on a narrow screen): chat for the ticket using the existing agent chat component, reloads `details.md` and the ticket after each turn. | 11 | AC-25, AC-26, AC-37 | `feat/007-bugs-updates/12-app-ticket-chat` |
| 13 | app   | Link the tracker: "Tickets" card on the project page and nav entry. | 12 | AC-3, AC-37 | `feat/007-bugs-updates/13-app-tickets-nav` |

No infra task: `GIT_TOKEN` (Pull requests read and write), the repos volume and the agent's access
to `/repos` already exist from 003 and 006. `src/agent.ts` and `src/instructions.md` (08) are
`shared` base files: changes are additive and the user confirms each edit. No `locked` file
changes: `chat.ts`, `server.ts`, `agent/README.md` and `components/agent/*` stay as they are.

## AC coverage

| AC    | Tasks            | Verified by |
| ----- | ---------------- | ----------- |
| AC-1  | 01, 09           | Service test: number 1 then 2, `filed`, `medium`, `created` event; manual create |
| AC-2  | 01, 09           | Validation tests (blank title, missing type); form shows the error |
| AC-3  | 09, 13           | Manual: empty project shows the empty state with a create action |
| AC-4  | 01, 09           | Controller test of `type` and `status` filters; manual list columns and filters |
| AC-5  | 10               | Manual: detail page shows fields, status description, history |
| AC-6  | 02, 10           | Service test: full chain `filed` → … → `closed`; manual |
| AC-7  | 02               | Test: `filed` → `in_progress` and → `merged` give `409 invalid_transition`, status unchanged |
| AC-8  | 02               | Test: `filed` → `out_of_scope`, `backlog`; bug → `incorrect_expectation` accepted |
| AC-9  | 02               | Test: `backlog` → `open`; `out_of_scope` and `incorrect_expectation` allow only `closed` |
| AC-10 | 02               | Parameterised test: every status except `closed` → `closed` |
| AC-11 | 02               | Test: bug `deployed` and `closed` → `reopened`; `reopened` allows `in_progress` and `closed` only |
| AC-12 | 02               | Test: bug in `filed`, `open`, `in_progress`, `in_review`, `merged` → `reopened` rejected |
| AC-13 | 02, 10           | Test: improvement → `reopened`, `incorrect_expectation` rejected, `closed` has no `allowedStatuses`; manual control omits them |
| AC-14 | 02, 10           | Statuses route test; manual: description visible |
| AC-15 | 01, 10           | Edit tests (title, description, priority, link; bad link → `invalid_ticket`); manual |
| AC-16 | 01               | Test: `type` in a PATCH → `400 ticket_type_fixed`, unchanged |
| AC-17 | 01, 10           | Test: one `created` event with initial values; manual |
| AC-18 | 01, 02, 10       | Test: moves and edits give ordered events with field, old, new; manual |
| AC-19 | 01, 02           | Test: rejected status and invalid edit add no event |
| AC-20 | 03               | Test against a bare remote: worktree, branch `bugs/<n>-<slug>`, folder with committed `details.md`, branch on origin, three `workspace` events |
| AC-21 | 03               | Same test for an improvement: `updates/…`, `feature/updates/…` |
| AC-22 | 03               | Test: `in_progress` → `in_review` → … → `reopened` → `in_progress` reuses worktree and folder, no new events |
| AC-23 | 03, 11           | Test: failing push leaves status changed, state `failed`, error stored; retry completes without a second worktree or commit; manual retry button |
| AC-24 | 11               | Manual: never-in-progress ticket shows no markdown pane or agent window |
| AC-25 | 07, 11, 12       | Manual: markdown rendered with the agent window to its right; chat route test |
| AC-26 | 07, 08, 12       | Tool tests: investigator can read and write only `details.md`; manual: page shows the agent's update after a turn |
| AC-27 | 04, 11           | Test: `hasFindings` false on the template, `no_findings` on approve; manual disabled button |
| AC-28 | 04, 08, 11       | Test: approve sets `implementer`, writes event; tool test: code write refused before and allowed after |
| AC-29 | 05, 11           | Test against a local repo: commit returns SHA and writes event; clean tree returns `committed: false`; manual |
| AC-30 | 05, 11           | Test: push with commits succeeds; rejected push returns the git code; manual retry |
| AC-31 | 06, 11           | Client-mocked test: PR from branch into default, link saved, `pull` event; manual |
| AC-32 | 06, 11           | Test: second request `409 pull_exists`; manual: button hidden, link shown |
| AC-33 | 06, 11           | Tests: `branch_not_pushed`, `nothing_to_compare`; manual disabled reason |
| AC-34 | 06, 11           | Test: GitHub failure → `502`, nothing saved; manual retry |
| AC-35 | 01–07, 09, 10, 11 | Controller tests: every write route on an archived project → `409 project_archived`, reads `200`; manual |
| AC-36 | 01, 09, 10       | Tests: unknown id and another project's ticket → `404`; manual not-found page |
| AC-37 | 09–13            | Manual at 375 px: list, create form, detail, markdown and agent window (stacked) without horizontal scroll |

## Rollout

- 01–07 add tables and routes nothing calls yet. The schema comes from the entities
  (`ddl-auto=update`).
- 08 registers tools that do nothing until a chat message carries a `[Ticket context: …]` line.
- 09–12 add pages reachable only by typing their URL.
- 13 adds the only links to the tracker (project page card and nav) and merges last.
- Before 03 is useful: `GIT_TOKEN` already needs push access, and before 06 Pull requests read and
  write on the project repositories (006's note). Without it those calls answer `502`/`503` and
  nothing else breaks.
- No data to migrate.

## Progress

| #  | Spec | PR | Status |
| -- | ---- | -- | ------ |
| 01 | [spec](tasks/01-api-ticket-foundation/spec.md) | | not started |
| 02 | [spec](tasks/02-api-ticket-status/spec.md) | | not started |
| 03 | [spec](tasks/03-api-ticket-workspace/spec.md) | | not started |
| 04 | [spec](tasks/04-api-ticket-details/spec.md) | | not started |
| 05 | [spec](tasks/05-api-ticket-commit-push/spec.md) | | not started |
| 06 | [spec](tasks/06-api-ticket-pull/spec.md) | | not started |
| 07 | [spec](tasks/07-api-ticket-chat/spec.md) | | not started |
| 08 | [spec](tasks/08-agent-ticket-tools/spec.md) | | not started |
| 09 | [spec](tasks/09-app-ticket-list/spec.md) | | not started |
| 10 | [spec](tasks/10-app-ticket-detail/spec.md) | | not started |
| 11 | [spec](tasks/11-app-ticket-workspace/spec.md) | | not started |
| 12 | [spec](tasks/12-app-ticket-chat/spec.md) | | not started |
| 13 | [spec](tasks/13-app-tickets-nav/spec.md) | | not started |

## Changelog

- 2026-11-11 — Created.
