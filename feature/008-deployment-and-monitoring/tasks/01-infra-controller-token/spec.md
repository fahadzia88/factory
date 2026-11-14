---
feature: 008-deployment-and-monitoring
task: 01-infra-controller-token
layer: infra
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/01-infra-controller-token
base: feat/008-deployment-and-monitoring/00-docs
covers: [AC-4]
---

# 01 — Pass `CONTROLLER_API_TOKEN` to the api container

## Goal

The api container receives the controller bearer token from the root `.env`, and nothing else does. Needs the user's go-ahead because `docker-compose.yml` is a shared file.

## Contract

No endpoint. The api reads the variable `CONTROLLER_API_TOKEN` (task 03 binds it to `app.homelab.token`).

## Files

- `docker-compose.yml` — change: in the `api` service `environment`, add `CONTROLLER_API_TOKEN: ${CONTROLLER_API_TOKEN:-}` with a comment: bearer token of the HomeLab Controller, effectively cluster-admin; server side only; blank makes cluster screens answer "not configured". Do not add it to `app` or `agent`.
- `.env.example` — change only if the file exists: add `CONTROLLER_API_TOKEN=` with the same comment. If there is no such file, skip it.

## Steps

1. Ask the user to confirm the edit to `docker-compose.yml` (shared file).
2. Add the variable to the `api` service only, next to the other tokens, additive.
3. Check that `app` and `agent` services do not list it.

## Tests

- None (configuration). Verified by reading the compose file: the variable is under `api` only (AC-4).

## Checks

- `docker-compose config` parses without error (when Docker is available).

## Out of scope

- Reading the variable in the api (task 03) and docs (task 18).
