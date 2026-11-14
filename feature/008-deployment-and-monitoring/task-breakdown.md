---
feature: 008-deployment-and-monitoring
status: draft
approved:
---

# Deployment and Monitoring: task breakdown

Requirements: [requirements.md](requirements.md)

## Contracts

All endpoints are on this app's `api/` behind the existing bearer auth (`StaticTokenAuthenticationFilter`) and reached by the app through the Vite `/api` proxy. The controller token (`CONTROLLER_API_TOKEN`) is read only by `api/`. The controller's own address comes from the cluster record.

### Errors (all new endpoints)

Errors are `ProblemDetail`. For a controller failure, `detail` is the controller's `error` message, unchanged, and the extra property `controllerStatus` holds the controller's status.

| Controller status | api HTTP status | Notes |
| --- | --- | --- |
| 400, 403, 404, 409, 413, 422, 503 | same | 422 also carries `dryRun` and `results`. 403 also covers read-only. |
| 401 | 502 | `controllerStatus: 401`. Not sent as 401 so the app doesn't treat it as a signed-out session. Not retried. |
| 502, unreachable, timeout | 502 | `detail` says "cluster unreachable" plus the message. |
| token not configured | 503 | `controllerStatus` absent; `detail` says `CONTROLLER_API_TOKEN` is not set. The controller isn't called. |
| namespace not in the project | 403 | api's own message; the controller isn't called. |
| change request while the cluster is read-only | 403 | The controller isn't called. |

The app shows `detail` as given. It must not use `apiErrorMessage` for these calls, since that replaces 404 and 502 with generic text; the new `lib/clusters.ts` supplies its own message helper.

### Clusters

- `GET /api/clusters` → `200 [{ id, name, address, createdAt }]`
- `POST /api/clusters` `{ name, address }` → `201 Cluster`. `400` for a blank name, a name over 100 characters, an address that isn't `http(s)`, or a duplicate name (ignoring case).
- `PUT /api/clusters/{id}` `{ name, address }` → `200 Cluster`; `404`.
- `DELETE /api/clusters/{id}` → `204`; linked projects are unlinked. `404`.
- `GET /api/clusters/{id}/status` → `200 { reachable, ready, kubernetes?, readOnly, namespaceSelector, error? }`. It combines `/healthz`, `/readyz` and `GET /api`. A failing cluster answers `200` with `reachable: false` and `error`, so the list can show every cluster. `readOnly` comes from `GET /api`, which is cached for 30 seconds per cluster (`?refresh=true` skips the cache).
- `GET /api/clusters/{id}/overview` → `Overview`
- `GET /api/clusters/{id}/nodes` → `Node[]`
- `GET /api/clusters/{id}/metrics` → `Metrics`; `503` with the controller's message when metrics-server is missing.
- `GET /api/clusters/{id}/namespaces` → `Namespace[]`
- `GET /api/clusters/{id}/events?namespace&type&limit` → `Event[]`

Types (`Overview`, `Node`, `Metrics`, `Namespace`, `Event`, `Workload`, `Pod`, `PodRestart`, `ApplyResult`) are exactly the controller's, as in the brief.

### Project link

- `GET /api/projects/{id}/cluster` → `200 { clusterId, clusterName, namespaces: string[] }`, or `200 null` when not linked.
- `PUT /api/projects/{id}/cluster` `{ clusterId, namespaces: string[] }` → `200`. `400` for no namespaces, a blank or malformed name (Kubernetes DNS label) or a duplicate. Each namespace must appear in `/api/namespaces` of that cluster; otherwise the controller's or api's message comes back (`403`/`404`). An archived project answers as other archived projects do.
- `DELETE /api/projects/{id}/cluster` → `204`.

### Project deployment (read)

All need a linked cluster (`404` "Project isn't linked to a cluster" otherwise) and a namespace that belongs to the project. `namespace` omitted means all of the project's namespaces (the api calls the controller once per namespace and merges, sorted by namespace and name).

