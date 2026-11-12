---
feature: 007-bugs-updates
task: 09-app-ticket-list
layer: app
status: approved
approved: 2026-11-12
branch: feat/007-bugs-updates/09-app-ticket-list
base: feat/007-bugs-updates/08-agent-ticket-tools
covers: [AC-1, AC-2, AC-3, AC-4, AC-35, AC-36, AC-37]
---

# 09 — Ticket list and create form

## Goal

The tracker's list page with filters and an empty state, the create form, and the shared ticket types and API calls. Reachable only by URL until task 13.

## Contract

Consumes `GET /api/projects/{id}/tickets?type=&status=`, `POST /api/projects/{id}/tickets` and `GET …/tickets/statuses` from [task-breakdown.md](../../task-breakdown.md), unchanged. Routes added: `projects/:id/tickets` and `projects/:id/tickets/new`.

## Files

Under `app/app/`:

- `lib/tickets.ts` — new: `Ticket`, `TicketEvent`, `TicketDetails`, `StatusInfo` types as in the breakdown; `listTickets`, `getTicket`, `createTicket`, `editTicket`, `changeTicketStatus`, `listStatuses`, `getHistory`, `getDetails`, `approveTicket`, `commitTicket`, `pushTicket`, `openTicketPull`, `retryWorkspace` (all typed, only the first three plus `listStatuses` used here); `ticketError` (same shape as `featureError`), `ticketId(raw)` (positive integer or null), `statusLabel` (`in_progress` → "In progress", `incorrect_expectation` → "Incorrect expectation"), `TYPE_LABEL`, `PRIORITY_LABEL`.
- `components/tickets/ticket-badges.tsx` — new: `TicketTypeBadge`, `TicketStatusBadge`, `TicketPriorityBadge` using `StatusBadge`/`Badge` tones.
- `components/tickets/ticket-list.tsx` — new: list of tickets and the filters.
- `components/tickets/ticket-form.tsx` — new: the create form.
- `components/tickets/ticket-not-found.tsx` — new: like `feature-not-found.tsx`.
- `routes/ticket-list.tsx`, `routes/ticket-new.tsx` — new.
- `routes.ts` — change: add the two routes inside `app-layout` after `projects/:id/features/:featureId`.

## Steps

1. `lib/tickets.ts` follows `lib/features.ts`: `api.get/post/patch` from `lib/api.ts`, paths from `/api/projects/${projectId}/tickets`, `ticketError` reading `problemDetail` (message is the server's `detail`, `retryable` for 5xx).
2. `ticket-list.tsx` route: `clientLoader` (with `hydrate = true` and a `HydrateFallback`) loads the project and, in parallel, the tickets filtered by the `type` and `status` search params, and the statuses. Unknown project → `ProjectNotFound`; `project_not_found` → not-found page (AC-36).
3. List: each ticket is a row/card showing `#number`, title, type, status and priority badges, linking to `/projects/:id/tickets/:ticketId` (the page arrives in task 10). Two filters (type, status) built with `Select`; changing one updates the search params; a "Clear filters" action. Filters with no match show "No tickets match these filters" (AC-4).
4. Empty state (no tickets and no filters) uses `Empty` with a "New ticket" button (AC-3). The "New ticket" action is hidden for an archived project, which shows an `Alert` "This project is archived. Restore it to make changes." and the list stays visible (AC-35).
5. `ticket-form.tsx`: type (`RadioGroup`: Bug / Improvement, required), title (`Input`, required, max 200), description (`Textarea`), priority (`Select`, default Medium). Submit calls `createTicket`; `invalid_ticket` shows the server's message in an `Alert` above the form and the typed title shortfall inline; the submit button shows `Spinner` while sending (AC-2). On success navigate to `/projects/:id/tickets` with a toast "Ticket #n created" (task 10 later changes the destination to the new ticket's page). An archived project shows the archived alert instead of the form.
6. Phone width: filters stack, rows wrap, no horizontal scrolling at 375 px (AC-37). Controls are labelled; filters and the form work by keyboard.
7. Use `PageHeader`, `ProjectBreadcrumb` (page "Tickets"), and the patterns in `feature-new.tsx` for the form action.

## Tests

The app has no test runner (see `app/CLAUDE.md`); verification is the checks plus a manual pass:

- Create a bug and an improvement: each appears with the next number, status Filed, priority Medium (AC-1).
- Submit with an empty title: nothing is created and the error shows (AC-2).
- Empty project shows the empty state with a create action (AC-3).
- Filter by type and by status; combination with no match shows the message (AC-4).
- Archived project: list visible, no create button, form blocked (AC-35).
- `/projects/1/tickets/abc` and an unknown project show the not-found page; a ticket id from another project is handled in task 10 (AC-36).
- 375 px: list, filters and form without horizontal scrolling (AC-37).

## Checks

- `docker exec skeleton-app npm run typecheck`
- `docker exec skeleton-app npm run build`
- `docker exec skeleton-app npx prettier --check <files>`

## Out of scope

- The detail page (10), links to the tracker (13).
