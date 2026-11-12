---
feature: 007-bugs-updates
task: 11-app-ticket-workspace
layer: app
status: approved
approved: 2026-11-12
branch: feat/007-bugs-updates/11-app-ticket-workspace
base: feat/007-bugs-updates/10-app-ticket-detail
covers: [AC-23, AC-24, AC-25, AC-27, AC-28, AC-29, AC-30, AC-31, AC-32, AC-33, AC-34, AC-35, AC-37]
---

# 11 — Workspace panel

## Goal

Show the ticket's workspace on its page: state and retry, `details.md` as markdown, and the Approve, Commit, Push and Create pull request actions.

## Contract

Consumes `POST …/tickets/{tid}/workspace`, `GET …/details`, `POST …/approve`, `POST …/commit`, `POST …/push`, `POST …/pull` and the history route from [task-breakdown.md](../../task-breakdown.md), unchanged. The Ticket JSON gives `workspace.state`, `workspace.error`, `mode`, `approvedAt`, `hasFindings`, `pullRequestUrl`, `pullNumber`. The API's contract has no "pushed" flag: the panel treats the branch as pushed with commits when the history has a `push` event.

## Files

Under `app/app/`:

- `components/tickets/ticket-workspace.tsx` — new: the panel; takes an optional `side` slot for the agent window (used in task 12) and a `refreshKey`.
- `components/tickets/details-pane.tsx` — new: loads and renders `details.md`.
- `components/tickets/workspace-actions.tsx` — new: the four buttons and their dialogs.
- `routes/ticket-detail.tsx` — change: render the panel between the header and the grid.
- `lib/tickets.ts` — change: nothing new expected beyond calls added in task 09.

## Steps

1. `ticket-workspace.tsx` by `workspace.state`:
   - `none` → nothing at all is rendered: no markdown pane, no agent window, no actions (AC-24).
   - `failed` → an `Alert` with the stored `workspace.error` and a **Retry** button calling `retryWorkspace`; success revalidates; failure shows the new detail (AC-23).
   - `ready` → the grid `lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]` (one column at phone width, the `side` slot below the markdown); a missing worktree (`workspace.worktree.missing`) shows a warning as `feature-detail.tsx` does.
2. `details-pane.tsx`: `getDetails` on mount and whenever `refreshKey` changes; renders `content` with `Markdown` from `components/pull-requests/markdown.tsx` inside a `Card` titled "Details" with the folder path; skeleton while loading; error `Alert` with Try again; wide tables and code scroll inside the card (AC-25, AC-37).
3. `workspace-actions.tsx` under the markdown:
   - **Approve**: disabled unless `hasFindings` and `mode` is `investigator`, with the reason in text ("The agent hasn't written findings yet." / already approved shows an "Approved" badge with the date) (AC-27). Click calls `approveTicket`, then revalidates; `no_findings` shows the server detail (AC-28).
   - **Commit**: opens a dialog with a message input (default `#<number> <title>`, max 200); `commitTicket`; `committed: false` shows "Nothing to commit." (AC-29); success toast with the short SHA.
   - **Push**: `pushTicket`; success toast; errors (`push_rejected`, `nothing_committed`, git errors) show as an `Alert` with Try again (AC-30).
   - **Create pull request**: hidden and replaced by the pull request link when `pullNumber` is set (AC-32). Otherwise disabled with the reason "Push the branch first." until the history has a `push` event (AC-33); enabled opens a dialog with an editable title (default `#<number> <title>`); when `pullRequestUrl` is set by hand a confirmation line "This replaces the link you entered." sends `replaceLink: true`. Success shows the link and revalidates (AC-31). `nothing_to_compare`, `branch_not_pushed` and GitHub errors show their detail and leave the button available for retry (AC-33, AC-34).
   - Every action disables itself while any action is running. For an archived project all four are disabled with "Archived projects can't be changed." (AC-35).
4. The panel exposes `onChanged` used by actions to revalidate the ticket and history (the push event enables the pull request button).
5. All buttons are real `Button`s with text labels, usable by keyboard; dialogs trap focus (Base UI `Dialog`).

## Tests

No app test runner; checks plus a manual pass against a running API:

- A ticket never in progress shows no workspace area (AC-24).
- Moving an open ticket to In progress creates the workspace and shows `details.md` rendered (AC-25). With a failing push (e.g. no token) the alert and Retry appear; Retry completes it (AC-23).
- Approve is disabled on the template and enabled after the agent (or a manual edit in the worktree) changes `details.md`; clicking it shows Approved and the history entry (AC-27, AC-28).
- Commit with and without changes (AC-29); Push, and a failure (AC-30).
- Create pull request disabled before the push with the reason; works after; the link replaces the button; a second attempt is not offered (AC-31, AC-32, AC-33); GitHub failure shows the error and retry works (AC-34).
- Archived project: actions disabled (AC-35). 375 px: markdown, buttons and dialogs without horizontal scrolling (AC-37).

## Checks

- `docker exec skeleton-app npm run typecheck`
- `docker exec skeleton-app npm run build`
- `docker exec skeleton-app npx prettier --check <files>`

## Out of scope

- The agent chat itself (12), links to the tracker (13).
