---
feature: 003-repository-checkouts
status: approved
approved: 2026-11-05
---

# Repository checkouts

## Problem

Projects record which GitHub repository each codebase lives in (feature 001), but the factory has
no working copy of any of them. To build features on a project, the code has to be on disk, and
parallel feature work needs more than one branch checked out at a time. Today the only clone is
the agent's private copy of the factory repo (`agent_git` volume at `/git`, from
`agent/agent/lib/repo.ts`). The API can't see it and the database knows nothing about it.

## Users

- **People who sign in to the app** (shared password, no individual accounts). They want a
  project's repository checked out, a worktree per branch being worked on, and to see what is
  checked out where.
- **The agent's feature-workflow tools.** They need a working directory per feature branch, so
  parallel tasks don't step on each other, on a volume the API also sees.
- **The API.** It runs the git commands that create, update and remove checkouts and worktrees,
  and records them in the database.

## Scope

- **Shared volume.** A new Docker volume, mounted at `/repos` in both the `api` and `agent`
  containers.
- **Layout on the volume**, one folder per project, named by project id:
  - `/repos/<project-id>/`: a clone of the project's repository; its base is the **main
    worktree**, on the repository's default branch;
  - `/repos/<project-id>.worktrees/<branch>/`: each additional worktree, one per branch, beside
    the main worktree rather than inside it (`/` in a branch name becomes `__`).
- **Database.** One record per checkout and one per worktree, the main worktree included. The
  API is the only writer.
- **Who runs git.** The API runs every git command that touches the network or the set of
  worktrees: clone, fetch, worktree add and remove, push, delete. It authenticates with a new
  token, `API_GIT_TOKEN`.
- **Checkout (on request).** A project has at most one checkout.
  - A "Check out repository" action on the project detail page, and an API endpoint, clone the
    repository.
  - Cloning runs in the background. The checkout shows as "Cloning", then "Ready" or "Failed"
    with the reason. A failed checkout can be retried.
  - Nothing is cloned when a project is created.
