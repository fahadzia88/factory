---
feature: 006-feature-implementation
task: 10-app-feature-tasks
layer: app
status: draft
approved:
branch: feat/006-feature-implementation/10-app-feature-tasks
base: feat/006-feature-implementation/09-api-question-chat
covers: [AC-13, AC-17, AC-25]
---

# 10 — Pull requests card on the feature page

## Goal

The feature page shows each task's pull request with its state and checks, a Refresh button, and a way to retry
opening the docs PR.

## Contract

Consumes, from task-breakdown.md: `GET /api/projects/{id}/features/{featureId}/tasks` → `[Task]` (fields: `number`, `layer`,
`slug`, `branch`, `baseBranch`, `specPath`, `worktree`, `pull` { `number`, `title`, `state`, `draft`, `htmlUrl`, `baseRef`,
`headSha`, `mergedAt`, `checks` { `state`, `runs` [{ `name`, `status`, `conclusion`, `htmlUrl`, `title`, `summary` }] } },
`pullError`, `needsRestack`); `POST …/tasks/0/pull` → `201` Task (errors `409 pull_exists`, `409 task_not_pushed`, GitHub
`502`/`503`/`504`); the create response's `docsPullError`.

## Files

Under `app/app/`:

- `lib/features.ts` — change: types `FeatureTask`, `TaskPull`, `TaskChecks`, `TaskCheckRun`; `listFeatureTasks(projectId, featureId)`,
  `openDocsPull(projectId, featureId)`; `Feature` gets `docsPullError?: string | null`; add `"pull_exists"`, `"task_not_pushed"`,
  `"github_error"`, `"github_timeout"`, `"github_rate_limited"`, `"github_not_configured"` to `FeatureErrorCode`.
- `components/features/feature-tasks.tsx` — new: the "Pull requests" `Card`.
- `components/features/task-checks.tsx` — new: checks summary for one task.
- `routes/feature-detail.tsx` — change: render `<FeatureTasks>` between the Steps card and the chat; hold a `refresh` counter that
  `onTurnEnd` also increments.
- `routes/feature-new.tsx` — change (read it first): after create, navigate to the feature page with
  `state: { docsPullError: feature.docsPullError ?? null }`.

## Steps

1. `lib/features.ts`: add the types and the two calls with `api.get` / `api.post`.
2. `FeatureTasks({ projectId, featureId, refresh, initialError })`: loads in an effect keyed by `refresh` and a local reload counter
   (states `loading` / `loaded` / `error` like `useFeatureChat`'s history). Header: title "Pull requests", a "Refresh" `Button`
   (outline, small, `Spinner` while loading).
3. Each task is a list row (no horizontal page scroll; `min-w-0`, `break-all` on branches):
   - label `NN layer slug` (row 0: `00 docs`), the branch in mono;
   - with `pull`: link `#<number>` to the in-app route `/projects/<id>/pulls/<number>` and a small external link to `htmlUrl`
     (`target="_blank" rel="noreferrer"`); `PullRequestStateBadge` from `components/pull-requests/pull-request-badges.tsx`
     (pass `pull`); the base `baseRef`;
   - `<TaskChecks>`: `none` → "No checks"; `pending` → warning badge "Checks running"; `success` → positive "Checks passing"; `failure` →
     negative "Checks failing" and the failing run names (conclusion not success/neutral/skipped) as a list with links to
     `htmlUrl`;
   - `needsRestack` → warning `StatusBadge` "Needs restack";
   - `pullError` → `text-destructive` line "Couldn't read this pull request: <pullError>";
   - no `pull` and no error → muted "No pull request yet".
4. Row 0 with no pull: a "Open pull request" `Button`. On click `openDocsPull`, disabled with `Spinner` meanwhile; success reloads the list;
   failure shows `featureError(...)`'s message in an `Alert` under the row (and `retryable` errors keep the button). `initialError` (from
   `useLocation().state.docsPullError`) is shown once in the same `Alert` with the title "The docs pull request wasn't opened".
5. List failure: `Alert` "Couldn't load the pull requests" with the message and "Try again". 401 reads as `featureError` does.
6. Feature page: pass `refresh`; keep the rest unchanged. No controls to stop, retry or skip a task anywhere.

## Tests

The app has no test runner and adding one is out of scope. Manual checks:

- A feature with only the docs row and no PR shows "No pull request yet" and the button (AC-13); with the API token missing GitHub
  access, clicking shows the API's detail and keeps the button.
- With PRs from tasks 04–05 running against a real repository: state badges for open/merged/closed, checks running/passing/failing
  with the failing run name, "Needs restack" after the lower branch moves, in-app link opens the PR page (AC-17).
- API stopped: error with "Try again", the rest of the page works. 375 px wide: no horizontal scroll, long branch names wrap (AC-25).

## Checks

- `docker exec factory-app npm run typecheck`, `docker exec factory-app npm run build`,
  `docker exec factory-app npx prettier --check app/lib/features.ts app/components/features/feature-tasks.tsx app/components/features/task-checks.tsx app/routes/feature-detail.tsx app/routes/feature-new.tsx`.

## Out of scope

- Any status-changing control (stop/retry/skip). Auto-polling. Question cards (11).
