---
feature: 008-deployment-and-monitoring
task: 11-app-cluster-page
layer: app
status: draft
approved:
branch: feat/008-deployment-and-monitoring/11-app-cluster-page
base: feat/008-deployment-and-monitoring/10-app-clusters
covers: [AC-6, AC-7, AC-8, AC-9, AC-26, AC-31]
---

# 11 — Cluster page

## Goal

A page per cluster with its overview, nodes, metrics and recent events.

## Contract

Consumes `GET /api/clusters/{id}/status`, `/overview`, `/nodes`, `/metrics`, `/events?namespace&type&limit` from the breakdown. `503` on metrics means usage is hidden; `502` means "cluster unreachable"; a `502` with `controllerStatus: 401` is a configuration error.

## Files

- `app/app/lib/clusters.ts` — change: types `Overview`, `Node`, `Usage`, `Metrics`, `ClusterEvent`; `getOverview`, `getNodes`, `getMetrics`, `getClusterEvents`; `isConfigurationError(error)` (503 without `controllerStatus`, or 502 with `controllerStatus: 401`); `isUnreachable(error)` (502 otherwise).
- `app/app/routes/cluster-detail.tsx` — new: route `clusters/:id`. `clientLoader` loads the status first; overview, nodes, metrics and events load as promises rendered with `<Suspense>` and `<Await errorElement>` (not `null`), so metrics failing doesn't block the rest (AC-8).
- `app/app/components/clusters/cluster-overview.tsx` — new: cards for nodes ready/total, workloads ready/total, pods by phase, warnings in the last hour; lists of workloads that aren't ready (status badge, replicas) and pod problems (`reason` and `message`, namespace/name) (AC-7).
- `app/app/components/clusters/node-table.tsx` — new: nodes with ready, roles, pressure, addresses, allocatable, and usage with CPU and memory percent when present; text next to any bar (AC-7, AC-8).
- `app/app/components/clusters/cluster-events.tsx` — new: events list with a Warning/All filter and namespace select; newest first; refetches on change.
- `app/app/components/clusters/cluster-problem.tsx` — new: shows the "cluster unreachable" alert with the controller's message and a "Retrying…" state: reload with backoff (2 s, 4 s, 8 s up to 30 s) while the page is open (AC-9); for a configuration error shows the message, with no retry (AC-6).
- `app/app/routes.ts` — change: add `route("clusters/:id", "routes/cluster-detail.tsx")`.
- `app/app/routes/cluster-list.tsx` — change: cluster cards link to the detail page.

## Steps

1. Add types and helpers.
2. Build the loader and the sections; metrics 503 renders a small `Alert` with the controller's message and nothing else changes (AC-8).
3. Add the backoff retry in `cluster-problem.tsx` with a `useEffect` timer cleaned up on unmount.
4. The page shows a read-only badge from the status (AC-26 shows the mode; change controls come with later tasks).
5. Tables scroll inside their `Card` at phone width; no page-wide horizontal scroll (AC-31).

## Tests

- Manual against the running app: healthy cluster overview and nodes (AC-7); cluster without metrics-server hides usage and shows the message (AC-8); stopped controller shows "cluster unreachable" and retries (AC-9); wrong token shows a configuration message and doesn't retry (AC-6); mode badge (AC-26); 375 px (AC-31).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the files changed

## Out of scope

- Project-scoped views, nav item.
