---
feature: 008-deployment-and-monitoring
task: 02-api-clusters
layer: api
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/02-api-clusters
base: feat/008-deployment-and-monitoring/01-infra-controller-token
covers: [AC-2, AC-3, AC-30]
---

# 02 — Cluster entity and CRUD endpoints

## Goal

Users can store clusters (name and `http(s)` address) through the api: list, create, update, delete.

## Contract

From the breakdown, "Clusters" section:

- `GET /api/clusters` → `200 [{ id, name, address, createdAt }]`
- `POST /api/clusters` `{ name, address }` → `201 Cluster`; `400` for a blank name, a name over 100 characters, an address that isn't `http(s)`, or a duplicate name ignoring case.
- `PUT /api/clusters/{id}` `{ name, address }` → `200 Cluster`; `404`.
- `DELETE /api/clusters/{id}` → `204`; linked projects are unlinked; `404`.
- Errors are `ProblemDetail`; a rejected save carries `errors` (field → message), like `ProjectExceptionHandler`.

## Files

Package `com.toptier.custom.cluster`:

- `domain/Cluster.kt` — new: entity `cluster` (`id`, `name` ≤100, `nameKey` unique lowercase, `address` ≤300, `createdAt`, `updatedAt`), like `Project`.
- `domain/ClusterRepository.kt` — new: `findByNameKey`, ordered list by name.
- `dto/ClusterDtos.kt` — new: `ClusterRequest`, `ClusterResponse`.
- `exception/ClusterExceptions.kt` — new: sealed `ClusterException` with `ClusterNotFoundException`, `ClusterValidationException(errors)`.
- `service/ClusterService.kt` — new: list, create, update, delete. Uses the injected `Clock`. Validation: trimmed name, address parsed with `java.net.URI`; scheme `http` or `https` with a host; stored without a trailing slash; no credentials in the URL (`userInfo` null).
- `controller/ClusterController.kt` — new: the four endpoints.
- `controller/ClusterExceptionHandler.kt` — new: `@RestControllerAdvice(basePackageClasses = [ClusterController::class])`; 400 with `errors`, 404 "Cluster not found.", unreadable body → 400.

## Steps

1. Add the entity and repository; Hibernate creates the table.
2. Write `ClusterService` with the validation above; names are unique ignoring case (a duplicate is a 400 with `errors.name`).
3. Delete: the project link table doesn't exist yet. Publish a `ClusterDeletedEvent(clusterId)` (like `ProjectArchivedEvent`) that task 07 listens to for unlinking; deleting a cluster also succeeds when nobody listens.
4. Write the controller and exception handler. No change to `SecurityConfig`: the routes stay behind the bearer filter.

## Tests

- `ClusterServiceTests` — create trims and stores; blank name, long name, `ftp://x`, `host-only`, `http://user:pw@host` rejected with field messages; duplicate name ignoring case rejected; update keeps its own name; delete publishes the event; unknown id is not found (AC-2, AC-3).
- `ClusterRepositoryTests` — `@DataJpaTest`: unique `nameKey` (AC-2).
- `ClusterControllerTests` — `@WebMvcTest` with `SecurityConfig` and the handler, `app.security.api-token=test-token`: each route without a bearer token → 401 and the service isn't called (AC-30); status codes and bodies for the happy and error paths (AC-2, AC-3).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q` (the container is `factory-api` in `docker-compose.yml`; `api/CLAUDE.md` calls it `skeleton-api`).

## Out of scope

- Status and monitoring endpoints (task 06), linking projects (task 07), anything that calls the controller.
