---
feature: 007-bugs-updates
task: 04-api-ticket-details
layer: api
status: approved
approved: 2026-11-12
branch: feat/007-bugs-updates/04-api-ticket-details
base: feat/007-bugs-updates/03-api-ticket-workspace
covers: [AC-27, AC-28, AC-35]
---

# 04 — Details and approval

## Goal

Read `details.md`, report `hasFindings`, and let the user approve the investigation, which switches the ticket to `implementer` mode.

## Contract

From [task-breakdown.md](../../task-breakdown.md), unchanged: `GET …/tickets/{tid}/details` → `{ path, content, hasFindings }` (`409 workspace_missing`); `POST …/tickets/{tid}/approve` → Ticket with `mode` `implementer` (`409 workspace_missing`, `409 no_findings`, `409 already_approved`, `409 project_archived`); Ticket fields `mode`, `approvedAt`, `hasFindings`; the `approve` event.

## Files

Under `api/src/main/kotlin/com/toptier/custom/ticket/`:

- `service/TicketDetails.kt` — new: reads the file, computes `hasFindings`, approves.
- `service/TicketService.kt` — change: response mapping calls `TicketDetails.hasFindings`.
- `dto/TicketDtos.kt` — change: `TicketResponse.mode`, `approvedAt`, `hasFindings` filled; new `TicketDetailsResponse`.
- `exception/TicketExceptions.kt` — change: `WorkspaceMissingException`, `NoFindingsException`, `AlreadyApprovedException`.
- `controller/TicketController.kt` — change: the two routes.
- `controller/TicketExceptionHandler.kt` — change: `workspace_missing`, `no_findings`, `already_approved` (409).

## Steps

1. `TicketDetails.read(ticket)`: requires `workspace_state` `ready` and a present worktree (via `TicketWorktrees`), else `WorkspaceMissingException`. Resolve `<worktree>/<folder>/details.md`, check the real path stays inside the worktree, read at most 200 KB as UTF-8. `FeatureFiles` shows how the feature file routes cap and read files; follow it.
2. `hasFindings` = SHA-256 of the file differs from `details_baseline`; false when there is no workspace, no worktree or no file. The list and get responses use it (a read error counts as false, logged).
3. `approve`: refuse archived project; `WorkspaceMissingException` without a ready workspace; `AlreadyApprovedException` when `mode` is already `implementer`; `NoFindingsException` when `hasFindings` is false. Then set `mode` `implementer` and `approved_at` and write an `approve` event (`detail`: "Investigation approved"), in one transaction. The mode is never set back (the ticket keeps it after `reopened`).
4. Routes and handler entries as above. `GET …/details` has no archived restriction.

## Tests

- `TicketDetailsTests` — template content gives `hasFindings` false; after the file changes it is true (AC-27); missing file or worktree gives false; `details` route returns path and content; no workspace → `workspace_missing`.
- Approve: refused with `no_findings` on the template (AC-27); accepted after a change, `mode` `implementer`, `approvedAt` set, one `approve` event (AC-28); second approve → `already_approved`; archived → `project_archived` (AC-35).
- `TicketControllerTests` (extend) — `401` on both routes and the error codes.

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`

## Out of scope

- The agent writing `details.md` (08), the buttons (11).
