---
feature: 008-deployment-and-monitoring
task: 06-api-cluster-endpoints
layer: api
status: draft
approved:
branch: feat/008-deployment-and-monitoring/06-api-cluster-endpoints
base: feat/008-deployment-and-monitoring/05-api-client-streams
covers: [AC-7, AC-8, AC-9, AC-26, AC-30]
---

# 06 — Cluster endpoints: status and cluster-wide monitoring

## Goal

Expose a cluster's status and mode, overview, nodes, metrics, namespaces and events to the UI.

## Contract

From the breakdown, "Clusters":

- `GET /api/clusters/{id}/status` → `200 { reachable, ready, kubernetes?, readOnly, namespaceSelector, error? }`. Combines `/healthz`, `/readyz` and `GET /api`; a failing cluster answers `200` with `reachable: false` and `error`. The `/api` info is cached 30 s per cluster; `?refresh=true` skips the cache.
- `GET /api/clusters/{id}/overview` → `Overview`
- `GET /api/clusters/{id}/nodes` → `Node[]`
- `GET /api/clusters/{id}/metrics` → `Metrics`; `503` with the controller's message when metrics-server is missing
- `GET /api/clusters/{id}/namespaces` → `Namespace[]`
- `GET /api/clusters/{id}/events?namespace&type&limit` → `Event[]`
- Errors as in the breakdown table; unknown cluster → `404`.

## Files

- `cluster/service/ClusterStatusService.kt` — new: builds the status. `reachable` is false when `/healthz` fails or times out; `ready` is from `/readyz` (and `kubernetes` from its body); `readOnly` and `namespaceSelector` come from `info`, cached with the injected `Clock` (the cache holds `ApiInfo` per cluster id and address; editing a cluster's address invalidates it). If the token isn't configured the status call itself answers `503` (not a `reachable:false` body) so the UI shows the configuration message.
- `cluster/service/ClusterMonitoring.kt` — new: looks up the cluster, then calls `HomelabClient` for overview, nodes, metrics, namespaces and events.
- `cluster/controller/ClusterMonitoringController.kt` — new: the six routes under `/api/clusters/{id}`; implements the `HomelabController` marker from task 03 so its advice applies. `limit` defaults to 100 and is capped at 1000 by the controller (the api rejects larger with 400).
- `cluster/service/ClusterService.kt` — change: call `ClusterStatusService.invalidate(id)` after an update or delete.

## Steps

1. Write the cache as a small class holding `(info, fetchedAt)` in a `ConcurrentHashMap`.
2. Write the status flow: health, then ready, then info; each failure is captured into `error` (the controller's message) rather than thrown, except a missing token.
3. Write the pass-through endpoints. Metrics passes the controller's 503 through via the advice.
4. Controller tests wire a mocked `ClusterMonitoring` and `ClusterStatusService`.

## Tests

- `ClusterStatusServiceTests` — healthy cluster gives reachable, ready, version and mode (AC-7); `/readyz` 503 gives `reachable: true, ready: false` with the error; connection failure gives `reachable: false` with "cluster unreachable" text (AC-9); a second call within 30 s doesn't call `/api` again, `refresh` does; read-only mode is reported (AC-26).
- `ClusterMonitoringControllerTests` — `@WebMvcTest`: overview and nodes returned as given (AC-7); metrics 503 returns 503 with the controller's message (AC-8); 502 returns 502 "cluster unreachable" (AC-9); each route without a bearer token → 401 and no service call (AC-30); `limit` over 1000 → 400.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`

## Out of scope

- Project-scoped endpoints (tasks 07 to 09), any UI.
