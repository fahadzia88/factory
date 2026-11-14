---
feature: 008-deployment-and-monitoring
task: 15-app-control-actions
layer: app
status: approved
approved: 2026-11-14
branch: feat/008-deployment-and-monitoring/15-app-control-actions
base: feat/008-deployment-and-monitoring/14-app-pod-logs
covers: [AC-19, AC-20, AC-21, AC-22, AC-23, AC-26, AC-28, AC-31]
---

# 15 — Control actions with confirmation and progress

## Goal

Restart, scale, change image and restart pod from the workload and pod views, with confirmation, read-only handling and progress polling.

## Contract

Consumes the change endpoints from the breakdown (`restart`, `scale`, `image`, pod `restart?force`) and `GET .../workloads/{ns}/{kind}/{name}` for polling. `409` on a pod restart means no owner. `403` disables the control.

## Files

- `app/app/lib/deployment.ts` — change: `restartWorkload`, `scaleWorkload`, `setWorkloadImage`, `restartPod(…, force?)`.
- `app/app/hooks/use-workload-progress.ts` — new: after a change, polls the workload every 2.5 s until `ready`, `failed` or `scaled-down`; stops at 5 minutes with "Timed out waiting for the workload. It may still be rolling out." Stops on unmount; a 404 stops with the controller's message and revalidates the list (AC-28). Returns `{ status, workload, timedOut, error }`.
- `app/app/components/deployment/workload-actions.tsx` — new: buttons Restart, Scale, Change image. Each destructive path opens a confirmation `Dialog`: restart (always), scale (always when the new value is 0; for other values a plain Apply), image change (always), shows what will change (AC-19, AC-20, AC-21). Scale input is a number field, 0-100, validated next to the input (AC-20). Image form: image text, and a container `Select` shown (and required) when the workload has more than one image entry (AC-21). A `400` message from the api is shown next to the input.
- `app/app/components/deployment/progress-banner.tsx` — new: shows "Rolling out…", replicas and the final status; text, not only color.
- `app/app/components/deployment/pod-actions.tsx` — new: "Restart pod" shown on a pod with a `problem` (and on the pod page); confirmation dialog; on `409` a second dialog offers "Delete anyway" (retry with `force=true`) and only does so after that confirmation (AC-22, AC-23).
- `app/app/components/deployment/read-only-note.tsx` — new: when `status.readOnly` is true the action buttons are hidden and a note explains the cluster is read-only; a `403` from an action disables the control and shows the message (AC-26).
- `app/app/components/deployment/workload-detail.tsx`, `app/app/routes/project-pod.tsx` — change: mount the actions and the banner.

## Steps

1. Write the lib calls and the progress hook.
2. Write the actions with confirmation dialogs (keyboard operable, focus on the safe button).
3. After a successful change, start the progress hook and refresh the list on completion.
4. StatefulSet case: on a workload with a pod `problem`, show "Restart pod" next to that pod, with a hint that a StatefulSet doesn't replace a pod stuck on a bad image (AC-22).
5. Show every error with the api's `detail` unchanged.
6. Phone width: buttons wrap, dialogs fit (AC-31).

## Tests

- Manual: restart with confirmation follows progress to ready (AC-19); an unreachable image ends in failed; leave it open for 5 minutes (or temporarily set a shorter timeout locally) to see the timeout message (AC-19); scale 0 asks to confirm, 101 is refused next to the input (AC-20); multi-container pod requires a container (AC-21); StatefulSet with a bad image then a fixed image shows "Restart pod" and it works (AC-22); ownerless pod gives the force prompt (AC-23); read-only cluster hides controls and a forced 403 shows its message (AC-26); delete a workload then act on it: message and refreshed list (AC-28); 375 px (AC-31).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the files changed

## Out of scope

- Apply and create forms (task 16).
