---
name: feature-dispatch
description: Coordinator step — send the next approved task of a feature to the session that owns its folder, one task at a time, and track progress in task-breakdown.md. Use from the repo-root coordinator session once a feature's task specs are approved, or when a task reports back.
argument-hint: <feature folder>
---

# Feature dispatch

Step 4 of the workflow in `feature/rules.md`, run by the root coordinator session. The
coordinator does not write code in `api/`, `app/` or `agent/`.

## Preconditions

- The breakdown and the specs for the tasks being sent are `approved`.
- `gh auth status` succeeds. If `gh` is missing or signed out, stop and tell the user.

## Steps

1. **Create the base branch** if it doesn't exist yet: from an up-to-date `main`, create
   `feat/<NNN>-<slug>/00-docs`, commit the feature folder, push, and open a PR to `main` titled
   `<NNN> <feature>: docs`. Record it in the progress table. Set the breakdown to
   `status: in-progress`.
2. **Pick the next task**: the lowest-numbered task whose status isn't `pr open` or `merged`.
   Only one task is in progress at a time, because every session shares one checkout.
3. **Find the owner** with ListAgents: the `api/` session for `api`, the `app/` session for
   `app`. If you can't tell which session owns which folder, ask the user. Agent tasks go to
   whichever session the user names. `infra` tasks are done by the coordinator itself, and only
   after the user gives the go-ahead for that task.
4. **Send the task** with SendMessage. The brief includes:
   - the spec path, branch and base branch;
   - "Run `/stacked-pr feature/<NNN>-<slug>/tasks/<NN>-…`" as the instruction;
   - the contract it provides or uses and anything learned from earlier tasks;
   - "Report back with the PR link, or what blocked you."
5. **Update the progress table**: status `in progress`, owner session.
6. **When the session reports back**, record the PR link and set the status to `pr open`, then
   repeat from step 2. If it reports a blocker that changes a spec or contract, stop and take it
   to the user.
7. **After the last task**, tell the user the stack is ready for review, bottom PR first, and
   list the ACs to check against the running app.

## Merges and restacking

When the user says a PR has merged, send the next task's owner a restack instruction
(`/stacked-pr restack <spec path>`), one branch at a time from the bottom up. Mark merged rows
`merged`. When everything has merged and the user confirms the ACs, set the breakdown's
`status: done`.
