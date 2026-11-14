---
feature: 008-deployment-and-monitoring
task: 18-infra-docs
layer: infra
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/18-infra-docs
base: feat/008-deployment-and-monitoring/17-app-nav
covers: [AC-38]
---

# 18 — Docs

## Goal

Short documentation of the configuration and how to point the app at the controller.

## Contract

No code. The document describes what tasks 01 to 17 built.

## Files

- `docs/homelab-controller.md` — new (create `docs/` if it doesn't exist; ask first if the repo keeps docs elsewhere, since `README-base.md` is a base file and stays untouched):
  - what it is: clusters, project links, the Deployment tab;
  - configuration: `CONTROLLER_API_TOKEN` (api only; compose passes it; blank means cluster screens say it isn't configured; never put it in the app or agent), and the optional `app.homelab.*` properties from `application.properties` (timeout, status cache, watch backoff) with their defaults;
  - pointing the app at the controller: add a cluster under Clusters with name and address, for example `http://homelabs001:8088` (Docker) or `http://controller.controller.svc:3000` (in cluster); the address must be reachable from the api container, not the browser; one token serves every cluster;
  - linking a project: pick the cluster and register namespaces the controller already manages (the app doesn't create namespaces);
  - read-only mode, error messages (the controller's text is shown as is), and what 401 means (token mismatch: fix `CONTROLLER_API_TOKEN` and restart the api);
  - security notes: the token stays on the server; cluster addresses accept any `http(s)` URL, so only trusted users should have access;
  - short troubleshooting list: unreachable cluster, 403 for an unmanaged namespace, metrics unavailable without metrics-server.

## Steps

1. Read the final code and the properties file so the names and defaults are exact.
2. Write the document in plain, sentence-case language.
3. If an index of docs exists, add a line; otherwise leave it.

## Tests

- Manual: a reader follows the document on a clean checkout and gets a cluster added and a project linked (AC-38).

## Checks

- No code checks. Read the rendered Markdown once.

## Out of scope

- Any code change.
