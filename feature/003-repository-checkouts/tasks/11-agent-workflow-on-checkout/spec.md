---
feature: 003-repository-checkouts
task: 11-agent-workflow-on-checkout
layer: agent
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/11-agent-workflow-on-checkout
base: feat/003-repository-checkouts/10-app-worktree-actions
covers: [AC-9, AC-22, AC-23, AC-24]
---

# 11 — Workflow tools on the API checkout

## Goal

Rewrite `agent/agent/lib/repo.ts` so the feature-workflow tools work in the factory project's
checkout on the shared `/repos` volume: the API resolves the checkout, creates worktrees, fetches
and pushes; the agent reads, writes, commits and rebases locally with no git credentials. Every
exported function keeps its signature and behaviour, so `tools.ts`, `docs.ts`, `checks.ts`,
`github.ts` and the subagent `tools/` files don't change.

## Contract

From task-breakdown.md. Every route needs `Authorization: Bearer <API_TOKEN>` (`401` otherwise)
and returns errors as `ProblemDetail` (`application/problem+json`) with a `code` property the app
and agent switch on, and a `detail` they can show as is.

"Volume (infra ↔ api ↔ agent)":

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

"Checkout (JSON)":

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

"Routes" (the rows this task calls):

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST /api/projects/{id}/checkout/fetch` | — | `200` Checkout | `409 checkout_not_ready`, `409 project_archived`, `502 git_remote_error`, `503 git_not_configured`, `504 git_timeout` |
| `POST /api/projects/{id}/checkout/worktrees` | `{ "branch": "feat/x", "base": "main" }` (`base` optional) | `201` Worktree | `400 invalid_branch` (fails `check-ref-format --branch`), `400 base_not_found`, `409 branch_checked_out` (in a worktree already, the main one included), `409 checkout_not_ready`, `409 project_archived`, `502`/`503`/`504` as fetch |
| `POST /api/projects/{id}/checkout/worktrees/{worktreeId}/push` | `{ "force": false }` | `200` `{ "branch": "…", "head": "a1b2c3d" }` | `404 worktree_not_found`, `409 push_default_branch`, `409 push_rejected` (non-fast-forward, or the lease failed), `409 worktree_missing`, `409 project_archived`, `502`/`503`/`504` as fetch |
| `GET /api/checkouts?repositoryUrl=<url>` | exact match on the project's `repositoryUrl` | `200` Checkout | `404 project_not_found`, `404 checkout_not_found` |

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
- Push: `git push -u origin <branch>:<branch>`, or `--force-with-lease=<branch>` when
  `force`. No fetch before; a fetch after. Pushing the default branch is refused before git runs.
- Git failures map as: auth rejected or other remote error → `502 git_remote_error` with a plain
  message (no stderr containing URLs or headers); timeout → `504 git_timeout`. Any text taken
  from git output is scrubbed of the token.

"api → agent: none. agent → api":

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

## Before you start: the state of `agent/agent/`

Since the base merge `c28dd5c` ("agent moved from eve to nodejs"), the workflow code under
`agent/agent/` isn't wired into the running agent:

- `src/agent.ts` registers only `listProjects` and `getProject`; nothing imports `agent/agent/`.
- `lib/tools.ts` and the subagents import `eve` / `eve/tools`, `repo.ts` imports `simple-git`,
  `github.ts` imports `@octokit/rest`. None of these is in `agent/package.json`, and the
  container's `node_modules` has none of them.
- `tsconfig.json` (locked) includes only `src/**/*.ts`, so `npm run typecheck` never sees
  `agent/agent/`. Its sibling imports have no `.ts` extension (eve's bundler style).

The user chose to port `repo.ts` only: this task doesn't port the subagents to the Claude Agent
SDK (that's its own feature), and they stay unwired. It makes
`repo.ts` correct and runnable on its own (Node built-ins plus `src/api.ts`, no `simple-git`), so
it can be typechecked and driven directly; see Tests. AC-22 and AC-23 talk about "a workflow
subagent"; until the subagents run again, they're verified by calling the same `repo.ts`
functions the tools call.

## Base files

- `agent/README.md` is **`locked`** and this repo has a `base` remote, so the hook blocks the
  edit. Its "Feature workflow subagents" section is project-only (it already differs from the
  skeleton by ~57 lines). The user has agreed to add `agent/README.md` to
  `.claude/base-overrides` themselves. That entry must exist before step 9: check the file, and if
  it isn't there yet, stop and ask the user to add it. Never edit `.claude/base-overrides` or get
  around the hook.
- `agent/tsconfig.json` and `agent/AGENTS.md` are locked; don't touch them.
- `agent/src/api.ts` and everything under `agent/agent/` are project code.
- `docker-compose.yml` (shared) is not changed here. The `agent_git` comment that cites
  `repo.ts` is deleted with the mount in task 12.

## Files

- `agent/agent/lib/repo.ts` — change: rewritten internals (API client calls, local git through
  `node:child_process`, paths on `/repos`); same exports except `GIT_ROOT` (no importer).
- `agent/src/api.ts` — change: `ApiError` gains an optional `code` (the problem's `code`);
  new `apiPost<T>(path, body, timeoutMs)`.
- `agent/agent/lib/workflow.md` — change: "Where the work happens" says the agent works in the
  factory project's checkout on `/repos`, with worktrees created through the API.
- `agent/README.md` — change (locked; the user's `.claude/base-overrides` entry must exist first):
  "Where the work happens" and the token guardrail.

Unchanged, and why they keep working:

| File | Uses from `repo.ts` | Effect of this task |
| ---- | ------------------- | ------------------- |
| `lib/tools.ts` | `parseFeatureBranch`, `writablePrefix`, `FeatureBranch`, `checkout`, `pull`, `push`, `rebase`, `rebaseControl`, `status`, `commit`, `listFiles`, `readText`, `tryReadText`, `search`, `writeText`, `deleteFile`, `cap` | Same signatures and return shapes. `checkout`, `pull`, `push`, `rebase` change internally (API calls); the reads change where they run (`/repos/<id>` instead of `/git/repo.git`). |
| `lib/docs.ts` | `docsBranch`, `featureBranches`, `listFiles`, `tryReadText` | `featureBranches` reads the clone's refs. Otherwise none. |
| `lib/checks.ts` | `cap`, `changedFiles`, `worktreeDir` | `worktreeDir` returns `/repos/<id>.worktrees/<branch __>`; checks run there. |
| `lib/github.ts` | `githubToken`, `parseFeatureBranch`, `repoSlug` | None: `githubToken()` and `repoSlug()` are kept as they are. |
| `subagents/**/tools/*.ts` | re-export `lib/tools.ts` | None. |

## Steps

1. **API helper (`src/api.ts`).**
   - `ApiError` gets `readonly code?: string`, set from the problem body's `code` when present
     (extend `problemDetail` into a small parser returning `{ detail, code }`). The constructor
     keeps `(status, message)` as its first two parameters; `code` is an optional third.
   - Add `apiPost<T>(path: string, body: unknown, timeoutMs = 10_000): Promise<T>`: same base URL,
     bearer token and error handling as `apiGet`, `content-type: application/json`, and returns
     `undefined as T` for an empty body. `apiGet` keeps its signature; it may share a private
     `request()` with `apiPost`.
   - `src/tools/*` and `projects.test.ts` keep working unchanged.

2. **Local git without `simple-git` (`repo.ts`).** Replace `simpleGit` with a private
   `git(cwd, args, extraEnv?)` built on `execFile` from `node:child_process` (promisified,
   `maxBuffer` 64 MB). It resolves to stdout, and on a non-zero exit rejects with an `Error` whose
   message is stderr trimmed (or the exit code when stderr is empty), plus `exitCode` on the
   error. The env is fixed and contains no secrets:
   - `PATH`, `HOME`, `GIT_TERMINAL_PROMPT=0`;
   - `GIT_AUTHOR_NAME`, `GIT_COMMITTER_NAME` from `GIT_USER_NAME` (default `factory agent`) and
     `GIT_AUTHOR_EMAIL`, `GIT_COMMITTER_EMAIL` from `GIT_USER_EMAIL` (default
     `factory-agent@users.noreply.github.com`), on every command, so commits and rebases get them;
   - `GIT_EDITOR=true` when the caller asks (rebase, rebase control).
   - No `GIT_CONFIG_*`, no auth header, no token: an accidental network command fails instead of
     prompting. Delete `gitEnv`'s header code. Nothing is written to the clone's config (no
     `git config user.*`).
   - `repo.ts`'s own imports use `.ts` extensions (`../../src/api.ts`) so Node can run it directly.

3. **Resolve the checkout.** Private module state: `let current: ApiCheckout | undefined` and
   `let lastFetch = 0`. Name the API types `ApiCheckout` and `ApiWorktree` (the exported type
   `Checkout` is already taken by `checkout()`'s result and stays).
   - `repoUrl()` = `https://github.com/${owner}/${repo}` from `repoSlug()`.
   - `notReady()` builds the contract's error exactly:
     `Register the factory repository (https://github.com/<GITHUB_REPO>) as a project and check it out on its project page first.`
     with `<GITHUB_REPO>` replaced by `owner/repo`.
   - `resolve(force = false)`: unless cached and not `force`,
     `apiGet<ApiCheckout>("/api/checkouts", { repositoryUrl: repoUrl() })`. An `ApiError` with
     status `404` → `throw notReady()`. A `state` other than `"ready"` → clear the cache and
     `throw notReady()`. Other errors propagate with their message.
   - `withCheckout(fn)`: runs `fn(current)`; if it fails with `404` (`project_not_found`,
     `checkout_not_found`, `worktree_not_found`) or `409 checkout_not_ready`, re-resolve once with
     `force` and retry once. A second failure of the same kind → `notReady()` for project and
     checkout errors, the API's `detail` otherwise. Every other error (`400 invalid_request`,
     `409 project_archived`, `500 git_error`, `502`/`503`/`504`) throws an `Error` with the API's
     `detail`; never retry them.
   - Paths come from the resolved checkout: `clone = current.path`; worktrees under
     `${current.path}.worktrees/`; `treeDir(branch)` = that folder plus
     `branch.replaceAll("/", "__")`. Prefer an `ApiWorktree.path` when the entry is known.

4. **`ensureRepo()`** (still private): `resolve()`, then if more than 15 s since `lastFetch`,
   `apiPost<ApiCheckout>(`/api/projects/${projectId}/checkout/fetch`, {}, 150_000)` through
   `withCheckout`; store the returned checkout as `current` and set `lastFetch`. No clone, no
   `mkdir`, no `git init` anywhere. Remove `GIT_ROOT`, `BARE`, `TREES`, `AGENT_GIT_ROOT` and
   `GIT_REMOTE_URL`.

5. **Exported functions** (signatures unchanged; `serial()` still wraps each one):
   - `repoSlug()`, `githubToken()`, `parseFeatureBranch()`, `docsBranch()`, `writablePrefix()`,
     `cleanPath()`, `cap()`, `serial()`: unchanged.
   - `hasWorktree(branch)` (sync): `false` until a checkout is resolved; otherwise
     `existsSync(<treeDir>/.git)`.
   - `worktreeDir(branch)` (sync) and the private `worktreePath`: same check, same error
     ("No worktree for <branch>. Call git_checkout first.") when nothing is resolved yet or the
     folder is absent. `runChecks` always runs after `git_checkout`, so the cache is set.
   - `refExists(ref)` (private): `git rev-parse --verify --quiet <ref>^{commit}` in the clone;
     any failure → `false`.
   - `checkout(branch, base?)`, after `parseFeatureBranch` and `ensureRepo()`:
     1. Find the `worktrees` entry for `branch`; if absent, re-`resolve(true)` once to pick up
        one made from the project page. Found and not `missing` → `{ branch, created: false,
        head }` (`head` = local `rev-parse --short HEAD`). Found and `missing` → throw "The
        worktree for <branch> is missing on disk. Remove it on the factory project's page, then
        check it out again."
     2. Keep today's rules, checked against the clone's refs (after `ensureRepo()`'s fetch)
        before calling the API, in the same order the API uses: `refs/heads/<branch>` exists, or
        else `refs/remotes/origin/<branch>` exists → `POST …/worktrees` with `{ branch }` (the API
        uses the local branch first, then tracks origin, as today's `checkout()` does). Else with
        `base`: `refs/heads/<base>` or `refs/remotes/origin/<base>` must exist, else throw "Base
        branch <base> doesn't exist locally or on origin."; then `POST` with `{ branch, base }`.
        Else (no base) throw "<branch> doesn't exist yet. Pass a base to create it." Never `POST`
        without a base for a branch that doesn't exist, so the API's default-branch fallback never
        applies. One difference stays: for the base, the API prefers `origin/<base>` over a local
        `<base>` (today's code prefers local). Task branches are pushed before the next one is
        stacked, so they normally match; note it in the PR. A `400 base_not_found` that slips past
        the pre-check (a race) surfaces as the API's `detail`.
     3. `POST` timeout 150 s. On `409 branch_checked_out` (a race with the page), re-resolve and
        return the existing entry as step 1. Add the new `ApiWorktree` to `current.worktrees`.
     4. Return `{ branch, created: true, base, head }`.
   - `pull(branch)`: `ensureRepo()` (API fetch, throttled), `worktreePath`, then as today: no
     `refs/remotes/origin/<branch>` → `"no upstream yet"`, else local `merge --ff-only
     origin/<branch>`.
   - `push(branch, forceWithLease)`: `parseFeatureBranch`, `resolve()`, `worktreePath` (the
     folder must exist), find the branch's `ApiWorktree` (re-resolve once if not cached; none →
     "No worktree for <branch>. Call git_checkout first."), then
     `apiPost(`…/worktrees/${id}/push`, { force: forceWithLease }, 150_000)` through
     `withCheckout`. Errors surface as the API's `detail` (`push_rejected`, `worktree_missing`,
     `push_default_branch`). Set `lastFetch = Date.now()` (the API fetched after pushing).
     Return the response's `head`.
   - `rebase(branch, upstream, onto?)`: `ensureRepo()` (API fetch), then the same local rebase as
     today, with `GIT_EDITOR=true`; conflict handling unchanged.
   - `rebaseControl`, `status`, `commit`, `changedFiles`, `writeText`, `deleteFile`: unchanged
     logic on the worktree folder; `resolve()` first so paths exist. `commit` gets its author
     and committer from the env in step 2.
   - Reads (`listFiles`, `readText`, `tryReadText`, `search`): private `source(ref)` resolves,
     then: `ref !== "main"` and `hasWorktree(ref)` → the worktree folder (uncommitted work, as
     today); else `ensureRepo()` and the first of `refs/remotes/origin/<ref>`, `refs/heads/<ref>`
     that exists, read with `ls-tree` / `show` / `grep` run in the clone (`/repos/<id>`). Keep
     the "Branch <ref> not found locally or on origin." error.
   - `search`: `git grep` exits 1 with empty stderr when nothing matches → return `""` (check
     `exitCode === 1`, since `execFile` rejects where `simple-git` didn't).
   - `featureBranches()`: `ensureRepo()`, then `for-each-ref --format=%(refname) refs/heads/feat
     refs/remotes/origin/feat` in the clone; same dedupe and sort.

6. **Header comment** of `repo.ts`: replace the `/git` description with: the factory project's
   checkout on the shared volume (`/repos/<project-id>` clone, `/repos/<project-id>.worktrees/…`),
   found through `GET /api/checkouts`; the API owns clone, fetch, worktree add/remove and push
   (with its own token); this file only runs local git inside feature-branch worktrees and
   read-only git in the clone, with author env per command and no credentials.

7. **`lib/workflow.md`**, "Where the work happens", first bullet: "You work in the factory
   project's checkout on the shared `/repos` volume, not on anyone's local checkout. `git_checkout`
   asks the API for the branch's worktree, so it shows on the project's page." Keep the rest.

8. **`githubToken()`** stays exported and unchanged for `github.ts`; nothing in `repo.ts` calls it
   any more.

9. **`agent/README.md`** (once the override exists; see Base files):
   - "Where the work happens": the subagents never touch your checkout. They work in the factory
     project's checkout on the shared `repos` volume (`/repos/<project-id>`), which you create by
     registering `https://github.com/<GITHUB_REPO>` as a project and clicking "Check out
     repository". Each feature branch gets a worktree under `/repos/<project-id>.worktrees/`,
     created through the API and listed on the project's page. Without a ready checkout the tools
     stop and say so.
   - Guardrails: replace "The GitHub token is sent as a per-command header and never written to
     disk." with "The agent has no git credentials: fetch and push go through the API, which
     holds its own token. `GITHUB_TOKEN` is only for the pull request API."

## Tests

There's no runnable test harness for `agent/agent/` (see "Before you start"), so verification is a
typecheck of `repo.ts` plus a driven run against the real API. Prerequisites: tasks 01–07 merged
into this stack and running, `API_GIT_TOKEN` set with Contents write on `fahadzia88/factory`, and
the project `https://github.com/fahadzia88/factory` (exactly that URL: no `.git`, no trailing
slash) registered and checked out (state Ready).

- **Typecheck `repo.ts` with `src/`.** In the container, write a throwaway config (not committed)
  and run it:

  ```bash
  docker exec factory-agent sh -c 'cat > /tmp/tsconfig.repo.json <<EOF
  { "extends": "/workspace/tsconfig.json",
    "include": ["/workspace/src/**/*.ts", "/workspace/agent/lib/repo.ts"] }
  EOF
  cd /workspace && npx tsc -p /tmp/tsconfig.repo.json'
  ```

  Passes with no errors.
- **Driven run.** A scratch script (not committed), e.g. `/tmp/repo-check.mjs` in the container,
  that imports `/workspace/agent/lib/repo.ts` and calls the exported functions; run with
  `docker exec factory-agent node /tmp/repo-check.mjs`. Use a throwaway feature number such as
  `feat/999-repo-check/00-docs` and `feat/999-repo-check/01-agent-probe`.
  - `checkout("feat/999-repo-check/00-docs", "main")` → `created: true`; the folder
    `/repos/<id>.worktrees/feat__999-repo-check__00-docs` exists, and the factory project's card
    lists the worktree (AC-22).
  - `writeText(…, "feature/999-repo-check/x.md", "hello", "feature/999-repo-check/")`, then
    `commit(…)` → `committed: true`; `git -C <worktree> log -1 --format='%an <%ae>|%cn <%ce>'`
    shows `GIT_USER_NAME`/`GIT_USER_EMAIL` for both, and `git -C /repos/<id> config --get
    user.name` is empty (AC-22).
  - Write a second, uncommitted file in the docs worktree, then
    `checkout("feat/999-repo-check/01-agent-probe", "main")`: neither file is in that worktree nor
    in the main worktree (`/repos/<id>/feature/999-repo-check/` doesn't exist) (AC-9).
  - `readText("main", "CLAUDE.md")` and `featureBranches()` work; `readText` on the docs branch
    returns the uncommitted edit when there is one.
  - `push("feat/999-repo-check/00-docs", false)` returns the head SHA; the branch is on GitHub at
    that SHA, and the API log shows the push route was called (AC-23).
  - `push("main", false)` and `push("release/x", false)` throw the "isn't a feature branch"
    error before any API call (AC-23).
  - `commit` on the docs branch, `push` it, then
    `rebase("feat/999-repo-check/01-agent-probe", "feat/999-repo-check/00-docs")` →
    `{ ok: true }` (the API fetch made `origin/feat/999-repo-check/00-docs` visible locally).
  - Without a checkout: run the script with `GITHUB_REPO=fahadzia88/no-such-repo` → every call
    above fails with exactly "Register the factory repository
    (https://github.com/fahadzia88/no-such-repo) as a project and check it out on its project
    page first.", and `ls -A /repos` and `ls -A /git` are unchanged (AC-24).
  - No credentials in the agent's git: `grep -ri extraheader /repos/<id>/.git/config` finds
    nothing.
  - Clean up: remove both worktrees on the project page (force if asked) and delete the remote
    branch on GitHub.

## Checks

- `docker exec factory-agent npm run typecheck` (covers `src/api.ts`).
- The `repo.ts` typecheck above.
- `docker exec factory-agent npm test` (the `src/tools` tests still pass with the `ApiError`
  change).

## Out of scope

- Porting the workflow subagents and `lib/tools.ts` from eve to the Claude Agent SDK, adding
  `eve`, `simple-git` or `@octokit/rest` to `package.json`, or including `agent/agent/` in
  `tsconfig.json` (locked).
- Removing `agent_git` from compose, the agent's `GITHUB_TOKEN` comment (task 12).
- Removing worktrees from the agent (no `DELETE` in the agent → api contract).
- Any API change; if a route misbehaves, report it against tasks 03–07.
