---
feature: 007-bugs-updates
task: 01-api-ticket-foundation
layer: api
status: approved
approved: 2026-11-12
branch: feat/007-bugs-updates/01-api-ticket-foundation
base: feat/007-bugs-updates/00-docs
covers: [AC-1, AC-2, AC-4, AC-15, AC-16, AC-17, AC-18, AC-19, AC-35, AC-36]
---

# 01 — Ticket foundation

## Goal

The `ticket` package: tickets and their history in the database, with create, list (type and status filters), get, edit and the history route. Status changes, workspace, chat and the rest come in later tasks.

## Contract

From [task-breakdown.md](../../task-breakdown.md), unchanged: the Ticket and Event JSON, the database tables `ticket` and `ticket_event`, and these routes under `/api/projects/{id}/tickets`: `GET` (list, `type` and `status` filters), `POST`, `GET /{tid}`, `PATCH /{tid}`, `GET /{tid}/history`. In this task the fields owned by later tasks have their resting values: `allowedStatuses: []`, `workspace: { state: "none", error: null, branch: null, folder: null, worktree: null }`, `mode: "investigator"`, `approvedAt: null`, `hasFindings: false`. `statusDescription` is already filled from the status enum.

## Files

All under `api/src/main/kotlin/com/toptier/custom/ticket/`:

- `domain/TicketType.kt`, `TicketPriority.kt`, `TicketStatus.kt` — new: enums with a lowercase JSON `value` (`in_progress`, …); `TicketStatus` also holds its `description` and which types may use it (bug only: `reopened`, `incorrect_expectation`).
- `domain/Ticket.kt`, `TicketRepository.kt` — new: entity and repository for table `ticket` with every column in the breakdown (`workspace_state` default `none`, `mode` default `investigator`); unique `(project_id, number)`; `findByProjectIdOrderByNumberDesc`, `findByIdAndProjectId`, `maxNumber(projectId)`.
- `domain/TicketEvent.kt`, `TicketEventRepository.kt` — new: entity and repository for `ticket_event`; `findByTicketIdOrderByCreatedAtAscIdAsc`.
- `dto/TicketDtos.kt` — new: `CreateTicketRequest`, `TicketResponse` (with nested `WorkspaceResponse`), `TicketEventResponse`.
- `exception/TicketExceptions.kt` — new: `TicketException` (sealed) with `TicketNotFoundException`, `InvalidTicketException`, `TicketTypeFixedException`, `TicketProjectArchivedException`.
- `service/TicketService.kt` — new: create, list, get, edit, history.
- `service/TicketHistory.kt` — new: writes events.
- `controller/TicketController.kt` — new: the five routes.
- `controller/TicketExceptionHandler.kt` — new: `@RestControllerAdvice(basePackageClasses = [TicketController::class])`.
- `api/src/main/resources/application.properties` — no change expected.

## Steps

1. Enums and entities as in `FeatureTask` (tabs, KDoc on every class, plain `Long` project id, `Instant` columns from the injected `Clock`). Status, type, priority are stored as strings of their `value`.
2. `TicketHistory.record(ticketId, kind, field, old, new, detail)` saves one `TicketEvent` with `clock.instant()`.
3. `TicketService` (`@Transactional` on writes):
   - `create(projectId, request)`: load the project via `ProjectService.get` (404 → `ProjectNotFoundException`), refuse an archived one with `TicketProjectArchivedException`; validate (title trimmed, 1–200 chars; description trimmed, blank → null, at most 10000; type required; priority default `medium`) throwing `InvalidTicketException` with a plain message; `number = maxNumber + 1`, retry once on `DataIntegrityViolationException`; status `filed`; record a `created` event whose `newValue` is the initial values as JSON (type, title, description, priority, status).
   - `list(projectId, type, status)`: unknown filter value → `InvalidTicketException`-like 400 `invalid_request`; number descending.
   - `get(projectId, ticketId)`: `findByIdAndProjectId` or `TicketNotFoundException` (another project's ticket is not found too).
   - `edit(projectId, ticketId, changes: JsonNode)`: refuse archived; a `type` key → `TicketTypeFixedException`; accept only `title`, `description`, `priority`, `pullRequestUrl` (any other key → `InvalidTicketException`); validate as in create; `pullRequestUrl` must parse as an `http`/`https` URL with a host, null or blank clears it. Validate everything first; then for each field that really changed write one `edit` event (`field`, old, new) and save. Nothing changed → return the ticket, no event.
   - `history(projectId, ticketId)`: events oldest first.
4. `TicketController`: `@RequestMapping("/api/projects/{id}/tickets")`; `POST` answers `201`; `PATCH` takes `JsonNode` so a missing field and `null` differ. Response mapping builds the resting values listed in Contract.
5. `TicketExceptionHandler`: `project_not_found` 404, `ticket_not_found` 404, `invalid_ticket` 400, `ticket_type_fixed` 400, `project_archived` 409, and `invalid_request` 400 for non-numeric ids, unreadable bodies and unknown filter values, in the style of `FeatureExceptionHandler`.
6. `ProjectNotFoundException` is handled in this advice too (it lives in the project package).

## Tests

Under `api/src/test/kotlin/com/toptier/custom/ticket/`:

- `TicketRepositoryTests` (`@DataJpaTest`) — unique `(project, number)`, ordering, `findByIdAndProjectId` with another project (AC-36).
- `TicketServiceTests` — create gives number 1 then 2, `filed`, `medium`, one `created` event with initial values (AC-1, AC-17); blank title and missing type rejected (AC-2); filters by type and status (AC-4); edit of each field writes one event with old and new, an edit that changes nothing writes none, invalid link and unknown key write none (AC-15, AC-18, AC-19); `type` in the body rejected (AC-16); archived project refuses create and edit but `get`, `list`, `history` work (AC-35); unknown and other-project ids (AC-36). Uses `MutableClock`.
- `TicketControllerTests` (`@WebMvcTest`, `app.security.api-token=test-token`) — `401` without the token on every route, status codes and `code` values above, `201` on create.

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`

## Out of scope

- Status changes and `allowedStatuses` (02), workspace, details and approval (03–04), commit, push, pull request (05–06), chat (07).
