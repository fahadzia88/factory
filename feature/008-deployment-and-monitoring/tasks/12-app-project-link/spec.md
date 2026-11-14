---
feature: 008-deployment-and-monitoring
task: 12-app-project-link
layer: app
status: draft
approved:
branch: feat/008-deployment-and-monitoring/12-app-project-link
base: feat/008-deployment-and-monitoring/11-app-cluster-page
covers: [AC-10, AC-32, AC-31]
---

# 12 — Project cluster link and namespaces

## Goal

On a project, choose a cluster and register namespaces. This is a card inside the project page that appears only after task 17 wires the tab.

## Contract

Consumes `GET /api/clusters`, `GET/PUT/DELETE /api/projects/{id}/cluster`, `GET /api/clusters/{id}/namespaces` from the breakdown. `400` carries `errors.namespaces`; other messages are the controller's.

## Files

- `app/app/lib/project-cluster.ts` — new: `ProjectCluster` type, `getProjectCluster`, `setProjectCluster`, `clearProjectCluster`; reuses `clusterErrorMessage` and `clusterFieldErrors`.
- `app/app/components/deployment/cluster-link-card.tsx` — new: card "Cluster". Not linked: an `Empty` state explaining how to link, with a "Link cluster" button. Linked: cluster name (link to its page), namespaces as badges, "Edit" and "Unlink" (confirmation dialog). Archived projects show the card read-only.
- `app/app/components/deployment/cluster-link-form.tsx` — new: dialog with a cluster `Select` (from `listClusters`; if none, a message with a link to Clusters) and a namespaces field: loads the cluster's namespaces from `/namespaces` and offers them as a checkbox list, plus a text input to type a name not in the list (the api then answers with the controller's or its own message next to the field) (AC-32). Saves with `PUT`; field and server errors shown next to the input; a controller error from the namespace check appears unchanged.
- `app/app/routes/project-detail.tsx` — change: none in this task (the tab is added in task 13); the card is exported for it.

## Steps

1. Write the lib and the card.
2. Write the form; disable Save with no namespace chosen (the api also rejects it).
3. On success revalidate and `toast.success("Cluster linked")`.
4. Stacked layout at phone width (AC-31).

## Tests

- Manual: link with a managed namespace and see it saved (AC-10); try an unmanaged name and see the message with nothing saved (AC-32); unlink removes it (AC-10); 375 px (AC-31).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the files changed

## Out of scope

- The Deployment tab and its views (task 13).
