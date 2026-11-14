---
feature: 008-deployment-and-monitoring
task: 09-api-project-control
layer: api
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/09-api-project-control
base: feat/008-deployment-and-monitoring/08-api-project-read
covers: [AC-19, AC-20, AC-21, AC-22, AC-23, AC-24, AC-25, AC-26, AC-27, AC-30]
---

# 09 — Project change endpoints

## Goal

Restart, scale, image change, pod restart and apply, scoped to the project's namespaces and blocked when the cluster is read-only.

## Contract

From the breakdown, "Project deployment (change)". All answer `403` when the cluster is read-only (no upstream change request) or the namespace isn't the project's.

- `POST /api/projects/{id}/workloads/{namespace}/{kind}/{name}/restart` → `200 Workload`
- `POST /api/projects/{id}/workloads/{namespace}/{kind}/{name}/scale` `{ replicas }` → `200 Workload`; `400` outside 0-100
- `POST /api/projects/{id}/workloads/{namespace}/{kind}/{name}/image` `{ image, container? }` → `200 Workload`; `400` for a blank image
- `POST /api/projects/{id}/pods/{namespace}/{name}/restart?force` → `202 PodRestart`; `409` passes through for an ownerless pod
- `POST /api/projects/{id}/apply?namespace&dryRun` with a raw text body → `200 ApplyResult`; `namespace` must be the project's; `422` carries `{ detail, dryRun, results }`; `413` over 5 MB

## Files

Package `com.toptier.custom.cluster`:

- `service/ProjectControl.kt` — new: each method resolves `ProjectScope`, checks the namespace, then `requireWritable` (reads `info` through `ClusterStatusService` with its 30 s cache; if the cache says writable but the controller answers 403 read-only, the 403 message is passed through and the cache entry is dropped). Archived projects refuse changes (409, as elsewhere). Validates `replicas` 0-100 and a non-blank `image` before calling.
- `service/ApplyGuard.kt` — new: before sending apply, parses the body with `snakeyaml` (already a Spring Boot dependency, so no new library) to load all documents; JSON is valid YAML. If `snakeyaml` isn't on the classpath, ask before adding a dependency (`build.gradle` is shared). Rejects with `403` and the api's message when any object's `metadata.namespace` is set and not one of the project's namespaces, and when the `kind` is cluster-scoped (`Namespace`, `Node`, `ClusterRole`, `ClusterRoleBinding`, `CustomResourceDefinition`, `PersistentVolume`, `StorageClass`) so the message is clear. A body that can't be parsed is not rejected here: the controller reports the YAML error (`400`) in its own words. Body size over 5 MB is `413` with the api's message before reading it all (`Content-Length` and a bounded read).
- `controller/ProjectControlController.kt` — new: the five routes. Apply takes `@RequestBody String` (content type ignored). Implements `HomelabController`.
- `dto/ProjectControlDtos.kt` — new: `ScaleRequest`, `ImageRequest`.

## Steps

1. Write the guard and control service.
2. Write the controller. `dryRun` is passed through exactly; the api never turns a dry run into a real apply or the other way round.
3. Error responses for apply `422` use `HomelabApiException.results` from task 03.
4. Never log the body or YAML values; log cluster, project id, path and status.

## Tests

- `ProjectControlTests` — restart, scale, image and pod restart call the client with kind mapped and the namespace checked (AC-19, AC-20, AC-21, AC-22); scale -1 and 101 rejected, 0 allowed (AC-20); blank image rejected (AC-21); `container` forwarded (AC-21); `force` forwarded and 409 passes through unchanged (AC-23); foreign namespace → 403 with no upstream call (AC-27); read-only → 403 and the client's change methods are never called (AC-26).
- `ApplyGuardTests` — documents in other namespaces and cluster-scoped kinds refused; JSON accepted; multi-document YAML; an unparsable body is passed through (AC-24).
- `ProjectControlControllerTests` — `@WebMvcTest`: no bearer → 401, service not called (AC-30); `dryRun=true` is forwarded (AC-24); 422 returns `results` and the controller's message (AC-25); 413 (AC-25).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`

## Out of scope

- The UI, confirmations and progress polling (tasks 15 and 16).
