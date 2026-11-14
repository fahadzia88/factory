---
feature: 008-deployment-and-monitoring
status: draft
approved:
---

# Deployment and Monitoring

## Problem

Projects run on a Kubernetes cluster managed by the HomeLab Controller (an HTTP service), but nothing in this app shows how they are doing or lets anyone act on them. Operators have to leave the app to see whether a workload is healthy, read pod logs, restart something, scale it, change an image or apply a manifest. The controller's bearer token is effectively cluster-admin, so it can't be handed to a browser, and the controller sends no CORS headers, so the browser can't call it anyway.

## Users

Signed-in operators of this app who deploy and watch their projects' workloads on the home-lab cluster.

## Scope

- **Clusters (global).** A new top-level Clusters section where a user adds, edits and removes a cluster with a **name** and an **address** (any `http(s)` URL). Each cluster shows its health (`/healthz`, `/readyz`, Kubernetes version) and its mode (read-only or not, from `GET /api`).
- **Cluster page (cluster-wide).** Overview, nodes (with usage when available), recent events, and metrics, for the whole cluster.
- **Project link.** A project links to one cluster and lists one or more namespaces. All project cluster screens only show and change those namespaces.
- **Project deployment tab.** For the project's namespaces:
  - workloads list with status, replicas, images and the `problem` of their pods;
  - workload detail with pods and events;
  - pod detail with containers, events and logs (tail, previous, timestamps, follow);
  - events list filtered by namespace and type;
  - live pod state through the controller's watch stream, proxied by the backend as SSE.
- **Control actions** (workloads and pods): restart, scale (0-100), change image (with container when the pod has more than one), restart pod (with force when the pod has no owner), and apply YAML/JSON.
- **Create from the UI.** On a project's Deployment tab:
  - forms to define a workload (kind Deployment or StatefulSet, name, image reference, replicas, container port, environment variables) and a ConfigMap (name, key/value entries). Each form generates YAML;
  - a raw YAML/JSON editor with starter templates (Deployment, StatefulSet, ConfigMap) where the generated YAML can be edited;
  - both go through the same dry run, then confirmed apply flow, with `namespace` defaulting to one of the project's namespaces.
- **Namespace registration.** A project registers existing namespaces by name. The app checks the name against `/api/namespaces` and shows the controller's message when it isn't managed. The app does not create namespaces.
- **Backend client** for the controller in `api/`: one function per endpoint, typed responses, a typed error carrying `status` and the controller's `message`, a 10 s timeout on normal requests and none on streams, and cancellable streams. It has its own endpoints for the UI behind this app's auth, including an SSE passthrough for watch and a streaming passthrough for logs.
- **Docs.** A short document listing the configuration variables and how to point the app at the controller.
- **Async follow-up.** After restart, scale or image, the UI polls the workload every 2-3 s until `ready`, `failed` or `scaled-down`, with a 5-minute timeout.
- **Configuration.** The bearer token comes from `CONTROLLER_API_TOKEN` (backend config/secret). The cluster address comes from the cluster record, not from an environment variable.

## Non-goals

- No chat agent tools for the cluster; `agent/` is unchanged.
- No per-cluster tokens and no token entry in the UI; one server-side token serves every cluster.
- No allowlist of cluster addresses.
- No per-user roles or permissions beyond this app's existing sign-in.
- No several clusters per project; a project links to one cluster.
- No creating or deleting namespaces, and no cluster-scoped object changes (the controller has no endpoint for it and refuses them in apply). Namespaces are only registered.
- No creating or managing pull secrets; "image source" is an image reference only (`registry/name:tag`).
- No forms for kinds other than Deployment, StatefulSet and ConfigMap; anything else goes through the YAML editor.
- No metrics history or charts beyond the current values from `/api/metrics`.

## Assumptions

- Every signed-in user may view and, unless the controller is read-only, change the clusters and the project namespaces they can see in this app.
- One `CONTROLLER_API_TOKEN` is valid for every cluster a user adds. A cluster with a different token will answer 401 and show as a configuration error.
- Adding a cluster address is trusted: the backend calls whatever `http(s)` URL a signed-in user enters (accepted SSRF risk on a trusted home lab).
- A project's namespaces are managed by the controller; others return 403 and the message is shown.
- The controller's behavior is as described in the controller API brief (endpoints, types, error shapes, SSE format).

## Constraints

