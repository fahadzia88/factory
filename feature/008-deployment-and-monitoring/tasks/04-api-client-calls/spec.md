---
feature: 008-deployment-and-monitoring
task: 04-api-client-calls
layer: api
status: draft
approved:
branch: feat/008-deployment-and-monitoring/04-api-client-calls
base: feat/008-deployment-and-monitoring/03-api-controller-client
covers: [AC-4, AC-29, AC-37]
---

# 04 — Client calls: discovery, health, monitoring, control

## Goal

One function per controller endpoint (except the two streams), typed, on top of task 03.

## Contract

The controller's endpoints from the requirements brief, called with the cluster's address. Functions take the cluster address and return the models from task 03; failures are the exceptions from task 03.

## Files

Package `com.toptier.custom.homelab.service`:

- `HomelabClient.kt` — new: class `HomelabClient(clients: HomelabClients, call: HomelabCall)` with:
  - `health(address)` (`GET /healthz`), `ready(address)` (`GET /readyz`; a 503 body `{status, error}` becomes `Readiness(ok = false, error)` instead of an exception), `info(address)` (`GET /api`);
  - `overview`, `namespaces`, `nodes`, `workloads(address, namespace?)`, `workload(address, namespace, kind, name)`, `pods(address, namespace?, problemsOnly)`, `pod(address, namespace, name)`, `events(address, namespace?, type?, limit?)`, `metrics(address, namespace?)`;
  - `restart(address, namespace, kind, name)`, `scale(..., replicas)`, `setImage(..., image, container?)`, `restartPod(address, namespace, name, force)`, `apply(address, body: String, namespace?, dryRun)`.
- `WorkloadKind.kt` — new: enum `DEPLOYMENTS("deployments")`, `STATEFULSETS("statefulsets")`; `fromPath` accepts `deployments`, `statefulsets`, `deploy`, `sts` and rejects others with a 400 (`HomelabBadRequestException`); `fromResponse("Deployment" | "StatefulSet")` maps back.

Details:

- Path segments are URL-encoded by the `RestClient` URI template, never concatenated.
- Control calls: `restart` and `restartPod` send no body; `scale` and `image` send JSON; `apply` sends the raw text with `Content-Type: text/plain`, query `namespace` and `dryRun=true` only when set.
- A 422 from apply becomes `HomelabApiException` that keeps `dryRun` and `results` (parsed from the body).
- The apply body is never logged.

## Steps

1. Write `WorkloadKind`.
2. Write each call as a one-liner through `HomelabCall`, with a `ParameterizedTypeReference` for lists.
3. `ready` parses the 503 body itself; any other failure goes through the mapping.

## Tests

`HomelabClientTests` with `MockRestServiceServer`, fixtures shaped like the brief's responses:

- Every call sends the bearer header and the right method, path and query (AC-37).
- `workload` with `StatefulSet` maps to `statefulsets` and back (AC-37).
- `scale` sends `{ "replicas": n }`; `setImage` sends `container` only when given; `restartPod(force = true)` sends `?force=true`; `apply` sends raw text with `dryRun=true` (AC-37).
- Apply 422 keeps per-object results; a 413 message comes back unchanged (AC-29, AC-37).
- `ready` 503 gives `ok = false` with the error text.
- A fixture check that no call path or query includes `access_token` (AC-4).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`

## Out of scope

- Streams (task 05), controllers and caching of `info` (task 06).