- `GET /api/projects/{id}/workloads?namespace` → `Workload[]`
- `GET /api/projects/{id}/workloads/{namespace}/{kind}/{name}` → `Workload & { pods, events }`. `kind` is `deployments` or `statefulsets`.
- `GET /api/projects/{id}/pods?namespace&problems` → `Pod[]`
- `GET /api/projects/{id}/pods/{namespace}/{name}` → `Pod & { events }`
- `GET /api/projects/{id}/pods/{namespace}/{name}/logs?container&tail&since&previous&follow&timestamps` → `text/plain`, streamed when `follow=true`; the `X-Container` header is passed on. Closing the connection aborts the upstream request.
- `GET /api/projects/{id}/events?namespace&type&limit` → `Event[]`
- `GET /api/projects/{id}/watch` → `text/event-stream`. Events pass through as `event: pod` (`Pod & { type }`) and `event: error` (`{ message }`). The api reconnects upstream with backoff when the controller closes the stream and sends `event: reset` (data `{}`) before the replay, so the app clears its state. Events for pods outside the project's namespaces are dropped. Keep-alive comments pass through. The app reads it with `fetch` streaming (the bearer header can't go on an `EventSource`).
- `GET /api/projects/{id}/status` → `{ readOnly, reachable, error? }`, the link's cluster status, so project pages know whether to show controls.

### Project deployment (change)

All answer `403` when the cluster is read-only (no upstream call) and when the namespace isn't the project's.

- `POST /api/projects/{id}/workloads/{namespace}/{kind}/{name}/restart` → `200 Workload`
- `POST /api/projects/{id}/workloads/{namespace}/{kind}/{name}/scale` `{ replicas }` → `200 Workload`; `400` outside 0-100.
- `POST /api/projects/{id}/workloads/{namespace}/{kind}/{name}/image` `{ image, container? }` → `200 Workload`; `400` for a blank image.
- `POST /api/projects/{id}/pods/{namespace}/{name}/restart?force` → `202 PodRestart`; `409` for a pod without an owner (the app retries with `force=true` after confirmation).
- `POST /api/projects/{id}/apply?namespace&dryRun` with a raw text body → `200 ApplyResult`. `namespace` must be one of the project's. Objects that name another namespace are rejected by the api with `403` before anything is sent. `422` carries `{ detail, dryRun, results }`; `413` over 5 MB; at most 100 objects is the controller's own check.

## Tasks

Stack order, bottom first. Each task is one PR based on the row above it.

