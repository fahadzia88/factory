---
name: feature-breakdown
description: Break an approved feature into stacked, layer-owned tasks (api, app, agent, infra) with the contracts between them and AC coverage, written to feature/<NNN>-<slug>/task-breakdown.md. Use after a feature's requirements.md is approved, or to revise an existing breakdown.
argument-hint: <feature folder, e.g. 001-patient-search>
---

# Feature breakdown

Gate 2 of the workflow in `feature/rules.md`. Read that file first.

## Preconditions

- `feature/<NNN>-<slug>/requirements.md` has `status: approved`. If not, stop and say so.

## Steps

1. **Read** the approved requirements, `api/CLAUDE.md`, `app/CLAUDE.md`, `agent/AGENTS.md`,
   and the existing code the feature extends.
2. **Write the contracts first.** For every boundary between layers (api ↔ app, api ↔ agent,
   app ↔ agent) write down the endpoint or interface: path, request, response, error statuses.
   Follow existing conventions (bearer auth, `ProblemDetail` errors, `/api` and `/agent` proxies).
3. **Split into tasks.** Copy `feature/_templates/task-breakdown.md`. Each task:
   - is owned by one layer: `api`, `app`, `agent`, or `infra` (compose, root config);
   - is one PR of about 400 changed lines or fewer;
   - builds only on the tasks below it and leaves `main` working when merged;
   - is ordered so providers come before consumers (api endpoint before the app page that
     calls it).
   Row `00-docs` is always the base and holds the feature folder.
4. **Plan the rollout.** Say how unfinished user-facing work stays hidden until the last task
   (an unlinked route, a nav item added last, a config flag).
5. **Check coverage.** Every AC appears in the coverage table with at least one task and a way
   to verify it. Fix any gaps before asking for approval.
6. **Ask for approval.** Show the task table and contracts in chat and ask the user to approve
   or ask for changes.
7. **Record approval** only on an explicit yes: `status: approved`, `approved: <today>`,
   changelog line. Then suggest `/feature-task-spec`.

## Rules

- Don't create task folders or write code here.
- If the breakdown shows the requirements are wrong or incomplete, stop and send the user back
  to `/feature-requirements` rather than working around it.
