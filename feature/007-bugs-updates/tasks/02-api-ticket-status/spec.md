---
feature: 007-bugs-updates
task: 02-api-ticket-status
layer: api
status: draft
approved:
branch: feat/007-bugs-updates/02-api-ticket-status
base: feat/007-bugs-updates/01-api-ticket-foundation
covers: [AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12, AC-13, AC-14, AC-18, AC-19, AC-35]
---

# 02 — Status rules and status change

## Goal

The transition table by ticket type, `allowedStatuses` on the Ticket JSON, the statuses route with descriptions, and `POST …/status` with a history event.

## Contract

From [task-breakdown.md](../../task-breakdown.md), unchanged: the transition table and status descriptions, `GET …/tickets/statuses` → `[{ status, description, types }]`, `POST …/tickets/{tid}/status` `{ "status" }` → `200` Ticket, errors `400 invalid_ticket` (unknown status) and `409 invalid_transition` (problem properties `from`, `to`, `allowed`), `409 project_archived`.

## Files

Under `api/src/main/kotlin/com/toptier/custom/ticket/`:

- `service/TicketStatusRules.kt` — new: `object` holding the table; `allowed(type, from): List<TicketStatus>`, `usableBy(type): List<TicketStatus>`.
- `service/TicketService.kt` — change: `changeStatus(projectId, ticketId, status)`.
- `dto/TicketDtos.kt` — change: `TicketResponse.allowedStatuses`; new `StatusInfoResponse`, `SetTicketStatusRequest`.
- `exception/TicketExceptions.kt` — change: `InvalidTransitionException(from, to, allowed)`.
- `controller/TicketController.kt` — change: `GET /statuses`, `POST /{tid}/status`.
- `controller/TicketExceptionHandler.kt` — change: map `invalid_transition` 409 with the three properties.

## Steps

1. `TicketStatusRules`: encode the table exactly as in the breakdown. `closed` is allowed from every status but `closed`; `reopened` and `incorrect_expectation` exist only for bugs; improvements in `closed` and `deployed`/`closed` have no `reopened`. Return statuses in the table's order.
2. `changeStatus`: load the ticket; refuse an archived project; parse the status value (unknown → `InvalidTicketException`); if the target is not in `allowed(type, current)` throw `InvalidTransitionException` before anything is written; else save the new status, bump `updatedAt`, and write one `status` event (`field` `status`, old and new values) in the same transaction.
3. `allowedStatuses` in the response is `allowed(type, status)`, or empty when the project is archived. The project's archived flag is read once per response mapping.
4. `GET /statuses` is declared before `/{tid}` in the controller and lists every status with its description and the types that can use it, in the table's order. It needs no project state other than a valid project id (`project_not_found` otherwise).
5. Leave a clearly named seam in `changeStatus` (a single call after the save, e.g. `onStatusChanged(ticket, old)`, empty here) where task 03 starts the workspace; do not add workspace code.

## Tests

- `TicketStatusRulesTests` — every row of the table for both types, including that `closed` is reachable from all other statuses, improvements never see `reopened` or `incorrect_expectation`, and a closed improvement has none (AC-6, AC-8, AC-9, AC-10, AC-11, AC-12, AC-13).
- `TicketServiceTests` (extend) — full chain `filed` → `open` → `in_progress` → `in_review` → `merged` → `deployed` → `closed` writes one event per step (AC-6, AC-18); `filed` → `in_progress` and → `merged` are rejected and the status and history are unchanged (AC-7, AC-19); bug `deployed`/`closed` → `reopened` → `in_progress`; bug in `merged` → `reopened` rejected (AC-11, AC-12); improvement → `reopened` rejected (AC-13); archived project refuses the change but reads work (AC-35).
- `TicketControllerTests` (extend) — `401`; `409 invalid_transition` body has `from`, `to`, `allowed`; `GET /statuses` returns descriptions and types for all 11 statuses (AC-14); `allowedStatuses` is `[]` for an archived project.

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`

## Out of scope

- Creating the workspace on the first `in_progress` (03). The seam only.
- Any app change.