| #  | Layer | Task | Depends on | ACs | Branch |
| -- | ----- | ---- | ---------- | --- | ------ |
| 00 | docs  | Feature folder | — | — | `feat/008-deployment-and-monitoring/00-docs` |
| 01 | infra | Pass `CONTROLLER_API_TOKEN` to the api container | 00 | AC-4 | `feat/008-deployment-and-monitoring/01-infra-controller-token` |
| 02 | api   | Cluster entity, CRUD endpoints, address validation | 01 | AC-2, AC-3, AC-30 | `feat/008-deployment-and-monitoring/02-api-clusters` |
| 03 | api   | Controller client foundation: properties, `RestClient` with 10 s timeout, models, typed error with `status` and message, error advice, token guard | 02 | AC-4, AC-5, AC-6, AC-29, AC-37 | `feat/008-deployment-and-monitoring/03-api-controller-client` |
| 04 | api   | Client calls for discovery, health, monitoring and control, kind mapping, apply with 422 results | 03 | AC-4, AC-29, AC-37 | `feat/008-deployment-and-monitoring/04-api-client-calls` |
| 05 | api   | Streaming: SSE parser, watch with reconnect and `reset`, log passthrough, cancellation | 04 | AC-16, AC-17, AC-37 | `feat/008-deployment-and-monitoring/05-api-client-streams` |
| 06 | api   | Cluster endpoints: status and mode, overview, nodes, metrics, namespaces, events | 05 | AC-7, AC-8, AC-9, AC-26, AC-30 | `feat/008-deployment-and-monitoring/06-api-cluster-endpoints` |
| 07 | api   | Project cluster link and namespace registration | 06 | AC-10, AC-32, AC-30 | `feat/008-deployment-and-monitoring/07-api-project-link` |
| 08 | api   | Project read endpoints: workloads, pods, logs, events, watch, status | 07 | AC-11, AC-14, AC-15, AC-16, AC-17, AC-18, AC-27, AC-30 | `feat/008-deployment-and-monitoring/08-api-project-read` |
| 09 | api   | Project change endpoints: restart, scale, image, pod restart, apply, read-only and namespace guards | 08 | AC-19 to AC-27, AC-30 | `feat/008-deployment-and-monitoring/09-api-project-control` |
| 10 | app   | Clusters list, add, edit, remove (route not in the nav yet) | 09 | AC-1, AC-2, AC-3, AC-5, AC-31 | `feat/008-deployment-and-monitoring/10-app-clusters` |
| 11 | app   | Cluster page: status, overview, nodes, metrics, events | 10 | AC-6, AC-7, AC-8, AC-9, AC-26, AC-31 | `feat/008-deployment-and-monitoring/11-app-cluster-page` |
| 12 | app   | Project cluster link and namespaces section | 11 | AC-10, AC-32, AC-31 | `feat/008-deployment-and-monitoring/12-app-project-link` |
| 13 | app   | Deployment tab: workload list and detail, problems, events, live pod updates | 12 | AC-11 to AC-14, AC-17, AC-18, AC-27, AC-28, AC-31 | `feat/008-deployment-and-monitoring/13-app-deployment-tab` |
| 14 | app   | Pod detail and logs (tail, since, previous, timestamps, follow) | 13 | AC-13, AC-15, AC-16, AC-31 | `feat/008-deployment-and-monitoring/14-app-pod-logs` |
| 15 | app   | Control actions with confirmations and progress polling: restart, scale, image, pod restart | 14 | AC-19 to AC-23, AC-26, AC-28, AC-31 | `feat/008-deployment-and-monitoring/15-app-control-actions` |
| 16 | app   | Create and apply: workload and ConfigMap forms, YAML editor with templates, dry run, apply | 15 | AC-24, AC-25, AC-33 to AC-36, AC-31 | `feat/008-deployment-and-monitoring/16-app-apply` |
| 17 | app   | Expose it: Clusters in the nav, Deployment link on the project page | 16 | AC-1, AC-12, AC-31 | `feat/008-deployment-and-monitoring/17-app-nav` |
| 18 | infra | Docs: configuration variables, pointing the app at the controller | 17 | AC-38 | `feat/008-deployment-and-monitoring/18-infra-docs` |

Task 01 touches `docker-compose.yml`, a shared file, so it needs the user's go-ahead before it starts.

## AC coverage

