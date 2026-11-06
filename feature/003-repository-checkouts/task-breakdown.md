---
feature: 003-repository-checkouts
status: in-progress
approved: 2026-11-05
---

# Repository checkouts: task breakdown

Requirements: [requirements.md](requirements.md)

## Contracts

The interfaces between layers, fixed before the tasks that depend on them. Every route needs
`Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as `ProblemDetail`
(`application/problem+json`) with a `code` property the app and agent switch on, and a `detail`
they can show as is.

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

### Routes

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET /api/projects/{id}/checkout` | — | `200` Checkout | `404 project_not_found`, `404 checkout_not_found` (the app shows "Check out repository") |
| `POST /api/projects/{id}/checkout` | — | `202` Checkout (`cloning`); the clone runs in the background | `409 project_archived`, `409 checkout_exists` (cloning or ready; a `failed` one is replaced, which is Retry), `503 git_not_configured` |
| `POST /api/projects/{id}/checkout/fetch` | — | `200` Checkout | `409 checkout_not_ready`, `409 project_archived`, `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout` |
| `GET /api/projects/{id}/checkout/unsaved` | — | `200` `[Unsaved work]`, only worktrees with something; `[]` when there's no checkout | `404 project_not_found` |
| `POST /api/projects/{id}/checkout/worktrees` | `{ "branch": "feat/x", "base": "main" }` (`base` optional) | `201` Worktree | `400 invalid_branch` (fails `check-ref-format --branch`), `400 base_not_found`, `409 branch_checked_out` (in a worktree already, the main one included), `409 checkout_not_ready`, `409 project_archived`, `502`/`503`/`504` as fetch |
| `DELETE /api/projects/{id}/checkout/worktrees/{worktreeId}` | `?force=true` optional | `204` | `404 worktree_not_found`, `409 main_worktree`, `409 worktree_unsaved` (problem has `unsaved`: Unsaved work), `409 project_archived` |
| `POST /api/projects/{id}/checkout/worktrees/{worktreeId}/push` | `{ "force": false }` | `200` `{ "branch": "…", "head": "a1b2c3d" }` | `404 worktree_not_found`, `409 push_default_branch`, `409 push_rejected` (non-fast-forward, or the lease failed), `409 worktree_missing`, `409 project_archived`, `502`/`503`/`504` as fetch |
| `GET /api/checkouts?repositoryUrl=<url>` | exact match on the project's `repositoryUrl` | `200` Checkout | `404 project_not_found`, `404 checkout_not_found` |

On every route above, in addition to the listed errors:

- `404 project_not_found` when no project has the id; on the `…/checkout/fetch` and
  `…/worktrees…` routes, `404 checkout_not_found` when the project has no checkout.
- `400 invalid_request` for a non-numeric id, an unreadable JSON body, or a missing required
  parameter or field.
- `500 git_error` when a local git command (no network) fails unexpectedly; `detail` is a plain
  message with no git output.

- Fetch: `git fetch --prune origin`, then `git merge --ff-only origin/<default>` in the main
  worktree when `git status --porcelain` is empty. Sets `lastFetchedAt`.
- Add worktree: fetch first. If a local branch exists: `worktree add <dir> <branch>`; else if
  `origin/<branch>` exists: `worktree add --track -b <branch>`; else from `origin/<base>` (or a
  local `<base>`), default the checkout's default branch, with `--no-track`.
- Folder names replace `/` with `__`, so `a/b` and `a__b` share a folder: adding the second
  answers `409 branch_checked_out`.
- Push: `git push -u origin <branch>:<branch>`, or `--force-with-lease=<branch>` when
  `force`. No fetch before; a fetch after. Pushing the default branch is refused before git runs.
- Remove: `git worktree remove --force <dir>` (after the unsaved check, unless `force`), then
  `git branch -D` only if the branch is on origin. For a `missing` worktree:
  `git worktree prune` and delete the record.
- Git failures map as: auth rejected or other remote error → `502 git_remote_error` with a plain
  message (no stderr containing URLs or headers); timeout → `504 git_timeout`. Any text taken
  from git output is scrubbed of the token.
- One git operation at a time per checkout (an in-process lock keyed by checkout id).

### Archive (api ↔ app, extends feature 001)

- `POST /api/projects/{id}/archive` is unchanged for callers, but now also deletes the
  checkout: both folders and both tables' rows, whatever their state. It doesn't ask for force;
  the app warns first using `GET …/checkout/unsaved`.
- Restore doesn't touch checkouts.

### api → agent: none. agent → api

The workflow tools in `agent/agent/lib/` call the routes above with `API_URL` and `API_TOKEN`:

