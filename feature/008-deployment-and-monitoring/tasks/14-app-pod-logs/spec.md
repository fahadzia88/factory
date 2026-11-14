---
feature: 008-deployment-and-monitoring
task: 14-app-pod-logs
layer: app
status: draft
approved:
branch: feat/008-deployment-and-monitoring/14-app-pod-logs
base: feat/008-deployment-and-monitoring/13-app-deployment-tab
covers: [AC-13, AC-15, AC-16, AC-31]
---

# 14 — Pod detail and logs

## Goal

A pod page with containers, events and a logs viewer: tail, since, previous, timestamps and live follow.

## Contract

Consumes `GET /api/projects/{id}/pods/{namespace}/{name}` and `.../logs?container&tail&since&previous&follow&timestamps` (`text/plain`, `X-Container`). Follow streams until the browser aborts.

## Files

- `app/app/lib/deployment.ts` — change: `getPod`, `PodDetail`; `fetchLogs(projectId, namespace, pod, options, signal)` returns `{ container, text }` for non-follow; `followLogs(..., { onChunk, signal })` reads `response.body` with a `TextDecoder` and a bearer header.
- `app/app/routes/project-pod.tsx` — new: route `projects/:id/deployment/pods/:namespace/:name`. Loader: pod detail. Shows phase, ready, node, IP, owner, a `ProblemAlert`, containers table (state, reason, message, restarts, exit code, last termination), init containers, and events (AC-15, AC-13).
- `app/app/components/deployment/log-viewer.tsx` — new: controls for container (select from the pod's containers), tail (default 200, max 10000, input validated), since (seconds, optional), previous (checkbox, hint "use for a crashed container"), timestamps, follow. "Load" fetches; follow streams appended lines, auto-scroll that pauses when the user scrolls up. Shows the container used from `X-Container`. A `<pre>` in a scrollable container with `whitespace-pre-wrap` and `break-all`. Aborts the request on unmount, on toggling follow off or changing options (AC-16). Errors (400, 404, controller messages) show next to the controls unchanged.
- `app/app/components/deployment/containers-table.tsx` — new.
- `app/app/components/deployment/workload-detail.tsx` — change: pod rows link to the pod page.
- `app/app/routes.ts` — change: add the pod route.

## Steps

1. Add the lib functions; `followLogs` caps the buffer kept in state (last 5000 lines) so a long follow doesn't grow without bound.
2. Write the viewer with an `AbortController` per load or follow, cleaned up in an effect.
3. Write the pod page; for a 404 show the controller's message and a link back to the list.
4. Phone width: controls wrap; the log area scrolls inside itself (AC-31).

## Tests

- Manual: containers and events (AC-15); tail, since, timestamps change what's shown and `X-Container` is displayed (AC-16); `previous` on a CrashLoopBackOff pod shows the last crash (AC-16); follow streams new lines and closing the page or toggling follow stops the stream (check the controller sees the request end) (AC-16); problem shown with its message (AC-13); 375 px (AC-31).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the files changed

## Out of scope

- Restart pod action (task 15).
