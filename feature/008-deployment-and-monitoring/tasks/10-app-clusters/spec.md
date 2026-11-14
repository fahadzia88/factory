---
feature: 008-deployment-and-monitoring
task: 10-app-clusters
layer: app
status: draft
approved:
branch: feat/008-deployment-and-monitoring/10-app-clusters
base: feat/008-deployment-and-monitoring/09-api-project-control
covers: [AC-1, AC-2, AC-3, AC-5, AC-31]
---

# 10 — Clusters list, add, edit, remove

## Goal

A Clusters page where users add, edit and remove clusters and see each one's health and mode. The route exists but nothing links to it yet.

## Contract

Consumes `GET/POST /api/clusters`, `PUT/DELETE /api/clusters/{id}` and `GET /api/clusters/{id}/status` from the breakdown. Errors are `ProblemDetail`; field errors come as `errors` (field → message); a `503` with no `controllerStatus` means the token isn't configured.

## Files

- `app/app/lib/clusters.ts` — new: types (`Cluster`, `ClusterStatus`, plus the controller types used by later tasks may be added there), `listClusters`, `createCluster`, `updateCluster`, `deleteCluster`, `getClusterStatus(id, refresh?)`, and `clusterErrorMessage(error)`: returns `detail` as is for any `ApiError` with a problem body (including 404 and 502), "Your session has expired. Sign in again." for a real 401, and falls back to `apiErrorMessage`. Also `clusterFieldErrors(error)` reading `errors`.
- `app/app/routes/cluster-list.tsx` — new: `clientLoader` (with `clientLoader.hydrate = true`, `HydrateFallback`) returns the clusters; statuses load per card with `<Suspense>` and `<Await errorElement>` so one slow cluster doesn't block the page. Page: `PageHeader` "Clusters" with an "Add cluster" button; `Empty` state with an "Add cluster" action (AC-1); a `Card` per cluster with name, address, a health badge (Ready, Not ready, Unreachable with the message), the Kubernetes version and a "Read-only" or "Read-write" badge; edit and remove buttons.
- `app/app/components/clusters/cluster-form.tsx` — new: dialog form (name, address) shared by add and edit; shows field errors next to inputs (AC-2); other errors in an `Alert`.
- `app/app/components/clusters/cluster-status-badge.tsx` — new: badges; status uses text as well as color.
- `app/app/components/clusters/remove-cluster-dialog.tsx` — new: confirmation that says linked projects will be unlinked (AC-3).
- `app/app/routes.ts` — change: add `route("clusters", "routes/cluster-list.tsx")` inside the app layout. Do not touch `NAV_ITEMS` (task 17).

## Steps

1. Write `lib/clusters.ts`; reuse `api()`.
2. Write the list route; a configuration `503` shows a single `Alert` ("CONTROLLER_API_TOKEN isn't configured on the server…", as given by the api's `detail`) in place of the status badges (AC-5).
3. Write the form dialog, the remove dialog and wire `clientAction` with an `intent` (`create`, `update`, `delete`) like `project-detail.tsx`; revalidate on success and `toast.success`.
4. Phone width: cards stack; address uses `break-all`; no horizontal scroll (AC-31).

## Tests

- The app has no test runner; verify by hand against the running app: empty state (AC-1), add with valid and invalid input (AC-2), edit and remove with the linked-projects warning (AC-3), unset token message (AC-5), 375 px width (AC-31).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/lib/clusters.ts app/routes/cluster-list.tsx app/components/clusters app/routes.ts`
- (`app/CLAUDE.md` names the container `skeleton-app`; the compose file names it `factory-app`.)

## Out of scope

- Cluster page contents (task 11), nav item (task 17).
