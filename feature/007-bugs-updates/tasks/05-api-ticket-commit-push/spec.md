---
feature: 007-bugs-updates
task: 05-api-ticket-commit-push
layer: api
status: draft
approved:
branch: feat/007-bugs-updates/05-api-ticket-commit-push
base: feat/007-bugs-updates/04-api-ticket-details
covers: [AC-29, AC-30, AC-35]
---

# 05 — Commit and push

## Goal

The ticket's **Commit** and **Push** actions: commit everything in the ticket's worktree, and push the branch, each with a history event.

## Contract

From [task-breakdown.md](../../task-breakdown.md), unchanged: `POST …/tickets/{tid}/commit` `{ "message?" }` → `{ committed, sha }`; `POST …/tickets/{tid}/push` → `{ branch, head }`; errors `409 workspace_missing`, `400 invalid_request` (message over 200 chars), `409 nothing_committed`, `409 push_rejected` and 003's other push codes, `409 project_archived`; events `commit` and `push`.

## Files

Under `api/src/main/kotlin/com/toptier/custom/ticket/`:

- `service/TicketGit.kt` — new: `commit` and `push`.
- `dto/TicketDtos.kt` — change: `CommitTicketRequest`, `CommitTicketResponse`, `PushTicketResponse`.
- `exception/TicketExceptions.kt` — change: `NothingCommittedException`.
- `controller/TicketController.kt` — change: the two routes.
- `controller/TicketExceptionHandler.kt` — change: `nothing_committed` 409, and the push exceptions mapped with the codes `CheckoutExceptionHandler` uses for `…/worktrees/{id}/push` (`push_rejected`, `worktree_missing` → here `workspace_missing`, and so on).

## Steps

1. `commit(projectId, ticketId, message)`: refuse archived; require a ready workspace and present worktree (`WorkspaceMissingException`); message trimmed, default `#<number> <title>`, over 200 characters → `400 invalid_request`. Under `CheckoutLocks`: `git status --porcelain`; clean → return `{ committed: false, sha: null }` without an event. Otherwise `git add -A`, `git commit` with `FeatureProperties.commitName`/`commitEmail` and `commit.gpgsign=false` (as `FeatureCreator`), read `git rev-parse --short HEAD`, write a `commit` event (`newValue` the SHA, `detail` the message), return it.
2. `push`: refuse archived; require the workspace; count commits beyond the default branch with `git rev-list --count origin/<default>..HEAD`; zero → `NothingCommittedException`. Otherwise `WorktreeService.push(projectId, worktreeId, force = false)`; write a `push` event (`newValue` head SHA, `detail` the branch). Push failures propagate as the existing exceptions and write no event.
3. Both routes check that `HEAD` is the ticket's branch before touching anything (a different branch → `workspace_missing`-style 409 with a clear detail).

## Tests

Against local bare repositories as in task 03:

- `TicketGitTests` — dirty tree commits all files with the given message and returns the SHA, one `commit` event (AC-29); default message; clean tree returns `committed: false` and no event (AC-29); long message rejected.
- Push: after a commit it pushes, `origin/<branch>` equals head, one `push` event (AC-30); no commit beyond the default branch → `nothing_committed`; a rejected push returns the git code and writes no event (AC-30).
- Archived → `project_archived` on both (AC-35); no workspace → `workspace_missing`.
- `TicketControllerTests` (extend) — `401`, status codes.

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`

## Out of scope

- Pull request (06), the buttons (11).