- **Token stays on the server.** It is never sent to the browser, bundled, returned by any endpoint, or logged. Logs may include the cluster name and path but not the `Authorization` header.
- **Controller errors are shown as they are.** Every non-2xx `{ "error": string }` message reaches the user unchanged, not replaced by a generic one. For 422 on apply, the per-object `results` are shown.
- **Read-only mode.** `GET /api` is read when a cluster is first used and on cluster refresh; when `readOnly` is true, change controls are hidden or disabled with a reason.
- **Destructive actions need confirmation:** restart, scale to 0, image change, pod restart and apply. Apply always runs `dryRun=true` first and shows the result before the real apply.
- **Error handling by status:** 400 next to the input; 401 as an operator configuration error with no retry; 403 shows the message and disables the control; 404 refreshes the list; 409 on pod restart offers a retry with `force=true` after explicit confirmation; 413 shows the message; 422 shows results; 502 shows "cluster unreachable" with the message and retries with backoff; 503 on metrics hides usage and shows the message.
- **SSE and logs.** The backend forwards `/api/watch` and `follow=true` logs unbuffered and aborts the request to the controller when the browser disconnects. The token is sent as a header, never as `?access_token=`.
- **Kind mapping.** Responses report `Deployment`/`StatefulSet`; the app maps them to `deployments`/`statefulsets` for paths.
- **StatefulSet bad image.** When a workload's pods have a `problem`, the UI offers "Restart pod".
- Follows `api/CLAUDE.md` (RestClient per upstream, typed properties, `ProblemDetail` errors, `Clock`) and `app/CLAUDE.md`. Existing features keep working.

## Non-functional requirements

- **Security:** the token is read only on the backend; the app's own auth guards every new endpoint; clusters and namespaces are validated before use; request logs never include the token or apply bodies.
- **Performance:** list and detail screens load within a few seconds on a healthy cluster; the controller call has a timeout so an unreachable cluster fails fast with a 502-style message; the watch stream reconnects with backoff and rebuilds state from the `ADDED` events.
- **Resource use:** closing a log or watch view ends the upstream request.
- **Accessibility:** status is shown with text as well as color; confirmation dialogs are keyboard operable.
- **Layout:** screens work at phone width; log and YAML views scroll rather than overflow.

## Open questions

- None. (Approval needs this list empty.)

## Acceptance criteria

