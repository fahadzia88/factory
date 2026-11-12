---
feature: 007-bugs-updates
task: 10-app-ticket-detail
layer: app
status: draft
approved:
branch: feat/007-bugs-updates/10-app-ticket-detail
base: feat/007-bugs-updates/09-app-ticket-list
covers: [AC-5, AC-6, AC-13, AC-14, AC-15, AC-17, AC-18, AC-35, AC-36, AC-37]
---

# 10 — Ticket detail page

## Goal

The ticket page: fields and editing, the status control with descriptions and only the allowed statuses, and the history.

## Contract

Consumes `GET …/tickets/{tid}`, `PATCH …/tickets/{tid}`, `POST …/tickets/{tid}/status`, `GET …/tickets/{tid}/history` and `GET …/tickets/statuses` from [task-breakdown.md](../../task-breakdown.md), unchanged. The app never recomputes the transition table: the buttons come from `allowedStatuses`. Route added: `projects/:id/tickets/:ticketId`.

## Files

Under `app/app/`:

- `routes/ticket-detail.tsx` — new: loader, page.
- `components/tickets/ticket-details-card.tsx` — new: the fields and the edit dialog.
- `components/tickets/ticket-edit-form.tsx` — new: title, description, priority, pull request link.
- `components/tickets/ticket-status-control.tsx` — new: current status with its description and the allowed changes.
- `components/tickets/ticket-history.tsx` — new: the event list.
- `routes.ts` — change: add `projects/:id/tickets/:ticketId` after `projects/:id/tickets/new`.
- `components/tickets/ticket-form.tsx` / `routes/ticket-new.tsx` — change: after create navigate to the new ticket's page.

## Steps

1. `clientLoader` (hydrate, `HydrateFallback`): parse ids with `projectId`/`ticketId`; load project, ticket, history and statuses with `Promise.allSettled`. 404 `project_not_found` or `ticket_not_found` (also another project's ticket) renders `TicketNotFound` (AC-36); other errors show an `Alert` with Try again for 5xx.
2. Header: `#number title`, type, status and priority badges. Breadcrumb project → Tickets → `#number`.
3. Details card: type, priority, status with its description (from `statusDescription`), description (preserve line breaks; "No description" when empty), pull request link (external link, "None" when empty), created and updated dates. "Edit" opens a `Dialog` with `ticket-edit-form` (title, description, priority, link). Submit calls `editTicket` with only the changed fields; `invalid_ticket` errors show in the dialog and the dialog stays open (AC-15); success closes it, toasts, and revalidates. Type is shown but not editable.
4. Status control: shows the current status and its description; one button per entry of `allowedStatuses`, each labelled with `statusLabel` and with its description (from the statuses route) in a tooltip/`title` and visible text under it on touch screens (AC-14). Clicking calls `changeTicketStatus`; the buttons disable while sending; `invalid_transition` shows the server's detail and revalidates; success revalidates and shows a toast. A closed improvement or an archived project has no buttons (empty `allowedStatuses`) and says why (AC-13).
5. History card: events oldest first with time, a sentence per kind (`created`, `status` "Status: Filed → Open", `edit` with field and old → new, `workspace`, `approve`, `commit`, `push`, `pull` with `detail`), long values truncated with wrap (AC-17, AC-18). Empty state is not possible (always a `created` event).
6. Archived project: an `Alert` like the list page; Edit and the status control are hidden or disabled (AC-35). After any change `useRevalidator().revalidate()` reloads ticket and history.
7. Layout: a two-column grid like `feature-detail.tsx` (`lg:grid-cols-[minmax(0,20rem)_minmax(0,1fr)]`): the details and status cards on the left, history on the right; one column at phone width (AC-37). Leave the area between the header and the grid free for the workspace panel added in task 11.

## Tests

No app test runner; checks plus a manual pass:

- Page shows fields, status description and history for any ticket (AC-5).
- Walk a ticket from Filed to Closed with the buttons; each change is accepted and shown (AC-6). Only the allowed buttons appear: a Filed bug offers Open, Incorrect expectation, Out of scope, Backlog, Closed.
- An improvement never shows Reopened or Incorrect expectation; a closed improvement has no buttons (AC-13).
- Status descriptions are visible (AC-14).
- Edit each field; a bad link shows the error and nothing changes (AC-15).
- A new ticket's history has one creation entry (AC-17); moves and edits appear oldest first with old and new values (AC-18).
- Archived project is read-only (AC-35); unknown ticket and other project's ticket show not found (AC-36).
- 375 px without horizontal scrolling (AC-37).

## Checks

- `docker exec skeleton-app npm run typecheck`
- `docker exec skeleton-app npm run build`
- `docker exec skeleton-app npx prettier --check <files>`

## Out of scope

- Workspace panel, markdown, approve/commit/push/pull request buttons (11), the agent window (12).
