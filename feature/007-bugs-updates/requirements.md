---
feature: 007-bugs-updates
status: draft
approved:
---

# bugs-updates

## Problem

There is no way to track bugs and improvements for a project's repository. Work is requested and followed outside the app, so nobody can see what is filed, what is being fixed, what has shipped, or how a ticket got to its current state. Investigating and fixing a ticket also happens away from the ticket itself.

## Users

People who work on a project and want to file a bug or an improvement, move it through its life, see its history, and investigate and fix it with an agent.

## Scope

- A ticket tracker per project: create, list, view and edit tickets.
- Ticket type: `bug` or `improvement`. Each ticket has a per-project ticket number.
- Ticket fields: title (required), description (optional), priority (`low`, `medium`, `high`, `critical`), optional pull request link, type, status.
- Statuses, each with a short description shown in the app:
  - `filed` — Reported, not yet looked at.
  - `open` — Accepted and ready to be worked on.
  - `in progress` — Someone is working on it.
  - `in review` — The fix or change is waiting for review.
  - `merged` — The change is merged and not yet released.
  - `deployed` — The change is released.
  - `closed` — Finished or dropped; no more work planned.
  - `reopened` — Bugs only. A deployed or closed bug that needs more work.
  - `incorrect expectation` — Bugs only. The behaviour is as designed; the report was a misunderstanding.
  - `out of scope` — Will not be done as part of this project.
  - `backlog` — Valid, but parked for later.
- Status availability: bugs can use all statuses. Improvements cannot use `reopened` or `incorrect expectation`.
- Status flow (one step at a time):
  - `filed` → `open` → `in progress` → `in review` → `merged` → `deployed` → `closed`
  - `filed` → `incorrect expectation` (bugs only), `out of scope`, or `backlog`
  - `backlog` → `open`
  - `deployed` → `reopened` and `closed` → `reopened` (bugs only); `reopened` → `in progress`
  - Any status → `closed`
  - Nothing else is allowed. `closed` is final for improvements.
- A history of every change to a ticket (creation, status changes, edits to any field, worktree and push results, approval), kept by the API and shown on the ticket.
- A ticket detail page for every ticket, showing its fields, status and its description, available status changes, and its history.
- Ticket workspace, created the first time a ticket becomes `in progress`:
  - A worktree and branch for the ticket, pushed to the remote.
  - A folder `feature/bugs/<ticket number>-<title slug>/` for bugs or `feature/updates/<ticket number>-<title slug>/` for improvements, containing `details.md`, committed in the worktree.
  - On later `in progress` changes (for example after `reopened`) the existing worktree and folder are reused.
- On the detail page once the workspace exists: `details.md` rendered as markdown, with an agent chat window to its right.
  - The agent starts in investigator mode: it can read the codebase and write its findings into `details.md`, and changes nothing else.
  - An **Approve** button: the user accepts the investigation; the agent then switches to implementer mode and can change code in the worktree. Approve is disabled while `details.md` has no findings written by the agent.
  - A **Commit** button commits the worktree changes; a **Push** button pushes the branch.
  - A **Create pull request** button opens a pull request on GitHub for the pushed branch, against the default branch, and saves its link on the ticket.
- API endpoints for the above, and the tracker UI in the app on the project's page.

## Non-goals

- Comments, assignees, labels, attachments, notifications.
- Syncing with GitHub Issues or pull request state (status, reviews, merges); the ticket only stores the pull request link, and the user moves the status.
- Merging or closing pull requests from the ticket page.
- Deleting tickets, changing a ticket's type after creation.
- A tracker across projects.

## Assumptions

- Tickets belong to exactly one project and are shown within it.
- Priority defaults to `medium` when not given.
- The ticket number counts up from 1 within a project. The slug is made from the title when the ticket first becomes `in progress` and does not change if the title is edited later.
- The worktree is based on the project's default branch. Its branch name is `bugs/<ticket number>-<slug>` or `updates/<ticket number>-<slug>`.
- History entries are immutable and record time, the field changed, and the old and new values. There are no user accounts for tickets, so no author is recorded.
- The pull request link must be a valid http(s) URL when given.
- The agent chat for a ticket is kept with the ticket and reuses the existing feature chat mechanism.
- After `reopened` → `in progress`, the agent stays in the mode it was in (implementer if the user had approved).
- Commit uses a message the user can edit, with a default such as the ticket number and title.
- Create pull request uses a title the user can edit (default `#<number> <title>`) and a body made from the ticket's description with the ticket number. It does not change the ticket's status.
- A ticket has at most one pull request created this way; a link edited by hand can be replaced by it only with the user's confirmation.

## Constraints

- Follows the existing api and app conventions; existing project pages keep working.
- Worktrees, commits and pushes go through the API (the agent has no git or GitHub token), as for features.
- Tickets of an archived project stay viewable; creating or changing them, and workspace actions, are refused.

## Non-functional requirements

- Ticket changes and their history entries are written in one transaction.
- Rejected status changes return a clear error and leave the ticket and its history unchanged.
- A failed worktree creation or push is shown clearly and can be retried without duplicating anything.
- The tracker list and ticket page work at phone width; on a narrow screen the agent window can sit below the markdown.
- Controls are keyboard-usable and labelled.

## Open questions

- None.

## Acceptance criteria

