---
feature: 007-bugs-updates
task: 08-agent-ticket-tools
layer: agent
status: draft
approved:
branch: feat/007-bugs-updates/08-agent-ticket-tools
base: feat/007-bugs-updates/07-api-ticket-chat
covers: [AC-26, AC-28]
---

# 08 — Ticket tools

## Goal

Give the agent six `mcp__app__` tools to read a ticket and its worktree and to write `details.md` (investigator) or any file (implementer, after approval), plus a "Tickets" section in its instructions.

## Contract

From [task-breakdown.md](../../task-breakdown.md), unchanged: the tool table (`ticket_get`, `ticket_list_files`, `ticket_read_file`, `ticket_write_details`, `ticket_write_file`, `ticket_edit_file`, each with `projectId` and `ticketId`) and the path and mode rules. It consumes `GET /api/projects/{id}/tickets/{tid}` (Ticket JSON: `mode`, `workspace.folder`, `workspace.worktree`). No tool commits, pushes or opens a pull request; `chat.ts` keeps `tools: []`.

## Files

Under `agent/src/`:

- `ticket-api.ts` — new: `Ticket` type, `loadTicket`, `requireTicketWorktree`, `assertTicketWritable`, `detailsPath`.
- `tools/ticket-ids.ts` — new: `{ projectId, ticketId }` Zod fields (positive integers), like `feature-ids.ts`.
- `tools/ticket-get.ts`, `ticket-list-files.ts`, `ticket-read-file.ts`, `ticket-write-details.ts`, `ticket-write-file.ts`, `ticket-edit-file.ts` — new.
- `tools/ticket-write.ts` — new: `ticketWritablePath(projectId, ticketId, rel)` shared by the two write tools, like `task-write.ts`.
- `agent.ts` — change: import and add the six tools to `tools` (shared base file: additive, confirm with the user).
- `instructions.md` — change: add a "Tickets" section (shared base file: additive, confirm with the user).
- `tools/ticket-files.test.ts` — new.

## Steps

1. `loadTicket` calls `apiGet` on the ticket route. `requireTicketWorktree` throws "This ticket has no workspace yet; it gets one when it first moves to in progress." when `workspace.state` isn't `ready`, and the missing-worktree message (as `requireTaskWorktree`) when the folder or its `.git` is gone.
2. Read tools: `ticket_get` returns the Ticket JSON; `ticket_list_files` and `ticket_read_file` copy `task-list-files.ts` and `task-read-file.ts` (limit 500 files, 200 KB, `resolveTaskPath`, `.git` refused, symlinks followed), both `readOnlyHint`.
3. `ticket_write_details`: writes `{ content }` to `<folder>/details.md` through `resolveTaskPath`, allowed in both modes; returns the byte count. It refuses empty content.
4. `ticketWritablePath(…, rel)`: load the ticket **on every call**; require the workspace; `resolveTaskPath` the path; refuse locked skeleton files with `isLocked` (as `writablePath` in `task-write.ts`); when `mode` is `investigator`, refuse everything with: "This ticket is in investigator mode: only details.md can be written until the user approves the investigation. Write your findings and plan in details.md and ask the user to press Approve."
5. `ticket_write_file` and `ticket_edit_file` use `ticketWritablePath`; `ticket_edit_file` replaces one exact unique occurrence (copy `task-edit-file.ts`).
6. Tool errors go through `toolError`/`apiToolError`; the model gets API `detail`s as text.
7. `instructions.md` "Tickets" section: a message starting `[Ticket context: …]` is about that ticket; call `ticket_get` first; in investigator mode read the code (`ticket_list_files`, `ticket_read_file`), then record findings and a plan in `details.md` with `ticket_write_details`, keeping the headings; never change code until `mode` is `implementer`; after approval make the smallest change that fixes the ticket and note it in `details.md`; you cannot commit, push or open pull requests: tell the user to use Commit, Push and Create pull request on the page; checks run in CI, never ask to run tests.

## Tests

`node:test` in `tools/ticket-files.test.ts`, with a temp worktree and a stubbed API (as `task-files.test.ts` does):

- investigator: `details.md` write succeeds (AC-26); a code write and edit are refused with the message above; list and read work (AC-26).
- implementer: code write and edit succeed (AC-28); `.git`, `..`, absolute paths, symlinks out of the worktree and locked paths are refused in both modes.
- mode is read per call: the same tool refuses, then allows after the stub flips to `implementer`.
- no workspace (`state` `none`) → every tool answers that the ticket has none yet.
- the registered tool list contains the six and no tool that runs commands, commits or pushes.

## Checks

- `cd agent && npm run typecheck`
- `cd agent && npm test`

## Out of scope

- API changes (done in 01–07), the chat window (12), questions.
