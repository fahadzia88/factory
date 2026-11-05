# Feature breakdown (gate 2)

## Preconditions

- `feature/<NNN>-<slug>/requirements.md` on the feature's docs branch has `status: approved`. If
  not, stop and say so.

## Steps

1. **Read** the approved requirements, `api/CLAUDE.md`, `app/CLAUDE.md`, `agent/AGENTS.md`, and the
   existing code the feature extends (on `main`).
2. **Write the contracts first.** For every boundary between layers (api ↔ app, api ↔ agent,
   app ↔ agent), write down the endpoint or interface: path, request, response, error statuses.
   Follow existing conventions (bearer auth, `ProblemDetail` errors, the `/api` and `/eve`
   proxies).
3. **Split into tasks.** `git_checkout` the docs branch and write
   `feature/<NNN>-<slug>/task-breakdown.md` from `feature/_templates/task-breakdown.md`. Each task:
   - is owned by one layer: `api`, `app`, `agent` or `infra` (compose, root config);
   - is one PR of about 400 changed lines or fewer;
   - builds only on the tasks below it and leaves `main` working when merged;
   - is ordered so providers come before consumers (the api endpoint before the app page that
     calls it).
   Row `00-docs` is always the base and holds the feature folder.
4. **Plan the rollout.** Say how unfinished user-facing work stays hidden until the last task (an
   unlinked route, a nav item added last, a config flag).
5. **Check coverage.** Every AC appears in the coverage table with at least one task and a way to
   verify it. Fix any gaps before asking for approval.
6. **Ask for approval.** Commit and push, put the task table and contracts in your reply, and ask
   with `ask_question`: approve, or request changes.
7. **Record approval** only on an explicit yes: `doc_set_status` with `status: "approved"`, then
   commit and push. The next step is `feature-task-spec`.

## Rules

- Don't create task folders or write code.
- If the breakdown shows the requirements are wrong or incomplete, stop and say the user needs to
  go back to `feature-requirements`, instead of working around it.
- Note for the stack: this agent implements `app` and `agent` tasks itself; `api` and `infra`
  tasks are done in Claude Code.