- **AC-1** — Given a project, when the user creates a ticket with a title and a type, then it appears in the project's ticket list with the next ticket number, status `filed`, priority `medium`, and the chosen type.
- **AC-2** — Given the create form, when the title is empty or the type is missing, then the ticket is not created and a validation error is shown.
- **AC-3** — Given a project with no tickets, when the user opens its tracker, then an empty state is shown with a way to create a ticket.
- **AC-4** — Given tickets of both types and several statuses, when the user opens the tracker, then each ticket shows its number, title, type, status and priority, and the list can be filtered by type and by status.
- **AC-5** — Given any ticket, when the user opens it, then a detail page shows its fields, its status with that status's description, and the history.
- **AC-6** — Given a ticket in `filed`, when the user moves it through `open`, `in progress`, `in review`, `merged`, `deployed` and `closed` in turn, then each change is accepted and the page shows the new status.
- **AC-7** — Given a ticket in `filed`, when a change straight to `in progress` or `merged` is requested, then it is rejected with an error and the status stays `filed`.
- **AC-8** — Given a ticket in `filed`, when the user moves it to `out of scope` or `backlog`, then it is accepted; and moving a bug to `incorrect expectation` is accepted too.
- **AC-9** — Given a ticket in `backlog`, when the user moves it to `open`, then it is accepted; from `out of scope` or `incorrect expectation` the only allowed change is `closed`.
- **AC-10** — Given a ticket in any status except `closed`, when the user moves it to `closed`, then it is accepted.
- **AC-11** — Given a bug in `deployed` or `closed`, when the user reopens it, then its status becomes `reopened`; and from `reopened` the only allowed changes are `in progress` and `closed`.
- **AC-12** — Given a bug in a status other than `deployed` or `closed`, when a change to `reopened` is requested, then it is rejected and the status is unchanged.
- **AC-13** — Given an improvement, when a change to `reopened` or `incorrect expectation` is requested, then it is rejected, and the app does not offer them; and an improvement in `closed` has no further changes.
- **AC-14** — Given the status list in the app, when the user views a status (in the status control or on the page), then its description is shown.
- **AC-15** — Given a ticket, when the user edits its title, description, priority or pull request link, then the ticket shows the new values; and a pull request link that is not an http(s) URL is rejected with a validation error.
- **AC-16** — Given a ticket's type, when a change to it is requested through the API, then the request is rejected and the type is unchanged.
- **AC-17** — Given a new ticket, when the user opens its history, then it has one entry for creation with the time and the initial values.
- **AC-18** — Given a ticket that was moved and edited, when the user opens its history, then every status change and field edit appears, oldest first, each with its time, the field, and the old and new value.
- **AC-19** — Given a rejected status change or an invalid edit, when the user opens the history, then no entry was added for it.
- **AC-20** — Given an `open` bug, when the user moves it to `in progress` for the first time, then a worktree and branch `bugs/<number>-<slug>` are created from the default branch, the folder `feature/bugs/<number>-<slug>/` with `details.md` is committed and the branch is pushed, and the history records each result.
- **AC-21** — Given an `open` improvement, when the user moves it to `in progress` for the first time, then the same happens with branch `updates/<number>-<slug>` and folder `feature/updates/<number>-<slug>/`.
- **AC-22** — Given a ticket that already has a workspace, when it becomes `in progress` again, then the existing worktree, branch and folder are reused and no new ones are created.
- **AC-23** — Given creating the worktree or pushing fails on the first `in progress`, when the change is made, then the status still changes, the failure is shown on the page and recorded in the history, and a retry action completes the workspace without creating duplicates.
- **AC-24** — Given a ticket that has never been `in progress`, when the user opens its page, then no markdown pane or agent window is shown.
- **AC-25** — Given a ticket with a workspace, when the user opens its page, then `details.md` is shown as rendered markdown with the agent chat window to its right.
- **AC-26** — Given the agent in investigator mode, when the user asks it to investigate, then it can read the codebase and update `details.md`, the page shows the updated content, and it makes no other change.
- **AC-27** — Given `details.md` with no findings written by the agent, when the user views the page, then **Approve** is disabled.
- **AC-28** — Given findings in `details.md`, when the user clicks **Approve**, then the approval is recorded in the history and the agent switches to implementer mode, where it can change code in the worktree.
- **AC-29** — Given changes in the worktree, when the user clicks **Commit** with a message, then they are committed to the ticket's branch and the history records it; with no changes, nothing is committed and the user is told.
- **AC-30** — Given a committed branch, when the user clicks **Push**, then the branch is pushed, and a failure is shown with the error and can be retried.
- **AC-31** — Given a pushed branch and no pull request yet, when the user clicks **Create pull request** (optionally editing the title), then a pull request is opened on GitHub from the ticket's branch into the default branch, its link is saved on the ticket and shown on the page, and the history records it.
- **AC-32** — Given a ticket that already has a created pull request, when the user opens its page, then the link is shown and **Create pull request** is not offered again; no duplicate is created.
- **AC-33** — Given a branch that has not been pushed or has no commits beyond the default branch, when the user views the page, then **Create pull request** is disabled with the reason shown.
- **AC-34** — Given GitHub rejects or cannot be reached when creating the pull request, when the user clicks the button, then the error is shown, no link is saved, and the user can retry.
- **AC-35** — Given an archived project, when the user opens its tracker, then tickets and history are viewable, and creating, editing, status changes and workspace actions (including creating a pull request) are refused.
- **AC-36** — Given a ticket id that does not exist or belongs to another project, when it is requested, then the API answers not found and the app shows a not-found message.
- **AC-37** — Given a phone-width screen, when the user uses the tracker list, create form and ticket page, then everything is reachable without horizontal scrolling and the agent window sits below the markdown.

## Changelog

- 2026-11-11 — Created.
- 2026-11-11 — Drafted from the request and answers (per project; guided flow; priority and pull request link).
- 2026-11-11 — Revised: more statuses with descriptions and a new flow (`reopened` and `incorrect expectation` bug-only), detail page, ticket workspace with worktree, `details.md`, investigator agent, Approve/Commit/Push.
- 2026-11-11 — Added a Create pull request button (AC-31 to AC-34); later ACs renumbered.
