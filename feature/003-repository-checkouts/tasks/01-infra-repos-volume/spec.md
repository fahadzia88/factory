---
feature: 003-repository-checkouts
task: 01-infra-repos-volume
layer: infra
status: approved
approved: 2026-11-05
branch: feat/003-repository-checkouts/01-infra-repos-volume
base: feat/003-repository-checkouts/00-docs
covers: [AC-3, AC-25]
---

# 01 — `repos` volume and the API's git token

## Goal

Declare a `repos` Docker volume, mount it at `/repos` in both `api` and `agent`, and pass the API
its git token as `GIT_TOKEN: ${API_GIT_TOKEN:-}`. Nothing uses either yet; `agent_git` stays until
task 12.

## Contract

From task-breakdown.md, "Volume (infra ↔ api ↔ agent)":

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

From "api config":

| Property | Env | Default | For |
| -------- | --- | ------- | --- |
| `app.git.token` | `GIT_TOKEN` | blank | Contents read+write token. Sent as `http.https://github.com/.extraheader` through `GIT_CONFIG_*` env per command; never on disk. |
| `app.git.root` | `REPOS_ROOT` | `/repos` | The shared volume. |

Compose maps `GIT_TOKEN: ${API_GIT_TOKEN:-}`.

`REPOS_ROOT` isn't set in compose; the property's default `/repos` matches the mount.

## Base files

`docker-compose.yml` is **`shared`** in `.claude/base-files` (an extension point). Every change
here is additive and project-specific (a new volume, two new mounts, one new env var), and the
user confirms each edit when the hook asks. No `locked` file is touched.

`README-base.md` is `locked`, so unlike feature 001's token task, `API_GIT_TOKEN` is not added to
its `.env` example. The compose comment is the documentation.

## Files

- `docker-compose.yml` — change (shared file; the user confirms the edit): `repos` volume
  declared, mounted at `/repos` in `api` and `agent`; `GIT_TOKEN` on `api` with a comment.

## Steps

1. Under `api.volumes`, after the two `~/.gradle` lines, add:

   ```yaml
      # Project repository checkouts and their worktrees (checkout package); also mounted in agent
      - repos:/repos
   ```
2. Under `api.environment`, after the existing `GITHUB_TOKEN: ${API_GITHUB_TOKEN:-}` line, add:

   ```yaml
      # Git token for cloning, fetching and pushing project repositories (checkout package).
      # Fine-grained, Contents: read and write on the project repositories. Separate from the
      # read-only API_GITHUB_TOKEN above. Sent per git command as a header, never written to disk.
      # Blank: checkout, fetch and push answer 503 git_not_configured.
      GIT_TOKEN: ${API_GIT_TOKEN:-}
   ```
3. Under `agent.volumes`, after the `agent_npx` line and before the `agent_git` comment, add:

   ```yaml
      # Same volume as api, at the same path (worktrees hold absolute paths). The api runs network
      # git and adds/removes worktrees; the agent's workflow tools work inside them.
      - repos:/repos
   ```
4. In the top-level `volumes:` list, add `repos:` (after `agent_node_modules:`).
5. Leave `agent_git` (mount, comment and declaration) and the agent's `GITHUB_TOKEN` comment as
   they are; task 12 removes or rewords them.
6. `gradle:jdk25` already has git (2.53.0, checked with `docker exec factory-api git --version`),
   so no image change. The agent image (`node:24`) has git 2.39.5, which reads worktrees made by
   2.53 as long as the API doesn't turn on relative worktree paths or reftable (task 02's
   concern; noted here so nobody changes the agent image to "match").
7. **User action, not done by the coordinator:** tell the user to add `API_GIT_TOKEN` to the root
   `.env` on its own line (the file's last line currently has no trailing newline, so start with a
   newline). The token: a fine-grained personal access token whose repository access covers every
   project repository, including `fahadzia88/factory` for task 11, with **Contents: read and
   write** (Metadata: read comes with it). The coordinator never reads or writes `.env` values.
   It's needed to verify task 03, not this task.

## Tests

- None (configuration only). The volume is used by tasks 03–07 and 11; AC-3 is fully checked
  after task 03 (a ready checkout listed from both containers).

## Checks

Compose is the standalone `docker-compose`. From the repo root:

- `docker-compose config --quiet` exits 0.
- `docker-compose config | grep -n -e '/repos' -e 'GIT_TOKEN' -e '^  repos:'` shows `/repos` under
  `api` and `agent`, `GIT_TOKEN` under `api`, and the `repos` volume (AC-25, first half). Don't
  paste the rendered config anywhere: it contains `.env` values.
- `docker-compose up -d api agent` (recreates both), then:
  - `docker volume ls | grep factory_repos` lists the volume;
  - `docker exec factory-api ls -la /repos` and `docker exec factory-agent ls -la /repos` both
    succeed on an empty folder (AC-3 support);
  - shared and writable by both: `docker exec factory-api touch /repos/.probe`,
    `docker exec factory-agent ls /repos/.probe`, `docker exec factory-agent rm /repos/.probe`,
    `docker exec factory-api ls -A /repos` is empty again;
  - token presence without printing it:
    `docker exec factory-api sh -c 'test -n "$GIT_TOKEN" && echo set || echo blank'` says `blank`
    until the user adds `API_GIT_TOKEN`, then `set` after `docker-compose up -d api`.
- `agent_git` is still mounted: `docker exec factory-agent ls /git` succeeds.

## Out of scope

- Removing `agent_git` and rewording the agent's `GITHUB_TOKEN` comment (task 12).
- `app.git.*` properties in `application.properties` and the code that reads `GIT_TOKEN`
  (task 02).
- Creating the token or editing `.env` (the user does it).
