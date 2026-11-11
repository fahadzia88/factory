---
feature: 007-bugs-updates
status: draft
approved:
---

# bugs-updates

## Problem

There is no way to track bugs and improvements for a project's repository. Work is requested and followed up outside the app, so nobody can see what is filed, what is being fixed, what has shipped, or how a ticket got to its current state.

## Users

People who work on a project and want to file a bug or an improvement, move it through its life, and see what happened to it.

## Scope

- A ticket tracker per project: create, list, view and edit tickets.
- Ticket type: `bug` or `improvement`.
- Ticket fields: title (required), description (optional), priority (`low`, `medium`, `high`, `critical`), optional pull request link, type, status.
- Status:
  - Bugs: `filed`, `in progress`, `in review`, `merged`, `deployed`, `reopened`.
  - Improvements: `filed`, `in progress`, `in review`, `merged`, `deployed` (no `reopened`).
- Guided status flow: a ticket moves one step forward at a time (`filed` → `in progress` → `in review` → `merged` → `deployed`). A bug in `merged` or `deployed` can be `reopened`, and a `reopened` bug can only go back to `in progress`.
- A history of every change to a ticket (creation, status changes, edits to any field), kept by the API and shown on the ticket.
- API endpoints for the above, and a tracker UI in the app on the project's page.

## Non-goals

- Comments, assignees, labels, attachments, notifications.
- Syncing with GitHub Issues or pull request state; the pull request link is just text the user supplies.
- Deleting tickets.
- Changing a ticket's type after creation.
- Agent (chat) tools for tickets.
- A tracker across projects.

## Assumptions

- Tickets belong to exactly one project and are shown within it.
- Backward moves other than `reopened` → `in progress` are not needed (for example `in review` back to `in progress`).
- Priority defaults to `medium` when not given.
- History entries are immutable and record time, the field changed, and the old and new values. The app has no user accounts for tickets, so no author is recorded.
- The pull request link must be a valid http(s) URL when given.

## Constraints

- Follows the existing api and app conventions; existing project pages keep working.
- Tickets of an archived project stay viewable; creating or changing them is refused. (Matches how archived projects behave elsewhere.)

## Non-functional requirements

- History is written in the same transaction as the change, so it never disagrees with the ticket.
- Rejected status changes return a clear error and leave the ticket and its history unchanged.
- The tracker list and ticket view work at phone width.
- Controls are keyboard-usable and labelled.

## Open questions

- None.

## Acceptance criteria

- **AC-1** — Given a project, when the user creates a ticket with a title and a type, then it appears in the project's ticket list with status `filed`, priority `medium`, and the chosen type.
- **AC-2** — Given the create form, when the title is empty or the type is missing, then the ticket is not created and a validation error is shown.
- **AC-3** — Given a project with no tickets, when the user opens its tracker, then an empty state is shown with a way to create a ticket.
- **AC-4** — Given tickets of both types and several statuses, when the user opens the tracker, then each ticket shows its title, type, status and priority, and the list can be filtered by type and by status.
- **AC-5** — Given a ticket in `filed`, when the user moves it to `in progress`, then to `in review`, `merged` and `deployed` in turn, then each change is accepted and the ticket shows the new status.
- **AC-6** — Given a ticket in `filed`, when a change straight to `merged` is requested, then it is rejected with an error and the status stays `filed`.
- **AC-7** — Given a bug in `merged` or `deployed`, when the user reopens it, then its status becomes `reopened`; and from `reopened` the only allowed change is to `in progress`.
- **AC-8** — Given a bug in `filed`, `in progress` or `in review`, when a change to `reopened` is requested, then it is rejected and the status is unchanged.
- **AC-9** — Given an improvement, when a change to `reopened` is requested, then it is rejected, and the UI does not offer `reopened`.
- **AC-10** — Given a ticket, when the user edits its title, description, priority or pull request link, then the ticket shows the new values.
- **AC-11** — Given a pull request link that is not an http(s) URL, when the user saves it, then it is rejected with a validation error.
- **AC-12** — Given a new ticket, when the user opens its history, then it has one entry for creation with the time and initial values.
- **AC-13** — Given a ticket that was moved and edited, when the user opens its history, then every status change and field edit appears, oldest first, each with its time, the field, and the old and new value.
- **AC-14** — Given a rejected status change or invalid edit, when the user opens the history, then no entry was added for it.
- **AC-15** — Given a ticket's type, when the user tries to change it through the API, then the request is rejected and the type is unchanged.
- **AC-16** — Given an archived project, when the user opens its tracker, then tickets and history are viewable, and creating, editing and status changes are refused.
- **AC-17** — Given a ticket id that does not exist, or one that belongs to another project, when it is requested, then the API answers not found and the app shows a not-found message.
- **AC-18** — Given a phone-width screen, when the user uses the tracker list, create form and ticket view, then everything is reachable without horizontal scrolling.

## Changelog

- 2026-11-11 — Created.
- 2026-11-11 — Drafted from the request and answers (per project; improvements skip `reopened`; guided flow; priority and pull request link).