| AC | Tasks | Verified by |
| -- | ----- | ----------- |
| AC-1 | 10, 17 | Manual: Clusters with no clusters shows the empty state |
| AC-2 | 02, 10 | `ClusterServiceTests` (validation); manual form check |
| AC-3 | 02, 10 | `ClusterServiceTests` (update, delete unlinks projects); manual |
| AC-4 | 01, 03, 04 | Test that no response, error or log line contains the token; manual network check |
| AC-5 | 03, 10 | `ControllerClientTests` (unset token → 503, no upstream call); manual |
| AC-6 | 03, 11 | `ControllerClientTests` (401 → 502 with `controllerStatus`); manual |
| AC-7 | 06, 11 | `ClusterControllerTests`; manual |
| AC-8 | 06, 11 | `ClusterControllerTests` (metrics 503 passes the message); manual |
| AC-9 | 06, 11 | `ClusterControllerTests` (502 and readyz 503); manual with a stopped controller |
| AC-10 | 07, 12 | `ProjectClusterTests`; manual |
| AC-11 | 08, 13 | `ProjectDeploymentControllerTests` (namespace filter); manual |
| AC-12 | 13, 17 | Manual: unlinked project's tab shows the empty state |
| AC-13 | 13, 14 | Manual with a pod in `ImagePullBackOff` |
| AC-14 | 08, 13 | `ProjectDeploymentControllerTests` (kind mapping); manual |
| AC-15 | 08, 14 | `ProjectDeploymentControllerTests`; manual |
| AC-16 | 05, 08, 14 | `ControllerStreamTests` (log options, `X-Container`, abort); manual follow and close |
| AC-17 | 05, 08, 13 | `ControllerStreamTests` (reconnect, `reset`, namespace filter); manual |
| AC-18 | 08, 13 | `ProjectDeploymentControllerTests`; manual |
| AC-19 | 09, 15 | `ProjectControlControllerTests`; manual restart with progress and timeout |
| AC-20 | 09, 15 | `ProjectControlControllerTests` (0-100); manual |
| AC-21 | 09, 15 | `ProjectControlControllerTests`; manual |
| AC-22 | 09, 15 | `ProjectControlControllerTests`; manual on a StatefulSet |
| AC-23 | 09, 15 | `ProjectControlControllerTests` (409 passes through; `force` is forwarded); manual |
| AC-24 | 09, 16 | `ProjectControlControllerTests` (`dryRun` is forwarded); manual |
| AC-25 | 09, 16 | `ControllerClientTests` and controller test (422 results, 413); manual |
| AC-26 | 06, 09, 10 to 15 | `ProjectControlControllerTests` (read-only → 403, no upstream call); manual read-only check |
| AC-27 | 08, 09, 13 | Controller tests (foreign namespace → 403); manual |
| AC-28 | 13, 15 | Manual: delete an object in the cluster, then act on it |
| AC-29 | 03, 04 | `ControllerClientTests` (message equals the controller's, per status) |
| AC-30 | 02, 06 to 09 | `@WebMvcTest` tests: no bearer → 401, service not called |
| AC-31 | 10 to 17 | Manual at 375 px width |
| AC-32 | 07, 12 | `ProjectClusterTests` (unknown namespace not saved); manual |
| AC-33 | 16 | Manual (YAML generated, validation messages) |
| AC-34 | 16 | Manual |
| AC-35 | 16 | Manual |
| AC-36 | 16 | Manual: apply a Deployment, watch status reach `ready` |
| AC-37 | 03, 04, 05 | `ControllerClientTests`, `ControllerStreamTests` with `MockRestServiceServer`: header, each status, 422, timeout, SSE parsing, keep-alives, reconnect, cancel |
| AC-38 | 18 | Manual: docs list the variables and the setup |

The app has no test runner (scripts are `build`, `typecheck`, `format`), so app ACs are checked by hand against the running app, plus typecheck, build and prettier. Adding a test framework is out of scope.

## Rollout

- Tasks 02 to 09 only add endpoints and tables; nothing in the UI calls them yet.
- Tasks 10 to 16 add routes that nothing links to. Cluster and project pages are reachable only by typed URL.
- Task 17 adds the nav item and the project link, so the feature appears all at once.
- Release: set `CONTROLLER_API_TOKEN` for the api (task 01). Tables `cluster` and the project link columns are created by Hibernate `ddl-auto=update`, as elsewhere. Without the token the cluster screens show a configuration message and nothing else breaks.

## Progress

| #  | Spec | PR | Status |
| -- | ---- | -- | ------ |
| 01 | [spec](tasks/01-infra-controller-token/spec.md) | | not started |
| 02 | [spec](tasks/02-api-clusters/spec.md) | | not started |
| 03 | [spec](tasks/03-api-controller-client/spec.md) | | not started |
| 04 | [spec](tasks/04-api-client-calls/spec.md) | | not started |
| 05 | [spec](tasks/05-api-client-streams/spec.md) | | not started |
| 06 | [spec](tasks/06-api-cluster-endpoints/spec.md) | | not started |
| 07 | [spec](tasks/07-api-project-link/spec.md) | | not started |
| 08 | [spec](tasks/08-api-project-read/spec.md) | | not started |
| 09 | [spec](tasks/09-api-project-control/spec.md) | | not started |
| 10 | [spec](tasks/10-app-clusters/spec.md) | | not started |
| 11 | [spec](tasks/11-app-cluster-page/spec.md) | | not started |
| 12 | [spec](tasks/12-app-project-link/spec.md) | | not started |
| 13 | [spec](tasks/13-app-deployment-tab/spec.md) | | not started |
| 14 | [spec](tasks/14-app-pod-logs/spec.md) | | not started |
| 15 | [spec](tasks/15-app-control-actions/spec.md) | | not started |
| 16 | [spec](tasks/16-app-apply/spec.md) | | not started |
| 17 | [spec](tasks/17-app-nav/spec.md) | | not started |
| 18 | [spec](tasks/18-infra-docs/spec.md) | | not started |

## Changelog

- 2026-11-14 — Created.