- **Worktrees.**
  - Added from the project detail page or the API, with a branch name and an optional base
    branch.
  - If the branch exists on GitHub, the worktree tracks it. Otherwise it is created from the base
    branch (default: the repository's default branch).
  - The API fetches before adding a worktree.
  - A branch can be in only one worktree. The main worktree's branch can't be added again.
  - A worktree is removed from the detail page or the API. If it has uncommitted changes or
    commits not on GitHub, the API refuses with `409` and lists them; the app asks for
    confirmation and retries with force. The main worktree can't be removed.
- **Push.**
  - A Push action per worktree (detail page and API) pushes its branch to GitHub and sets the
    upstream.
  - Pushing the repository's default branch is refused.
  - Force push is only `--force-with-lease`, and only when the caller asks for it.
  - The API doesn't fetch before pushing, so a lease compares against the last fetch; it
    fetches after a push to update the remote-tracking branch.
- **Fetch.**
  - A Fetch action (detail page and API) runs `git fetch --prune` on the checkout. It
    fast-forwards the main worktree when that worktree is clean, and records the last-fetched
    time.
  - A scheduled job does the same for every ready checkout every 15 minutes (configurable). It
    never changes worktrees other than the main one.
- **Archive and restore.**
  - Archiving a project deletes its checkout: the folders and the database records.
  - If any worktree has uncommitted changes or commits not on GitHub, the archive confirmation
    lists them first; confirming deletes anyway.
  - Restoring a project doesn't clone again; the user checks out again when needed.
  - Archived projects have no checkout actions.
- **Project detail page.** A "Repository checkout" card:
  - with no checkout, a "Check out repository" button;
  - while cloning or after a failure, the state and, for a failure, the reason and a Retry
    button;
  - when ready, the last-fetched time, a Fetch button, and the worktrees. The main worktree comes
    first and is marked "Main". Each worktree shows its branch, its path on the volume, the short
    SHA and subject of its head commit, and its created time, with Push and Remove actions (no
    Remove on the main one);
  - an "Add worktree" form with a branch name and an optional base branch.
- **Agent feature-workflow tools** move onto the shared volume, replacing `agent_git`:
  - The workflow repository is the project whose repository URL is
    `https://github.com/<GITHUB_REPO>`.
  - The tools ask the API to create a feature branch's worktree, fetch and push.
  - Inside a worktree, they still read and write files, commit and rebase directly on the volume.
  - Their branch rules are unchanged: only `feat/*` branches, and only the folder the branch
    owns.
  - If no project matches `GITHUB_REPO`, or it has no ready checkout, the tools fail with a
    message saying to register the factory repository as a project and check it out.
- **Compose.**
  - Declare the new volume and mount it in `api` and `agent`.
  - Give the API `API_GIT_TOKEN`.
  - Remove the `agent_git` mount and volume declaration.

## Non-goals

- Checkouts of repositories that aren't projects, or more than one checkout per project.
- Deleting a checkout without archiving the project.
- Editing files, committing or rebasing from the app, and API endpoints for those. Only the
  agent's workflow tools change files in a worktree.
- Agent chat tools (Assistant) for checkouts or worktrees.
- Showing uncommitted-change counts or ahead/behind counts in the app.
- Scheduled fetches that change worktrees other than the main one, or that pull with merges or
  rebases.
- Opening pull requests from the app (the agent's workflow already does that with its own
  `GITHUB_TOKEN`).
- Keeping or migrating anything from the old `agent_git` volume; branches there live on GitHub.

## Assumptions

- `API_GIT_TOKEN` is a fine-grained GitHub token with Contents read and write on the project
  repositories. It is separate from `API_GITHUB_TOKEN` (read-only, features 001 and 002). The
  agent keeps `GITHUB_TOKEN` for pull request API calls; git no longer uses it.
- Git sends the token as a per-command HTTP header (as `repo.ts` does today). It is never in a
  remote URL, `.git/config`, a response or a log.
- The `api` and `agent` containers both run as root, so files one writes the other can change.
  If that changes, ownership has to be settled in infra.
- The `api` image has `git`. If `gradle:jdk25` doesn't, infra installs it.
- The API runs one git command at a time per checkout. The agent changes files and commits only
  inside feature-branch worktrees, never in the main worktree. Git's own locks cover a fetch
  running at the same time as an agent commit.
- Branch names must pass `git check-ref-format --branch`. Callers never pass paths; the API
  builds every path from the project id and branch.
- If a recorded worktree's folder is missing from disk, the card shows it as "Missing", and
  Remove clears the record.
- Repositories are small enough that a clone finishes in minutes. A clone that runs longer than
  10 minutes fails.

## Constraints

- **api:** follow `api/CLAUDE.md`:
  - a feature package under `com.toptier.custom`;
  - bearer auth on every route;
  - `ProblemDetail` errors: bad input → 400, missing → 404, conflict → 409, GitHub failure → 502,
    timeout → 504;
  - typed `@ConfigurationProperties` for the root path, token and fetch interval;
  - `Clock`, and `@Scheduled` gated by `app.scheduling.enabled`;
  - schema from JPA entities;
  - tests against local bare repos, never GitHub.
- **Project archive** (feature 001) gains the delete-checkout step and its warning. The rest of
  archive and restore behaves as before.
- **agent:** follow `agent/AGENTS.md`. The workflow tools in `agent/agent/` keep their contract
  for the subagents that use them. Only where the repo lives and who does the network calls
  change.
- **app:** follow `app/CLAUDE.md`: a card on the existing project detail page with `Card`,
  `Alert` and `Empty`, usable at phone width.
- **infra:** `docker-compose.yml` and `.env` changes are done by the coordinator.

## Non-functional requirements

- **Security:** the git token stays in the API container and is never written to disk or
  returned. Branch names are validated, and no caller-supplied path is used.
- **Performance:**
  - adding, removing or pushing a worktree, and fetching, answer within the API's request time
    for normal repositories;
  - clone is asynchronous;
  - a GitHub network call times out after 2 minutes (→ `504`).
- **Layout:** the checkout card works at 375 px; long paths and branch names wrap.

## Open questions

- None.

## Acceptance criteria

### Checkout

- **AC-1** — Given a non-archived project with no checkout, when the user opens its detail page,
  then a "Repository checkout" card shows a "Check out repository" button.
- **AC-2** — Given that card, when the user clicks "Check out repository", then the card shows
  "Cloning". Once the clone finishes, it shows "Ready" with the main worktree on the repository's
  default branch at `/repos/<project-id>/`. The database has one checkout record and one main
  worktree record for the project.
- **AC-3** — Given a ready checkout, when either the `api` or the `agent` container lists
  `/repos/<project-id>/`, then it sees the same files, and the main worktree's head is the default
  branch's head on GitHub.
- **AC-4** — Given a project that already has a checkout, when a checkout is requested again
  through the API, then it is refused with `409` and nothing changes.
- **AC-5** — Given the clone fails (repository not found, token rejected or missing, network
  error, or more than 10 minutes), when the card loads, then it shows "Failed" with the reason in
  plain words and a Retry button. Retry starts a fresh clone.

### Worktrees

- **AC-6** — Given a ready checkout, when the user adds a worktree for a branch that exists on
  GitHub, then the API fetches and creates `/repos/<project-id>.worktrees/<branch with / as __>/`
  tracking that branch. The card lists it with its branch, path, head commit (short SHA and
  subject) and created time, and the database has its record.
- **AC-7** — Given a branch that doesn't exist on GitHub, when the user adds a worktree with a
  base branch (or none), then the branch is created from the base (or the default branch) and the
  worktree is listed as in AC-6.
- **AC-8** — Given a branch name that isn't a valid git branch, a base branch that doesn't exist,
  a branch already in a worktree, or the main worktree's branch, when the user adds a worktree,
  then it is refused (`400` for invalid input, `409` for a branch already checked out). The form
  shows the reason and nothing is created.
- **AC-9** — Given several worktrees of one checkout, when files change in one of them, then the
  others and the main worktree are unchanged.
- **AC-10** — Given a clean worktree whose commits are all on GitHub, when the user removes it,
  then its folder and record are gone and the card no longer lists it.
- **AC-11** — Given a worktree with uncommitted changes or commits not on GitHub, when removal is
  requested without force, then the API answers `409` listing the changed files and unpushed
  commits. The app shows them and asks for confirmation; confirming removes the worktree.
- **AC-12** — Given the main worktree, then the card offers no Remove action, and an API request
  to remove it is refused with `409`.
- **AC-13** — Given a worktree record whose folder is missing from disk, when the card loads, then
  that worktree shows as "Missing", and Remove clears its record.

### Push and fetch

- **AC-14** — Given a worktree with commits not on GitHub, when the user clicks Push, then the
  branch is on GitHub at the worktree's head commit with its upstream set, and the card says it
  was pushed.
- **AC-15** — Given the main worktree or any worktree on the default branch, when a push is
  requested, then it is refused with `409`.
- **AC-16** — Given a worktree whose branch has been rebased, when a push is requested without
  force, then GitHub's rejection is reported and nothing changes. When it is requested with force,
  then it pushes with `--force-with-lease` and is refused with `409` if the branch on GitHub moved
  since the checkout's last fetch.
- **AC-17** — Given new commits on GitHub, when the user clicks Fetch, then the last-fetched time
  updates. If the main worktree is clean, it fast-forwards to the new default-branch head; other
  worktrees are unchanged.
- **AC-18** — Given scheduling is enabled and ready checkouts exist, when 15 minutes (the
  configured interval) pass, then each checkout is fetched as in AC-17 and its last-fetched time
  updates. A checkout whose fetch fails keeps its state, and the failure is logged without the
  token.

### Archive and restore

- **AC-19** — Given a project with a checkout whose worktrees are all clean and pushed, when the
  user archives it and confirms, then `/repos/<project-id>/`, `/repos/<project-id>.worktrees/`
  and their database records are gone.
- **AC-20** — Given a project with a worktree that has uncommitted changes or unpushed commits,
  when the user archives it, then the confirmation lists them per worktree before anything is
  deleted. Confirming archives the project and deletes the checkout; cancelling changes nothing.
- **AC-21** — Given an archived project, when its detail page opens, then the checkout card has no
  actions, and checkout API requests for it are refused with `409`. When the project is restored,
  then the card shows "Check out repository" again.

### Agent workflow tools

- **AC-22** — Given a project whose repository is `https://github.com/<GITHUB_REPO>` and has a
  ready checkout, when a workflow subagent checks out a feature branch, then the worktree is
  created through the API (recorded, visible on the project's card) under
  `/repos/<project-id>.worktrees/`. The subagent's file writes, commits and rebases happen in that
  folder.
- **AC-23** — Given a workflow subagent has committed on a feature branch, when it pushes, then
  the push goes through the API's push endpoint and the branch is on GitHub. Pushing `main` or a
  non-`feat/*` branch is still refused by the tool.
- **AC-24** — Given no project matches `GITHUB_REPO`, or its checkout isn't ready, when a workflow
  tool is used, then it fails with a message saying to register the factory repository as a
  project and check it out. No clone is made anywhere else.
- **AC-25** — Given the new compose file, when the stack starts, then `/repos` is mounted in `api`
  and `agent`, and no `agent_git` volume or `/git` mount exists.

### Errors, security and layout

- **AC-26** — Given a request without a valid bearer token, when it calls any checkout or
  worktree endpoint, then it gets `401`.
- **AC-27** — Given `API_GIT_TOKEN` isn't set, when a checkout, fetch or push is requested, then
  it fails with a message saying the git token isn't configured. The token never appears in any
  response, log line, remote URL or file on the volume.
- **AC-28** — Given GitHub is unreachable or doesn't answer within 2 minutes, when a fetch,
  worktree add or push runs, then the API answers `502` or `504`, the card shows the error with a
  way to retry, and the rest of the project page still works.
- **AC-29** — Given an id that matches no project, checkout or worktree, when it is requested,
  then the API answers `404`.
- **AC-30** — Given a 375 px wide screen, when the user uses the checkout card and its forms, then
  the page doesn't scroll horizontally, and long paths and branch names wrap.

## Changelog

- 2026-11-05 — Created.
- 2026-11-05 — Open questions settled with the user; acceptance criteria written.
- 2026-11-05 — Approved.
- 2026-11-05 — Back to draft: push no longer fetches first, so `--force-with-lease` checks against the last fetch (AC-16).
- 2026-11-05 — Approved again.
