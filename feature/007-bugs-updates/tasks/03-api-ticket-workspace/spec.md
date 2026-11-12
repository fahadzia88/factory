---
feature: 007-bugs-updates
task: 03-api-ticket-workspace
layer: api
status: draft
approved:
branch: feat/007-bugs-updates/03-api-ticket-workspace
base: feat/007-bugs-updates/02-api-ticket-status
covers: [AC-20, AC-21, AC-22, AC-23, AC-35]
---

# 03 — Ticket workspace

## Goal

The first change to `in_progress` creates the ticket's worktree and branch, writes and commits `<folder>/details.md`, and pushes. Failures are kept on the ticket and recorded, the status change still stands, and a retry route finishes the job without duplicates.

## Contract

From [task-breakdown.md](../../task-breakdown.md), unchanged: "Workspace sequence", "Template of `details.md`", the Ticket `workspace` object, `POST …/tickets/{tid}/workspace` (`200` Ticket; `409 workspace_not_needed`, `409 project_archived`, git codes). Branch `bugs/<number>-<slug>` or `updates/<number>-<slug>`, folder `feature/bugs/<number>-<slug>` or `feature/updates/<number>-<slug>`.

## Files

Under `api/src/main/kotlin/com/toptier/custom/ticket/`:

- `service/TicketNaming.kt` — new: `slug(title)`, `branch(type, number, slug)`, `folder(type, number, slug)`.
- `service/TicketDetailsTemplate.kt` — new: renders the template and its SHA-256.
- `service/TicketWorktrees.kt` — new: finds the ticket's worktree by branch in the project's checkout (same lookup `FeatureService.worktreeForBranch` uses) and reports `missing`.
- `service/TicketWorkspace.kt` — new: `ensure(ticketId)` runs the sequence.
- `service/TicketService.kt` — change: fill the seam from task 02; add `retryWorkspace`.
- `dto/TicketDtos.kt` — change: `workspace` now filled (`state`, `error`, `branch`, `folder`, `worktree`).
- `exception/TicketExceptions.kt` — change: `WorkspaceNotNeededException`.
- `controller/TicketController.kt` — change: `POST /{tid}/workspace`.
- `controller/TicketExceptionHandler.kt` — change: `workspace_not_needed` 409, and the git and checkout exceptions mapped like `FeatureExceptionHandler` (`checkout_not_ready`, `branch_checked_out`, `git_remote_error`, `git_not_configured`, `git_timeout`, `git_error`).

## Steps

1. `TicketNaming.slug`: lowercase, every run of characters outside `a-z0-9` becomes `-`, trim `-`, cut to 40 and trim `-` again, `ticket` when empty.
2. `TicketDetailsTemplate`: exactly the breakdown's template (`# #<number> <title>`; a line with type, priority, status; `## Description`; `## Findings` "Not investigated yet."; `## Plan` "Not written yet."). Return the text and its SHA-256 hex.
3. `TicketWorkspace.ensure`: serialised per ticket (an in-memory lock keyed by ticket id) and, for git work, under `CheckoutLocks` as `FeatureCreator` does. On the first attempt compute and store `slug`, `branch`, `folder`; later attempts reuse them. Steps, each skipped when already done:
   1. Worktree: when `TicketWorktrees` finds none, `WorktreeService.add(projectId, branch, defaultBranch)`.
   2. Details: when `<folder>/details.md` doesn't exist, create the folder and write the template, storing `details_baseline`. When the file is untracked or modified in `<folder>`, `git add -- <folder>` and commit `#<number> <title>: details` with `FeatureProperties.commitName`/`commitEmail` and `commit.gpgsign=false`.
   3. Push: skip when `origin/<branch>` equals `HEAD`; else `WorktreeService.push(projectId, worktreeId, force = false)`.
   Each completed step writes a `workspace` event (`detail`: "worktree added", "details.md committed", "branch pushed"). Any exception: write a `workspace` event with the step and message, set `workspace_state` `failed` and `workspace_error` (the message; git output stays in the log), and return without throwing. All steps done: `workspace_state` `ready`, `workspace_error` null.
4. `TicketService.changeStatus` seam: after saving, when the new status is `in_progress` and `workspace_state` is not `ready`, call `ensure`. An already ready workspace is untouched (AC-22). `ensure` never throws to the caller; the response reflects the final state.
5. `retryWorkspace`: refuse archived; `WorkspaceNotNeededException` when the state is `none` or `ready`; else `ensure`, return the Ticket.
6. Response `workspace.worktree` comes from `TicketWorktrees`; `missing` is true when the folder is gone.

## Tests

Against local bare repositories, reusing `GitFixtures`/`CheckoutTestConfig` from the checkout tests:

- `TicketNamingTests` — slug rules, empty title, long title, branch and folder per type.
- `TicketWorkspaceTests` — first `in_progress` of a bug: worktree on `bugs/<n>-<slug>` from the default branch, `feature/bugs/<n>-<slug>/details.md` committed, branch on origin, three `workspace` events, state `ready` (AC-20); same for an improvement with `updates/…` and `feature/updates/…` (AC-21); `in_progress` → … → `reopened` → `in_progress` creates nothing new and adds no `workspace` event (AC-22); a push that fails leaves status `in_progress`, state `failed`, error stored and an error event; retry completes with one worktree and one details commit (AC-23); retry on a ready or never-started ticket → `workspace_not_needed`.
- `TicketControllerTests` (extend) — `401`, `workspace_not_needed` 409.
- Archived project: `POST …/workspace` → `409 project_archived`, and the status route refuses first (AC-35).

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`

## Out of scope

- Reading `details.md`, `hasFindings`, `mode` and approval (04). In this task `hasFindings` stays false and `mode` `investigator`.
- Commit and push buttons (05), pull request (06).
