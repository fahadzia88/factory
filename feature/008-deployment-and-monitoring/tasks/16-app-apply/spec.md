---
feature: 008-deployment-and-monitoring
task: 16-app-apply
layer: app
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/16-app-apply
base: feat/008-deployment-and-monitoring/15-app-control-actions
covers: [AC-24, AC-25, AC-33, AC-34, AC-35, AC-36, AC-31]
---

# 16 — Create and apply

## Goal

An Apply screen: forms for a workload and a ConfigMap that generate YAML, a YAML/JSON editor with templates, then a dry run followed by a confirmed apply.

## Contract

Consumes `POST /api/projects/{id}/apply?namespace&dryRun` with a raw text body. `422` returns `{ detail, dryRun, results }`; `413`, `403`, `400` carry the controller's message in `detail`. Progress polling uses the workload endpoint from task 15.

## Files

- `app/app/lib/manifests.ts` — new: pure functions `workloadYaml({ kind, name, namespace, image, replicas, port?, env[] })` and `configMapYaml({ name, namespace, data[] })`, producing a Deployment (selector and labels `app: <name>`), StatefulSet (with `serviceName: <name>`) or ConfigMap; values are quoted safely (strings with a colon, `#`, quotes or leading special characters get double quotes and escapes); `validateWorkloadForm` and `validateConfigMapForm` (DNS-1123 names ≤63, replicas 0-100, image not blank, port 1-65535, env names `[A-Za-z_][A-Za-z0-9_]*`, unique keys) returning field messages (AC-33, AC-34). `TEMPLATES` for Deployment, StatefulSet, ConfigMap (AC-35).
- `app/app/lib/deployment.ts` — change: `applyManifest(projectId, body, { namespace, dryRun })`; types `ApplyResult`; `applyErrorResults(error)` reads `results` from a 422 body.
- `app/app/routes/project-apply.tsx` — new: route `projects/:id/deployment/apply`. Tabs: "Workload form", "ConfigMap form", "YAML". Namespace `Select` of the project's namespaces. Hidden with a note when the cluster is read-only (AC-26 via the status).
- `app/app/components/deployment/workload-form.tsx` — new: fields kind, name, image reference (text only, hint `registry/name:tag`), replicas, container port, env rows; field errors next to inputs (AC-33). "Generate YAML" fills the editor and switches to the YAML tab.
- `app/app/components/deployment/configmap-form.tsx` — new: name and key/value rows; "Generate YAML" (AC-34).
- `app/app/components/deployment/yaml-editor.tsx` — new: a `Textarea` with a monospace font, a template `Select` (AC-35), editable text, size note (5 MB limit: the button is disabled and a message shown over 5 MB; the api and controller still decide).
- `app/app/components/deployment/apply-flow.tsx` — new: step 1 "Check" runs `dryRun=true` and shows the per-object results (kind, namespace, name, status, error) (AC-24); step 2 "Apply" is enabled only after a successful dry run of the same text and opens a confirmation dialog listing the objects, then runs the real apply. Changing the text resets to step 1. A `422` shows the error and every result (valid, applied, failed) and says earlier objects may already be applied (AC-25); `413` shows the message; `400` shows the controller's YAML error in an `Alert`.
- `app/app/components/deployment/apply-results.tsx` — new: results table with a status badge (text) and error text.
- After a successful apply, applied Deployments and StatefulSets get a progress banner via `use-workload-progress` and a link to the workload (AC-36).
- `app/app/routes.ts` — change: add the apply route.
- `app/app/routes/project-deployment.tsx` — change: an "Apply" button (hidden when read-only) linking to the apply route.

## Steps

1. Write `manifests.ts` with the pure generators and validators first.
2. Write the forms and the editor; the namespace in the generated YAML is the selected one.
3. Write the flow: dry run → results → confirm → apply → results → progress.
4. Phone width: the editor and results scroll inside their containers (AC-31).

## Tests

- Manual (no runner): generated Deployment, StatefulSet and ConfigMap YAML pass a dry run and apply (AC-33, AC-34); blank name or image is refused next to the field (AC-33); templates load and edits are kept (AC-35); dry run results show before apply, and apply needs a second confirmation (AC-24); a manifest with a bad second document shows the 422 results (AC-25); a body over 5 MB shows the message (AC-25); applied workload's status is followed to `ready` (AC-36); 375 px (AC-31).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the files changed

## Out of scope

- Pull secrets, other kinds in forms, the nav (task 17).
