---
feature: 007-bugs-updates
task: 06-api-ticket-pull
layer: api
status: draft
approved:
branch: feat/007-bugs-updates/06-api-ticket-pull
base: feat/007-bugs-updates/05-api-ticket-commit-push
covers: [AC-31, AC-32, AC-33, AC-34, AC-35]
---

# 06 — Create pull request

## Goal

Open a GitHub pull request for the ticket's pushed branch into the default branch, save its link on the ticket and record it.

## Contract

From [task-breakdown.md](../../task-breakdown.md), unchanged: `POST …/tickets/{tid}/pull` `{ "title?", "replaceLink?": false }` → `201` Ticket; errors `409 workspace_missing`, `409 branch_not_pushed`, `409 nothing_to_compare`, `409 pull_exists`, `409 pull_link_set`, `409 project_archived`, GitHub codes. Behaviour as the breakdown's "Pull" paragraph: title default `#<number> <title>`; body is the description (or "No description."), `Ticket #<number> (<type>)` and a link to `details.md` on the branch; the status does not change.

## Files

Under `api/src/main/kotlin/com/toptier/custom/ticket/`:

- `service/TicketPulls.kt` — new: `open`.
- `service/TicketPullText.kt` — new: title and body builders.
- `exception/TicketExceptions.kt` — change: `BranchNotPushedException`, `NothingToCompareException`, `TicketPullExistsException`, `PullLinkSetException`.
- `dto/TicketDtos.kt` — change: `OpenTicketPullRequest`.
- `controller/TicketController.kt` — change: the route.
- `controller/TicketExceptionHandler.kt` — change: the four 409 codes, and `GitHubException` mapped through `PullProblems.describe` as `FeatureExceptionHandler` does.

## Steps

1. `TicketPulls.open`, in this order: refuse archived (`project_archived`); `pullNumber` set → `pull_exists`; hand-entered `pullRequestUrl` with `pullNumber` null and `replaceLink` not true → `pull_link_set`; workspace ready with a present worktree else `workspace_missing`; title trimmed (default as above, 1–200 chars else `invalid_request`).
2. Under `CheckoutLocks`: `CheckoutService.fetchUnderLock`, then `runner.refExists(clone, "refs/remotes/origin/<branch>")` false → `branch_not_pushed`; `git rev-list --count origin/<default>..origin/<branch>` zero → `nothing_to_compare`.
3. `GitHubPullWriteClient.create(owner, repo, title, branch, defaultBranch, body)`; repository from `GitHubRepositoryUrl.parse(project.repositoryUrl)`; the link to `details.md` is `PullText.blobUrl(owner, repo, branch, "<folder>/details.md")`.
4. Save `pull_number` and `pull_request_url` (the pull's `html_url`), write a `pull` event (`newValue` the URL, `detail` the title; `oldValue` the replaced link if any), return the Ticket. A GitHub failure saves nothing and writes no event.

## Tests

With `MockRestServiceServer` for GitHub and local bare repos for git:

- `TicketPullsTests` — happy path: pull opened from the branch into the default branch with the right title, body and link, link and number saved, one `pull` event, status unchanged (AC-31); second call → `pull_exists`, no second GitHub call (AC-32); branch not on origin → `branch_not_pushed`, branch with no commits → `nothing_to_compare`, nothing called on GitHub (AC-33); GitHub `403`/timeout/blank token → mapped `502`/`504`/`503`, nothing saved, retry works (AC-34); hand-entered link without `replaceLink` → `pull_link_set`, with it the link is replaced and the old one is in `oldValue`; archived → `project_archived` (AC-35).
- `TicketPullTextTests` — title default, empty description, link formatting.
- `TicketControllerTests` (extend) — `401` and status codes.

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`

## Out of scope

- Syncing the pull request's state, merging or closing it.
- The button (11).
