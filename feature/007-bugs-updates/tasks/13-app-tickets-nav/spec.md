---
feature: 007-bugs-updates
task: 13-app-tickets-nav
layer: app
status: approved
approved: 2026-11-12
branch: feat/007-bugs-updates/13-app-tickets-nav
base: feat/007-bugs-updates/12-app-ticket-chat
covers: [AC-3, AC-37]
---

# 13 — Link the tracker

## Goal

Make the tracker reachable: a "Tickets" tab on the project page. This is the last task, so nothing user-facing shows before everything works.

## Contract

Consumes `GET /api/projects/{id}/tickets` from [task-breakdown.md](../../task-breakdown.md), unchanged. Links to the existing routes `projects/:id/tickets`, `projects/:id/tickets/new` and `projects/:id/tickets/:ticketId`.

## Files

Under `app/app/`:

- `components/tickets/tickets-card.tsx` — new: the project page card.
- `routes/project-detail.tsx` — change: add a "Tickets" tab beside Features, Pull requests and Worktrees.

## Steps

1. `TicketsCard({ projectId, archived })`, modelled on `FeaturesCard`: loads the project's tickets with `listTickets` (no filters) and shows the most recent 5 as rows (`#number`, title, type and status badges) linking to the ticket page; skeleton while loading; error `Alert` with Try again for 5xx.
2. Header actions: "View all" linking to `/projects/:id/tickets` and, unless archived, "New ticket" linking to `/projects/:id/tickets/new`. Empty state: "No tickets yet" with the create action (AC-3); an archived project says "Archived projects can't get new tickets." and hides it.
3. `project-detail.tsx`: a `TabsTrigger` "Tickets" (lucide `BugIcon`) and `TabsContent` rendering the card, placed after Features.
4. There is no global navigation item: tickets belong to a project, and the header `NAV_ITEMS` in `app-layout.tsx` stays unchanged. The ticket pages' breadcrumbs already lead back to the project and its list.
5. The card works at 375 px (rows wrap, no horizontal scrolling) (AC-37).

## Tests

No app test runner; checks plus a manual pass:

- Project page shows the Tickets tab; with no tickets the empty state and create action appear (AC-3).
- Create a ticket from the tab; it shows in the card and the full list; links reach the detail page and back.
- Archived project: no create action.
- 375 px: the tab and card without horizontal scrolling (AC-37).

## Checks

- `docker exec skeleton-app npm run typecheck`
- `docker exec skeleton-app npm run build`
- `docker exec skeleton-app npx prettier --check <files>`

## Out of scope

- A header nav entry (tickets are per project), dashboard widgets, any API or agent change.