- **AC-1** — Given no clusters exist, when a user opens Clusters, then an empty state with an "Add cluster" action is shown.
- **AC-2** — Given the Clusters section, when a user adds a cluster with a name and an `http(s)` address, then it appears in the list with its health, Kubernetes version and read-only or read-write mode; an address that isn't `http(s)` or a blank name is rejected with a message next to the field.
- **AC-3** — Given a cluster, when a user edits its name or address or removes it, then the list reflects the change; removing a cluster linked to projects asks for confirmation and unlinks those projects.
- **AC-4** — Given any cluster page, project page or API response, when inspected (including network traffic, bundles and logs), then `CONTROLLER_API_TOKEN` never appears.
- **AC-5** — Given `CONTROLLER_API_TOKEN` is not configured on the backend, when a cluster is used, then the UI shows a configuration error and the app does not call the controller.
- **AC-6** — Given the controller answers 401, when any cluster call is made, then the UI shows the controller's message as a configuration error and does not retry.
- **AC-7** — Given a reachable cluster, when a user opens its page, then the overview (nodes ready/total, workloads ready/total, pod phases, problem pods, warnings in the last hour) and the node list are shown.
- **AC-8** — Given metrics-server is missing (controller returns 503), when the cluster page loads, then usage values are hidden and the controller's message is shown; the rest of the page still works.
- **AC-9** — Given a cluster that can't reach Kubernetes (`/readyz` 503 or a 502), when the page loads, then "cluster unreachable" with the controller's message is shown and the call retries with backoff.
- **AC-10** — Given a project, when a user links it to a cluster and enters one or more namespaces, then the choice is saved and shown; unlinking removes it.
- **AC-11** — Given a project linked to a cluster, when its Deployment tab opens, then only workloads, pods and events from its namespaces are listed, each workload with status, replicas and images.
- **AC-12** — Given a project not linked to a cluster, when its Deployment tab opens, then an empty state explains how to link one.
- **AC-13** — Given a workload whose pods have a `problem`, when it is listed or opened, then `problem.reason` and `problem.message` are shown prominently.
- **AC-14** — Given a workload detail, when it opens, then its pods and events are shown; a Kubernetes kind is mapped correctly (`Deployment` → `deployments`, `StatefulSet` → `statefulsets`).
- **AC-15** — Given a pod detail, when it opens, then its containers (state, restarts, last termination) and events are shown.
- **AC-16** — Given a pod's logs view, when a user sets container, tail, since, previous or timestamps, then the logs shown follow those options and the container used is shown; with follow on, lines stream live and the upstream request is aborted when the view closes.
- **AC-17** — Given a pod list, when pods change in the cluster, then the list updates live from the watch stream; when the stream drops, it reconnects with backoff and rebuilds state without duplicates.
- **AC-18** — Given a project's events view, when a user filters by namespace or type (Normal/Warning), then only matching events are shown, newest first.
- **AC-19** — Given a writable cluster, when a user restarts a workload and confirms, then the workload is restarted and its status is followed by polling every 2-3 s until `ready`, `failed` or `scaled-down`, stopping after 5 minutes with a timeout message.
- **AC-20** — Given a workload, when a user scales to a number from 1 to 100, then the change applies after the progress polling starts; a value outside 0-100 is rejected next to the input; scaling to 0 asks for confirmation first.
- **AC-21** — Given a workload, when a user changes an image and confirms, then the change applies; when a pod has several containers, a container must be chosen first; a 400 message is shown next to the input.
- **AC-22** — Given a StatefulSet pod stuck on a bad image after the image was fixed, when the workload shows a pod `problem`, then "Restart pod" is offered and, after confirmation, deletes the pod.
- **AC-23** — Given a pod with no owner, when a user restarts it and the controller answers 409, then the UI offers to retry with `force=true` and only does so after explicit confirmation.
- **AC-24** — Given YAML or JSON entered in Apply, when a user submits it, then a `dryRun=true` request runs first and its per-object results are shown; the real apply happens only after a second explicit confirmation.
- **AC-25** — Given an apply that fails partway (422), when the response arrives, then the error and every object's result (valid, applied, failed) are shown; a body over 5 MB shows the 413 message.
- **AC-26** — Given the controller is in read-only mode, when any cluster or project screen loads, then restart, scale, image, pod restart and apply controls are hidden or disabled with an explanation, and the backend does not send change requests.
- **AC-27** — Given a 403 for a namespace not managed by the controller, when a user opens or changes it, then the controller's message is shown and the control is disabled.
- **AC-28** — Given a 404 for an object that was removed, when a view requests it, then the controller's message is shown and the list is refreshed.
- **AC-29** — Given any non-2xx controller response, when it reaches the UI, then the controller's `error` message is shown unchanged.
- **AC-30** — Given a user who isn't signed in, when they call any new backend endpoint, then the request is rejected by this app's auth and the controller isn't called.
- **AC-31** — Given a phone-width screen, when a user uses the cluster and deployment screens, then they remain usable without horizontal page scrolling.

- **AC-32** — Given a project with a cluster, when a user registers a namespace name that exists and is managed, then it is saved; when the name isn't in `/api/namespaces` or the controller refuses it, then the controller's message is shown and nothing is saved.
- **AC-33** — Given the workload form, when a user enters kind, name, image reference, replicas (0-100), a container port and environment variables and submits, then YAML is generated and shown for review in the namespace they picked from the project's namespaces; an empty name or image is rejected next to the field.
- **AC-34** — Given the ConfigMap form, when a user enters a name and key/value entries, then YAML is generated and shown for review.
- **AC-35** — Given the YAML editor, when a user picks a Deployment, StatefulSet or ConfigMap template or opens generated YAML, then the text can be edited before it goes through the dry run and confirmed apply flow.
- **AC-36** — Given a workload created through apply, when the apply succeeds, then the workload appears in the project's list and its status is followed until `ready`, `failed` or `scaled-down`.
- **AC-37** — Given the controller client, when its automated tests run with the HTTP layer mocked, then they check the `Authorization: Bearer` header, the mapping of 400, 401, 403, 404, 409, 413, 422, 502 and 503 to a typed error carrying `status` and the controller's message, the apply 422 results, a 10 s timeout on normal requests, and SSE parsing (event, data, keep-alives ignored, reconnect with backoff, cancel).
- **AC-38** — Given a repository checkout, when a developer reads the new docs, then they list `CONTROLLER_API_TOKEN` and how to add a cluster address (Docker `http://homelabs001:8088` or in-cluster `http://controller.controller.svc:3000`) and link a project.

## Changelog

- 2026-11-14 — Created.
- 2026-11-14 — Added create-from-UI (forms plus YAML editor), namespace registration (no creation), client, tests and docs deliverables; AC-32 to AC-38.
- 2026-11-14 — Drafted from the controller brief and the user's answers (global clusters, per-project namespaces, one env token, no agent tools).
