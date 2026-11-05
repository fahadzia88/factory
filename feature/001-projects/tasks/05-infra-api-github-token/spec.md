---
feature: 001-projects
task: 05-infra-api-github-token
layer: infra
status: approved
approved: 2026-11-05
branch: feat/001-projects/05-infra-api-github-token
base: feat/001-projects/04-api-project-list
covers: [AC-8, AC-9]
---

# 05 — Pass the API its GitHub token

## Goal

Give the `api` container its own read-only GitHub token for the repository check, documented for
setup. It's separate from the `GITHUB_TOKEN` in the root `.env`, which has write access for the
feature workflow.

## Contract

From task-breakdown.md, "api → GitHub":

- Config: `app.github.token=${GITHUB_TOKEN:}`.
- A blank `app.github.token` means no call is made, and the project saves unverified.

The api reads `GITHUB_TOKEN` from its own environment. The root `.env` names it
`API_GITHUB_TOKEN`, so it can't collide with the root `.env`'s `GITHUB_TOKEN`.

## Files

- `docker-compose.yml` — change: in the `api` service's `environment`, add a commented
  `GITHUB_TOKEN: ${API_GITHUB_TOKEN:-}`.
- `README-base.md` — change: add `API_GITHUB_TOKEN` to the `.env` example in setup step 1.

## Steps

1. In `docker-compose.yml`, under `api.environment`, after `AGENT_URL`, add:

   ```yaml
   # Read-only GitHub token (Metadata: read) for checking project repositories exist; blank skips the check.
   # Separate from the root .env's GITHUB_TOKEN, which can write.
   GITHUB_TOKEN: ${API_GITHUB_TOKEN:-}
   ```
2. In `README-base.md`'s `.env` example, add a line with a comment:

   ```
   API_GITHUB_TOKEN=github_pat_...   # optional; read-only token so the API can check project repos
   ```

   Add one sentence: without it, projects save as "Repository not verified".
3. Change nothing else in compose.

## Tests

- None (configuration only). The behavior is covered by task 01's tests for a blank token and
  for a token header (AC-8, AC-9).

## Checks

- `docker-compose config` renders without errors, and `api.environment.GITHUB_TOKEN` is empty
  when `API_GITHUB_TOKEN` is unset.
- With `API_GITHUB_TOKEN` set in `.env`: `docker-compose up -d api`, start the app, and create a
  project for a real repo. It saves with `repositoryVerified: true`, and a nonexistent repo gets
  "Repository not found on GitHub." (AC-8). Stop what you started.

## Out of scope

- Creating the token itself (a release step, listed in the breakdown's rollout).
- Any change to the agent service's environment.

## Changelog

- 2026-11-05 — Back to draft: the agent no longer uses `GITHUB_TOKEN` (it moved from eve to the
  Claude Agent SDK), so the token is described as the root `.env`'s write token instead.
- 2026-11-05 — Approved again.
