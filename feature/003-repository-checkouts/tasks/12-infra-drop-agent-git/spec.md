---
feature: 003-repository-checkouts
task: 12-infra-drop-agent-git
layer: infra
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/12-infra-drop-agent-git
base: feat/003-repository-checkouts/11-agent-workflow-on-checkout
covers: [AC-25]
---

# 12 — Drop the agent's own git volume

## Goal

Remove the `agent_git` mount and volume declaration from `docker-compose.yml`, now that the
workflow tools use the API checkout on `/repos` (task 11), and reword the agent's `GITHUB_TOKEN`
comment: it's for the pull request REST API only; git goes through the API.

## Contract

From task-breakdown.md, "Volume (infra ↔ api ↔ agent)":

- Docker volume `repos`, mounted at **`/repos` in both `api` and `agent`**. The path must be the
  same in both: a worktree's `.git` file and the clone's `.git/worktrees/*/gitdir` hold absolute
  paths.
- Only the API runs git commands that touch the network or the set of worktrees. The agent runs
  local git (status, add, commit, rebase, diff, log, show, grep) inside feature-branch
  worktrees, and read-only commands in the clone.

From "agent → api":

- `GITHUB_TOKEN` stays for the pull request REST calls in `github.ts`; git no longer uses it.

## Base files

`docker-compose.yml` is **`shared`**. The `agent_git` mount, its comment, the volume declaration
and the agent's `GITHUB_TOKEN`/`GITHUB_REPO`/`GIT_USER_*` block are project additions (they aren't
in `base/main`), so removing and rewording them is a project-specific change to project lines. The
user confirms the edit when the hook asks. No `locked` file is touched.

## Files

- `docker-compose.yml` — change (shared file; the user confirms the edit): remove the
  `agent_git` mount and declaration; reword the agent's `GITHUB_TOKEN` comment.

## Steps

1. Under `agent.volumes`, delete these three lines:

   ```yaml
      # The agent's own clone of the GitHub repo and its worktrees (agent/agent/lib/repo.ts).
      # Never the host checkout, so branch switches can't touch your files or reload the agent.
      - agent_git:/git
   ```

   These lines include the compose comment that cites `repo.ts` (the breakdown first gave that
   comment to the agent task); it goes with the mount. Keep the `repos:/repos` mount and its
   comment from task 01.
2. In the top-level `volumes:` list, delete `agent_git:`.
3. `AGENT_GIT_ROOT` isn't set in `docker-compose.yml` or read anywhere after task 11
   (`grep -rn AGENT_GIT_ROOT docker-compose.yml agent/ --exclude-dir=node_modules` finds
   nothing). If it does find something, remove it from compose; report any hit in `agent/`
   instead of editing it.
4. Replace the two comment lines above the agent's `GITHUB_TOKEN: ${GITHUB_TOKEN:-}` with:

   ```yaml
      # Feature-workflow subagents: the pull request REST API only (open, list, comment, retarget;
      # no gh CLI). Git doesn't use it: fetch and push go through the API's checkout routes.
      # Fine-grained token for this repo only: Pull requests read and write.
   ```

   Leave `GITHUB_REPO` (the checkout lookup and the PR API both use it) and `GIT_USER_NAME`,
   `GIT_USER_EMAIL` (commit author and committer env) unchanged.
5. Change nothing else.

## Tests

- None (configuration only). The agent's use of `/repos` is verified in task 11.

## Checks

Compose is the standalone `docker-compose`. From the repo root:

- `docker-compose config --quiet` exits 0.
- `docker-compose config | grep -n -e agent_git -e ':/git'` prints nothing, and
  `docker-compose config | grep -n '/repos'` still shows `api` and `agent` (AC-25). Don't paste
  the rendered config anywhere: it contains `.env` values.
- `grep -n -e agent_git -e AGENT_GIT_ROOT docker-compose.yml` prints nothing.
- `docker-compose up -d agent` (recreates it), then:
  - `docker exec factory-agent ls /git` fails with "No such file or directory" (AC-25);
  - `docker exec factory-agent ls /repos` and `docker exec factory-api ls /repos` list the same
    entries (AC-25);
  - the agent is healthy: `curl -s localhost:3001/agent/v1/health` answers `{"ok":true,…}`.
- Compose doesn't delete the old volume. `docker volume ls | grep factory_agent_git` still lists
  it; that's expected.

## Rollout note for the user

- Anything unpushed in the old `agent_git` volume is lost when it's deleted; feature branches
  live on GitHub, so this is expected (requirements, non-goals).
- Optional, once the stack is up without it: `docker volume rm factory_agent_git`. It fails while
  a container still uses the volume; recreate `agent` first.
- `agent/README.md` no longer mentions `agent_git` after task 11. If `grep -n agent_git
  agent/README.md` still finds it, report it; don't edit the README here.

## Out of scope

- Changing the `repos` volume or the API's `GIT_TOKEN` (task 01).
- Removing `GITHUB_TOKEN` from the agent: the pull request API still needs it.
- Deleting the `factory_agent_git` volume (the user decides).
