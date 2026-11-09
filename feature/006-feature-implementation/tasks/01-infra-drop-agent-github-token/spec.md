---
feature: 006-feature-implementation
task: 01-infra-drop-agent-github-token
layer: infra
status: draft
approved:
branch: feat/006-feature-implementation/01-infra-drop-agent-github-token
base: feat/006-feature-implementation/00-docs
covers: [AC-12]
---

# 01 — Drop the agent's GitHub token

## Goal

The agent service stops receiving GitHub credentials. The API's `GIT_TOKEN` comment says it also
needs Pull requests read and write, because the API now opens and reads pull requests.

## Contract

No interface. From task-breakdown.md ("Design decisions"): PR create, retarget and read use the
API's `GIT_TOKEN`; the agent never gets GitHub credentials.

## Files

- `docker-compose.yml` — change (`shared` base file; additive/removing only, the user confirms):
  - `agent` service: remove `GITHUB_TOKEN` and `GITHUB_REPO` and the comment above them ("Feature-workflow
    subagents: the pull request REST API only …").
  - `api` service: update the comment above `GIT_TOKEN`: "Fine-grained, Contents: read and write and
    Pull requests: read and write on the project repositories. Used for git (clone, fetch, push) and
    for opening, retargeting and reading pull requests." Keep the variable name `API_GIT_TOKEN`.

## Steps

1. Edit the two blocks above; change nothing else (keep `GIT_USER_NAME` / `GIT_USER_EMAIL`, which
   commits use).
2. Search the repo for other uses of the agent's `GITHUB_TOKEN`: `agent/src/**` must have none. The
   unwired eve code in `agent/agent/lib/github.ts` still reads it; it is not run by the service and
   is left alone.
3. If a root `.env` or docs mention `GITHUB_TOKEN` as an agent setting, tell the user; do not edit
   `.env` (it is the user's).

## Tests

- None automated. Manual (AC-12): `docker-compose config` shows no `GITHUB_TOKEN` or `GITHUB_REPO`
  under `agent`; `docker-compose up -d agent` starts; `npm run dev` logs no missing-variable error;
  `GET /agent/v1/health` (or the app's chat) still answers.

## Checks

- `docker-compose config` parses.
- `docker exec factory-agent printenv | grep -c GITHUB` prints `0`.

## Out of scope

- Giving `API_GIT_TOKEN` the new scope: the user does that on GitHub (see Rollout).
- Removing the unwired eve subagents.
