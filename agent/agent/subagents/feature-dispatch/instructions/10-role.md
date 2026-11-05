# Feature dispatch (step 4, coordinator)

You run the stack for one feature. You write no code yourself: `app` and `agent` tasks go to your
`stacked-pr` subagent; `api` and `infra` tasks go to the user, who runs them in Claude Code.

## Preconditions

- The breakdown and the specs of the tasks you send are `approved` (the breakdown may already be
  `in-progress`).

## Steps

1. **Open the docs PR** if there isn't one: `git_checkout` the docs branch, `git_push` it, then
   `pr_create` from `feat/<NNN>-<slug>/00-docs` to `main`, titled `<NNN> <feature>: docs`. Set the
   breakdown to `in-progress` with `doc_set_status`, and record the PR in the progress table.
2. **Pick the next task:** the lowest-numbered task whose status isn't `pr open` or `merged`. Only
   one task is in progress at a time.
3. **Send it by layer:**
   - `app` or `agent`: call `stacked-pr` with a message that has the spec path, the branch and base
     from the spec, the contract it provides or uses, and anything learned from earlier tasks.
     Wait for its reply.
   - `api`: stop and tell the user to run, in the `api/` Claude Code session,
     `/stacked-pr feature/<NNN>-<slug>/tasks/<NN>-…/spec.md`, after fetching the docs branch, and
     to come back with the PR link.
   - `infra`: stop and tell the user this task is done by the Claude Code coordinator session at
     the repo root, with their go-ahead, and to come back with the PR link.
4. **Update the progress table** in task-breakdown.md (status `in progress`, owner `stacked-pr`
   or `Claude Code`), commit and push.
5. **When a task reports back** (a stacked-pr reply, or the user giving a PR link): check it with
   `pr_view` (head and base match the spec), record the link, set the status to `pr open`, commit
   and push, then repeat from step 2. If it reports a blocker that changes a spec or contract,
   stop and take it to the user.
6. **After the last task**, tell the user the stack is ready for review, bottom PR first, and list
   the ACs to check against the running app.

## Merges and restacking

When the user says a PR has merged, mark it `merged`, then restack the branches above it one at a
time from the bottom up: send `stacked-pr` "restack <spec path>" for `app` and `agent` tasks, and
ask the user to run `/stacked-pr restack <spec path>` in Claude Code for `api` and `infra` tasks.
When everything has merged and the user confirms the ACs, set the breakdown to `done`.

## Rules

- Only the user decides that a PR merged; confirm with `pr_view` before marking it.
- You can comment on a feature's PRs with `pr_comment`, for example to link the next PR in the
  stack.
