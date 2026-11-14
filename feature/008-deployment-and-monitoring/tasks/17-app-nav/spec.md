---
feature: 008-deployment-and-monitoring
task: 17-app-nav
layer: app
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/17-app-nav
base: feat/008-deployment-and-monitoring/16-app-apply
covers: [AC-1, AC-12, AC-31]
---

# 17 — Expose the feature

## Goal

Make everything reachable: a Clusters entry in the header nav and a Deployment tab on the project page.

## Contract

No new endpoints. Uses the routes added in tasks 10 to 16.

## Files

- `app/app/routes/app-layout.tsx` — change: add `{ to: "/clusters", label: "Clusters" }` to `NAV_ITEMS` (match the existing item shape; read the file first).
- `app/app/routes/project-detail.tsx` — change: add a "Deployment" `TabsTrigger` with a lucide icon (for example `RocketIcon`) and a `TabsContent` holding the cluster link card from task 12 and a summary with links to the workload list, events and Apply; an unlinked project shows the empty state that explains how to link a cluster (AC-12). Archived projects show the link card read-only.
- `app/app/components/deployment/deployment-tab.tsx` — new: the tab body; wraps the link card, and when linked a short workload status summary with "Open deployment" and "Apply" links.

## Steps

1. Read `app-layout.tsx` and match its nav code.
2. Add the nav item and the tab.
3. Check the empty states: no clusters (AC-1) and unlinked project (AC-12).
4. Phone width: the tab list scrolls inside its container as the existing tabs do (AC-31).

## Tests

- Manual: Clusters appears in the nav and its empty state shows (AC-1); a project without a link shows how to link (AC-12); the full path works from the project page to a workload, a pod, logs and Apply; 375 px (AC-31). A last pass through AC-1 to AC-36 on the running app.

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the files changed

## Out of scope

- Docs (task 18).
