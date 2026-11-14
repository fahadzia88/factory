---
feature: 008-deployment-and-monitoring
task: 03-api-controller-client
layer: api
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/03-api-controller-client
base: feat/008-deployment-and-monitoring/02-api-clusters
covers: [AC-4, AC-5, AC-6, AC-29, AC-37]
---

# 03 — Controller client foundation

## Goal

The base of the HomeLab Controller client: configuration, an HTTP client with bearer auth and a 10 s timeout, the response types, a typed error carrying the controller's status and message, and the error mapping the UI relies on. No endpoint calls yet.

## Contract

The error table in the breakdown ("Errors (all new endpoints)"), unchanged:

- controller 400, 403, 404, 409, 413, 422, 503 → same status, `detail` = the controller's `error`, `controllerStatus` set;
- controller 401 → 502, `controllerStatus: 401`, not retried;
- controller 502, unreachable or timeout → 502, `detail` says "cluster unreachable" plus the message;
- token not configured → 503, the controller is not called.

## Files

Package `com.toptier.custom.homelab`:

- `config/HomelabProperties.kt` — new: `@ConfigurationProperties("app.homelab")`: `token` (default blank), `timeout` (10 s), `statusCache` (30 s), `watchBackoffInitial` (1 s), `watchBackoffMax` (30 s). Validates in `init` (positive durations, initial ≤ max). `toString` must not print the token.
- `config/HomelabConfig.kt` — new: a `HomelabClients` factory bean. It builds a `RestClient` per cluster address from `RestClient.Builder` with `baseUrl(address)`, the 10 s connect and read timeout, `Accept: application/json` and `Authorization: Bearer <token>`. It caches clients per address. It also builds a second variant for streams: connect timeout only, no read timeout. The factory is the only place that reads the token.
- `dto/HomelabModels.kt` — new: Kotlin data classes for the controller's types (`Workload`, `Replicas`, `ImageRef`, `Problem`, `Container`, `Pod`, `PodOwner`, `Usage`, `Node`, `Event`, `Namespace`, `Overview`, `Metrics`, `PodRestart`, `ApplyResult`, `ApplyObjectResult`, `ApiInfo`, `Readiness`). Unknown fields ignored; optional fields nullable.
- `exception/HomelabExceptions.kt` — new: `HomelabException(status: Int?, message: String)` with subclasses `HomelabNotConfiguredException`, `HomelabApiException(status, message, body)` and `HomelabUnreachableException`. `HomelabApiException` for 422 keeps `dryRun` and `results`.
- `service/HomelabErrors.kt` — new: maps a `RestClientResponseException` to `HomelabApiException`, reading `{ "error": string }` (falls back to the response text, then to the status phrase); maps connect and read failures to `HomelabUnreachableException`.
- `controller/HomelabExceptionHandler.kt` — new: a `@RestControllerAdvice` for these exceptions, applied to the cluster and project deployment controllers (a marker interface `HomelabController` they implement, used in `assignableTypes`). It produces the table above: `ProblemDetail` with `controllerStatus`, and for 422 the `dryRun` and `results` properties.
- `api/src/main/resources/application.properties` — change: add `app.homelab.token=${CONTROLLER_API_TOKEN:}` and the other properties with comments saying what they are for, in the file's style.

## Steps

1. Write properties, factory, models, exceptions.
2. The factory throws `HomelabNotConfiguredException` when the token is blank, before any request is built.
3. Write the error mapping and the advice. The log line for a failed call includes the cluster name or address, the method and path and the status, never headers or bodies.
4. Add a `HomelabCall` helper: `run { client -> ... }` that applies the mapping, so tasks 04 and 05 only write the calls.

## Tests

- `HomelabClientsTests` — with `MockRestServiceServer.bindTo(builder)`: every request carries `Authorization: Bearer test-token` (AC-37); a blank token throws `HomelabNotConfiguredException` and sends nothing (AC-5).
- `HomelabErrorsTests` — for each of 400, 401, 403, 404, 409, 413, 422, 502, 503 a fixture `{ "error": "..." }` becomes an exception whose `message` is the controller's text unchanged (AC-29, AC-37); 422 keeps `results`; a non-JSON body falls back to text; timeouts and refused connections become `HomelabUnreachableException` (AC-37).
- `HomelabExceptionHandlerTests` — `@WebMvcTest` with a small test controller: the status table above, including 401 → 502 with `controllerStatus: 401` (AC-6), and `detail` equal to the controller's message (AC-29).
- `HomelabPropertiesTests` — defaults; invalid durations rejected; `toString` hides the token (AC-4).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`

## Out of scope

- The endpoint calls (task 04), streams (task 05), and anything the UI calls.
