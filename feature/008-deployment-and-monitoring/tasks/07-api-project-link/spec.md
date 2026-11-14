---
feature: 008-deployment-and-monitoring
task: 07-api-project-link
layer: api
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/07-api-project-link
base: feat/008-deployment-and-monitoring/06-api-cluster-endpoints
covers: [AC-10, AC-32, AC-30]
---

# 07 — Project cluster link and namespace registration

## Goal

A project links to one cluster and registers the existing namespaces it monitors and changes.

## Contract

From the breakdown, "Project link":

- `GET /api/projects/{id}/cluster` → `200 { clusterId, clusterName, namespaces: string[] }`, or `200 null` when not linked.
- `PUT /api/projects/{id}/cluster` `{ clusterId, namespaces }` → `200` the same shape. `400` for no namespaces, a malformed name (DNS label: lowercase letters, digits, `-`, ≤63) or a duplicate. Each namespace must appear in `/api/namespaces` of the cluster; otherwise the controller's message or the api's own `403`/`404` comes back, and nothing is saved. An archived project answers as other archived projects do (409).
- `DELETE /api/projects/{id}/cluster` → `204`.

## Files

Package `com.toptier.custom.cluster`:

- `domain/ProjectCluster.kt` — new: entity `project_cluster` (`projectId` unique, `clusterId`, `createdAt`, `updatedAt`) with `@ElementCollection` table `project_cluster_namespace` for namespaces.
- `domain/ProjectClusterRepository.kt` — new: `findByProjectId`, `findAllByClusterId`, `deleteByClusterId`.
- `dto/ProjectClusterDtos.kt` — new: request and response.
- `service/ProjectClusterService.kt` — new: `get`, `set`, `clear`. `set` loads the project (archived check through `ProjectService`), loads the cluster (404), validates names, calls `HomelabClient.namespaces` and checks every name is in the list (a missing name gives `400` with `errors.namespaces` "Namespace x isn't managed by this cluster." — wording from the api; controller errors pass through unchanged). Saves all-or-nothing.
- `service/ClusterDeletedListener.kt` — new: `@EventListener` on `ClusterDeletedEvent` (task 02) that deletes the links.
- `controller/ProjectClusterController.kt` — new: the three routes; implements `HomelabController`.
- `controller/ClusterExceptionHandler.kt` — change: also handle `ClusterValidationException` for this controller.

## Steps

1. Write entity and repository; Hibernate creates the tables.
2. Write the service. Reading the link never calls the controller.
3. Write the controller. A project that doesn't exist is `404` "Project not found." (reuse `ProjectService` lookups).

## Tests

- `ProjectClusterServiceTests` — saves a link with managed namespaces (AC-10, AC-32); an unknown namespace is rejected and nothing saved (AC-32); the controller's 403 message is returned as is (AC-32); duplicates and bad names rejected (AC-10); replacing the link replaces the namespaces; clearing removes it; cluster deletion removes links (AC-10); archived project refused.
- `ProjectClusterRepositoryTests` — `@DataJpaTest`: one link per project, namespaces persisted.
- `ProjectClusterControllerTests` — `@WebMvcTest`: no bearer → 401 and no service call (AC-30); `null` body when not linked; status codes.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`

## Out of scope

- Reading workloads (task 08), changes (task 09), the UI (task 12).