- Resolve: `GET /api/checkouts?repositoryUrl=https://github.com/<GITHUB_REPO>`. A `404`, or a
  state other than `ready` → the tool fails with "Register the factory repository
  (https://github.com/<GITHUB_REPO>) as a project and check it out on its project page first."
- Checkout of a branch: use the matching entry in `worktrees`, or `POST …/worktrees` with
  `{ branch, base }`.
- Fetch (instead of `git fetch` in `ensureRepo`, still at most every 15 s): `POST …/fetch`.
- Push: `POST …/worktrees/{id}/push` with `force` = `forceWithLease`.
- Reads of `main` or a branch without a worktree use the clone's refs
  (`refs/remotes/origin/<ref>`) in `/repos/<project-id>`.
- Commits set `GIT_AUTHOR_*`/`GIT_COMMITTER_*` from `GIT_USER_NAME`/`GIT_USER_EMAIL` per
  command; the agent writes nothing to the clone's config.
- `GITHUB_TOKEN` stays for the pull request REST calls in `github.ts`; git no longer uses it.

### app → api

Through `lib/api.ts`. A new `lib/checkouts.ts` has the types and calls. Errors show the
problem's `detail`; `5xx` codes get "Try again".

## Tasks

Stack order, bottom first. Each task is one PR based on the row above it.

| #  | Layer | Task | Depends on | ACs | Branch |
| -- | ----- | ---- | ---------- | --- | ------ |
| 00 | docs  | Feature folder | — | — | `feat/003-repository-checkouts/00-docs` |
| 01 | infra | `repos` volume mounted at `/repos` in `api` and `agent`; `GIT_TOKEN: ${API_GIT_TOKEN:-}` on `api`, with a comment naming the token's scope (`gradle:jdk25` already ships git 2.53). `agent_git` stays for now. | 00 | AC-3, AC-25 | `feat/003-repository-checkouts/01-infra-repos-volume` |
| 02 | api   | `checkout` package foundation: `GitProperties`, `GitRunner` (process exec, `GIT_CONFIG_*` auth header, timeouts, token scrubbing, typed failures), path builder from project id and branch, `RepositoryCheckout` and `RepositoryWorktree` entities and repositories. Tests: `GitRunner` against a local bare repo on a temp dir, `@DataJpaTest`. | 01 | AC-27 (scrubbing), AC-28 (timeout) | `feat/003-repository-checkouts/02-api-git-runner` |
| 03 | api   | `CheckoutService` + controller + exception handler: `GET`/`POST …/checkout` (background clone on an executor, `CLONING`→`READY`/`FAILED`, retry replaces failed, startup reset), per-checkout lock, the shared error codes. Service tests against local repos, `@WebMvcTest`. | 02 | AC-2–AC-5, AC-26–AC-29 | `feat/003-repository-checkouts/03-api-checkout-clone` |
| 04 | api   | Fetch: `POST …/checkout/fetch` with the main fast-forward and `lastFetchedAt`, and the `@Scheduled` fetch of every ready checkout. Tests. | 03 | AC-17, AC-18, AC-28 | `feat/003-repository-checkouts/04-api-checkout-fetch` |
| 05 | api   | Worktrees: add (local / track / from base, validation, `__` collision), remove (unsaved check, force, main refused, missing → prune), unsaved-work computation, `missing` and `head` in the response. Tests against local repos. | 04 | AC-6–AC-13, AC-29 | `feat/003-repository-checkouts/05-api-worktrees` |
| 06 | api   | Push: `POST …/worktrees/{id}/push` (default branch refused, `--force-with-lease` on request, no fetch before, fetch after, rejection → `push_rejected`). Tests against a local bare remote. | 05 | AC-14–AC-16, AC-28 | `feat/003-repository-checkouts/06-api-worktree-push` |
| 07 | api   | `GET …/checkout/unsaved`; archive deletes the checkout (`ProjectArchivedEvent` from `ProjectService`, handled in `checkout`); archived projects refuse checkout routes; `GET /api/checkouts?repositoryUrl=`. Tests. | 06 | AC-19–AC-22 (api), AC-24 (api) | `feat/003-repository-checkouts/07-api-archive-lookup` |
| 08 | app   | `lib/checkouts.ts` (types, calls, errors). Archive dialog on `/projects/:id` loads unsaved work when it opens and lists it per worktree (branch, files, commits) above the confirm button; nothing extra when there's none. | 07 | AC-20 | `feat/003-repository-checkouts/08-app-archive-warning` |
| 09 | app   | "Repository checkout" card on `/projects/:id`: no checkout → button; cloning (polls `GET` every 3 s while cloning), failed with reason and Retry; ready with last fetched, Fetch, worktree list (Main first; branch, path, head, created; "Missing" badge); archived → no actions; error with Try again without breaking the page. Shows the card. | 08 | AC-1, AC-2, AC-5, AC-17, AC-21, AC-28, AC-30 | `feat/003-repository-checkouts/09-app-checkout-card` |
| 10 | app   | Worktree actions on the card: "Add worktree" form (branch, optional base, field errors), Push (with a "Force with lease" retry after `push_rejected`), Remove (confirm; on `worktree_unsaved` show the list and confirm again with force; none on Main; clears Missing). Completes the feature in the app. | 09 | AC-6–AC-8, AC-10–AC-16, AC-30 | `feat/003-repository-checkouts/10-app-worktree-actions` |
| 11 | agent | `agent/agent/lib/repo.ts` onto the API checkout: resolve via `/api/checkouts`, worktrees via the API, fetch and push via the API, reads from the clone's refs, local commit/rebase with author env, no git network auth, `execFile` instead of `simple-git`; same exported functions and branch rules; clear failure without a checkout. Update `agent/README.md` (the user adds it to `.claude/base-overrides` first). The workflow subagents stay unwired (see Rollout). | 07 | AC-9, AC-22–AC-24 | `feat/003-repository-checkouts/11-agent-workflow-on-checkout` |
| 12 | infra | Remove the `agent_git` mount and volume; reword the agent's `GITHUB_TOKEN` comment (PR API only; git goes through the API). | 11 | AC-25 | `feat/003-repository-checkouts/12-infra-drop-agent-git` |

Infra tasks (01, 12) are done by the coordinator with the user's go-ahead. The rest are done in
this Claude Code session unless the user names other sessions at dispatch. 11 is stacked on 10
to keep one stack, though it only needs 07.

## AC coverage

| AC    | Tasks          | Verified by |
| ----- | -------------- | ----------- |
| AC-1  | 09             | Manual: a project with no checkout shows the card and the button |
| AC-2  | 03, 09         | `CheckoutServiceTests` clone of a local repo → `READY`, default branch, one main worktree row; `CheckoutControllerTests` `202`; manual Cloning → Ready |
| AC-3  | 01, 03         | Manual: `docker exec factory-api ls /repos/<id>` and `docker exec factory-agent ls /repos/<id>` match; `git -C /repos/<id> rev-parse HEAD` equals GitHub's default-branch head |
| AC-4  | 03             | `CheckoutControllerTests` second `POST` → `409 checkout_exists` |
| AC-5  | 03, 09         | `CheckoutServiceTests` bad remote → `FAILED` with message, retry replaces it, startup reset; clone timeout; manual Failed + Retry |
| AC-6  | 05, 10         | `WorktreeServiceTests` tracks an existing remote branch, folder name with `__`, row saved; manual |
| AC-7  | 05, 10         | `WorktreeServiceTests` new branch from base and from default; manual |
| AC-8  | 05, 10         | `WorktreeServiceTests`/`WorktreeControllerTests` invalid name, missing base, duplicate, main's branch, `__` collision → `400`/`409`; manual form errors |
| AC-9  | 05, 11         | `WorktreeServiceTests` a file written in one worktree is absent from the other and main; scratch script against `repo.ts` |
| AC-10 | 05, 10         | `WorktreeServiceTests` clean remove deletes folder and row; manual |
| AC-11 | 05, 10         | `WorktreeServiceTests` dirty and unpushed → `409` with lists, force removes; manual two-step confirm |
| AC-12 | 05, 10         | `WorktreeControllerTests` main → `409 main_worktree`; manual no Remove on Main |
| AC-13 | 05, 09, 10     | `WorktreeServiceTests` deleted folder → `missing: true`, remove prunes the row; manual badge and Remove |
| AC-14 | 06, 10         | `WorktreePushTests` push to a local bare remote sets upstream at head; manual |
| AC-15 | 06             | `WorktreeControllerTests` default branch → `409 push_default_branch` |
| AC-16 | 06, 10         | `WorktreePushTests` rebased branch: plain push → `push_rejected`; force with lease succeeds; remote moved after last fetch → `push_rejected`; manual force retry |
| AC-17 | 04, 09         | `CheckoutFetchTests` fetch fast-forwards clean main, leaves dirty main and other worktrees, sets `lastFetchedAt`; manual Fetch |
| AC-18 | 04             | `CheckoutFetchTests` scheduled method fetches every ready checkout, a failing one keeps its state; log has no token |
| AC-19 | 07             | `CheckoutArchiveTests` archive removes both folders and rows |
| AC-20 | 07, 08         | `CheckoutArchiveTests` unsaved lists per worktree; manual dialog lists them, cancel keeps everything, confirm deletes |
| AC-21 | 07, 09         | `CheckoutControllerTests` archived → `409 project_archived`; manual no actions, restore shows the button |
| AC-22 | 11             | Scratch script calling `repo.ts` `checkout` creates a worktree visible on the factory project's card; its `commit` lands in `/repos/<id>.worktrees/…`; separate tsconfig typecheck of `agent/agent/lib` |
| AC-23 | 11             | Scratch script `push` goes through the API and the branch is on GitHub; `main` is still refused |
| AC-24 | 07, 11         | `CheckoutControllerTests` lookup `404`s; scratch script with no factory project fails with the message and nothing is cloned |
| AC-25 | 01, 12         | Manual: `docker-compose config` shows `/repos` in `api` and `agent`, no `agent_git`; `docker volume ls` after `docker-compose up` |
| AC-26 | 03, 05, 06, 07 | `@WebMvcTest` `401` without a token on each controller |
| AC-27 | 02, 03         | `GitRunnerTests` token scrubbed from failure text, not in `.git/config`; `CheckoutControllerTests` blank token → `503 git_not_configured` |
| AC-28 | 02, 04, 06, 09 | `GitRunnerTests` timeout → typed failure; controller tests `502`/`504` mapping; manual error with Try again, page still works |
| AC-29 | 03, 05         | Controller tests unknown project, checkout, worktree → `404`; bad id → `400 invalid_request` |
| AC-30 | 09, 10         | Manual at 375 px: no horizontal scroll, paths and branches wrap |

## Rollout

- 01 adds the volume and env; nothing uses them yet. Add `API_GIT_TOKEN` (fine-grained,
  Contents: read and write on the project repositories) to the root `.env` before verifying 03.
- 02–07 are additive API routes; nothing calls them until 08. 07's archive change deletes
  nothing when a project has no checkout, which is every project until 09.
- 08 changes only the archive dialog, and shows nothing new while no checkouts exist.
- 09 shows the card with checkout and fetch; 10 adds the worktree actions and completes the
  feature in the app.
- 11 moves the agent's workflow library onto the checkout. The workflow subagents in
  `agent/agent/**` aren't wired into the running agent today (their `eve`, `simple-git` and
  `@octokit` packages aren't in `package.json`, and `tsconfig.json` only covers `src/`), so 11 is
  checked with a separate typecheck and a scratch script; wiring the subagents is a later
  feature. Before verifying it, register `https://github.com/fahadzia88/factory` as a project and
  check it out, and add `agent/README.md` to `.claude/base-overrides`.
- 12 removes `agent_git`. Anything unpushed on that volume is lost; feature branches live on
  GitHub, so this is expected. Run `docker volume rm factory_agent_git` afterwards if wanted.
- Database: two new tables from the JPA entities (`ddl-auto=update`). No change to `project`.
- Feature 002's stack (#16–#21) also edits `project-detail.tsx`. 003 is based on `main`;
  whichever merges second rebases its app tasks.

## Progress

Docs PR: [#23](https://github.com/fahadzia88/factory/pull/23), the base of the stack. Merge bottom-up.

| #  | Spec | PR | Status |
| -- | ---- | -- | ------ |
| 01 | [spec](tasks/01-infra-repos-volume/spec.md) | [#24](https://github.com/fahadzia88/factory/pull/24) | pr open |
| 02 | [spec](tasks/02-api-git-runner/spec.md) | [#25](https://github.com/fahadzia88/factory/pull/25) | pr open |
| 03 | [spec](tasks/03-api-checkout-clone/spec.md) | [#26](https://github.com/fahadzia88/factory/pull/26) | pr open |
| 04 | [spec](tasks/04-api-checkout-fetch/spec.md) | [#27](https://github.com/fahadzia88/factory/pull/27) | pr open |
| 05 | [spec](tasks/05-api-worktrees/spec.md) | | not started |
| 06 | [spec](tasks/06-api-worktree-push/spec.md) | | not started |
| 07 | [spec](tasks/07-api-archive-lookup/spec.md) | | not started |
| 08 | [spec](tasks/08-app-archive-warning/spec.md) | | not started |
| 09 | [spec](tasks/09-app-checkout-card/spec.md) | | not started |
| 10 | [spec](tasks/10-app-worktree-actions/spec.md) | | not started |
| 11 | [spec](tasks/11-agent-workflow-on-checkout/spec.md) | | not started |
| 12 | [spec](tasks/12-infra-drop-agent-git/spec.md) | | not started |

## Changelog

- 2026-11-05 — Created.
- 2026-11-05 — Approved.
- 2026-11-05 — Back to draft: contract gaps filled (extra 404s, `invalid_request`, `git_error`, local branch first, `__` collision); api 03 and 04 split into 03–06 (12 tasks); 11 ports `repo.ts` only, the subagents stay unwired.
- 2026-11-05 — Approved again.
- 2026-11-05 — Task specs written and approved.
- 2026-11-05 — Docs PR #23 open; in progress.
