---
feature: 008-deployment-and-monitoring
task: 13-app-deployment-tab
layer: app
status: draft
approved:
branch: feat/008-deployment-and-monitoring/13-app-deployment-tab
base: feat/008-deployment-and-monitoring/12-app-project-link
covers: [AC-11, AC-12, AC-13, AC-14, AC-17, AC-18, AC-27, AC-28, AC-31]
---

# 13 — Deployment tab: workloads, detail, events, live pods

## Goal

The project's Deployment views for workloads, a workload detail with pods and events, an events list, and live pod updates. The tab is not shown in the project page until task 17.

## Contract

Consumes the project read endpoints from the breakdown, including `GET /api/projects/{id}/watch` (`event: pod`, `event: error`, `event: reset`, comments). The bearer header can't go on an `EventSource`, so the app reads the stream with `fetch` and the stored password.

## Files

- `app/app/lib/deployment.ts` — new: types (`Workload`, `Pod`, `Problem`, `Container`, `WorkloadDetail`, `PodDetail`, `DeploymentEvent`), `kindPath(kind)` (`Deployment` → `deployments`, `StatefulSet` → `statefulsets`), `listWorkloads`, `getWorkload`, `listPods`, `listEvents`, `getProjectStatus`.
- `app/app/lib/sse.ts` — new: `readSse(response, onFrame)` parsing `event`/`data` frames from `response.body` (comments ignored), and `watchPods(projectId, { onPod, onReset, onError, signal })`: `fetch` with `Authorization`, reconnect with backoff (1 s doubling to 30 s, reset after a frame arrives), aborts with the `AbortSignal`; no reconnect on 401/403/404.
- `app/app/hooks/use-pod-watch.ts` — new: keeps a `Map` keyed by `namespace/name`; `ADDED`/`MODIFIED` upsert, `DELETED` removes, `reset` clears; returns `{ pods, state: "connecting" | "live" | "reconnecting" | "error", message }`; aborts on unmount (AC-17).
- `app/app/routes/project-deployment.tsx` — new: route `projects/:id/deployment` (and a nested detail route `projects/:id/deployment/workloads/:namespace/:kind/:name`). Loader: project link (not linked → `Empty` explaining how to link, AC-12), project status, workloads. Workload list: table/cards with status badge, replicas (ready/desired), images, and problem count; a pod `problem` is shown prominently with `reason` and `message` (AC-13).
- `app/app/components/deployment/workload-list.tsx` — new: list; the status updates live from the pod map.
- `app/app/components/deployment/workload-detail.tsx` — new: header with status and replicas; pods table with ready, restarts, node, phase and problem; events list. Kind is mapped back with `kindPath` for all calls (AC-14).
- `app/app/components/deployment/problem-alert.tsx` — new: `reason` as a bold title, `message` below; text plus icon, not only color (AC-13).
- `app/app/components/deployment/events-list.tsx` — new: events for the project with namespace and Warning/Normal filter, newest first (AC-18).
- `app/app/components/deployment/namespace-filter.tsx` — new: `Select` of the project's namespaces.
- `app/app/routes.ts` — change: add the deployment routes.

## Steps

1. Write `sse.ts` and `use-pod-watch.ts` first; the watch starts when the workloads view mounts and stops when it unmounts.
2. Write the list with the loader and the live map; for a 404 on a workload, show the controller's message and call `revalidate` to refresh the list (AC-28); for a 403 show the message and disable the controls (AC-27).
3. Write the detail view and the events view.
4. Show a small "Live" / "Reconnecting…" indicator with text.
5. Status badges: Ready, Progressing, Unavailable, Failed, Scaled down; each has text.
6. Phone width: tables become stacked cards or scroll inside their container (AC-31).

## Tests

- Manual: only the project's namespaces appear (AC-11); a pod in `ImagePullBackOff` shows reason and message (AC-13); StatefulSet and Deployment detail load (AC-14); changing a pod in the cluster updates the list without reload (AC-17); stopping the controller shows "Reconnecting…", and starting it again recovers with no duplicate rows (AC-17); event filters (AC-18); a namespace outside the project returns the message (AC-27); deleting a workload then opening it refreshes the list (AC-28); unlinked project shows the empty state (AC-12); 375 px (AC-31).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the files changed

## Out of scope

- Pod detail and logs (task 14), change actions (task 15), apply (task 16), the tab link (task 17).
