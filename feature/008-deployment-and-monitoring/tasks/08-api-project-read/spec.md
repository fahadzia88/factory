---
feature: 008-deployment-and-monitoring
task: 08-api-project-read
layer: api
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/08-api-project-read
base: feat/008-deployment-and-monitoring/07-api-project-link
covers: [AC-11, AC-14, AC-15, AC-16, AC-17, AC-18, AC-27, AC-30]
---

# 08 — Project read endpoints

## Goal

Workloads, pods, logs, events, the live pod stream and the link's status, restricted to the project's namespaces.

## Contract

From the breakdown, "Project deployment (read)":

- `GET /api/projects/{id}/workloads?namespace` → `Workload[]`
- `GET /api/projects/{id}/workloads/{namespace}/{kind}/{name}` → `Workload & { pods, events }`
- `GET /api/projects/{id}/pods?namespace&problems` → `Pod[]`
- `GET /api/projects/{id}/pods/{namespace}/{name}` → `Pod & { events }`
- `GET /api/projects/{id}/pods/{namespace}/{name}/logs?container&tail&since&previous&follow&timestamps` → `text/plain`; `X-Container` passed on
- `GET /api/projects/{id}/events?namespace&type&limit` → `Event[]`
- `GET /api/projects/{id}/watch` → `text/event-stream`
- `GET /api/projects/{id}/status` → `{ readOnly, reachable, error? }`
- Not linked → `404` "Project isn't linked to a cluster." A namespace outside the project → `403`, the controller isn't called. Other errors as in the breakdown table.

## Files

Package `com.toptier.custom.cluster`:

- `service/ProjectScope.kt` — new: resolves `(project id) → (cluster address, namespaces)` and `requireNamespace(ns)`; throws `ProjectNamespaceException` (403) and `ProjectNotLinkedException` (404).
- `service/ProjectDeployment.kt` — new: reads. With no `namespace`, calls the controller once per project namespace and merges (workloads sorted by namespace and name, pods likewise, events newest first and cut to `limit`). Converts `kind` with `WorkloadKind.fromPath`. A namespace that fails with 404 or 403 in a merge fails the whole call with that message (not silently skipped).
- `service/ProjectWatch.kt` — new: runs `HomelabWatch` over the project's namespaces: one upstream watch per namespace, merged into one sink (a lock around the sink); frames whose pod namespace isn't the project's are dropped (defence in depth); after a `Reset` from any namespace, a single `reset` event is emitted and other namespaces' upstreams are also re-subscribed so state is consistent (simplest: when one namespace reconnects, the service restarts all, closing all upstreams and emitting one `reset`). Cancelling closes every upstream.
- `controller/ProjectDeploymentController.kt` — new: the routes. Logs and watch write on the request thread like `FeatureChatController` (`HttpServletResponse` output, `Content-Type: text/event-stream` or `text/plain; charset=UTF-8`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`, flush after each frame or chunk). A write failure (`IOException`) means the browser left: cancel and close the upstream. Headers go out only after the upstream accepted the request, so earlier errors stay `ProblemDetail`. Implements `HomelabController`.
- `dto/ProjectDeploymentDtos.kt` — new: `ProjectClusterStatus`, `WorkloadDetail`, `PodDetail`.

## Steps

1. Write `ProjectScope` and its exceptions plus handler lines in `HomelabExceptionHandler`/`ClusterExceptionHandler`.
2. Write the read service; limit default 100, max 1000.
3. Write the controller routes; logs use `openLogs` from task 05 and close it in `finally`.
4. Write `ProjectWatch` and the SSE route; an `event: error` frame is passed on untouched; upstream failures after the headers were sent are written as `event: error` with `{ message }` and the stream ends.
5. `status` uses `ClusterStatusService` with the project's cluster; it never throws for an unreachable cluster.

## Tests

- `ProjectDeploymentTests` — merge and sorting across namespaces (AC-11); a foreign namespace → 403 with no upstream call (AC-27); `deployments`/`statefulsets` mapping (AC-14); events filtered by type, newest first, limit (AC-18); pod detail contains events (AC-15).
- `ProjectWatchTests` — frames from other namespaces dropped; a reconnect yields one `reset` (AC-17); cancel closes all upstreams.
- `ProjectDeploymentControllerTests` — `@WebMvcTest`: no bearer → 401, service not called (AC-30); logs pass the options and `X-Container` (AC-16); closing the output aborts the upstream; unlinked → 404; the SSE route's content type and unbuffered headers (AC-17).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`

## Out of scope

- Changes (task 09), the UI.
