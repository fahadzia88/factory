# Feature workflow

Every feature goes through three approval gates before code is written, then ships as a stack
of small PRs. Each step has a skill in `.claude/skills/`.

```
requirements.md ──approve──▶ task-breakdown.md ──approve──▶ tasks/*/spec.md ──approve──▶ stacked PRs
 /feature-requirements        /feature-breakdown             /feature-task-spec           /feature-dispatch → /stacked-pr
```

## Layout

```
feature/
  rules.md
  _templates/                 requirements.md, task-breakdown.md, task-spec.md
  <NNN>-<slug>/               one folder per feature, numbered 001, 002, …
    requirements.md
    task-breakdown.md
    tasks/
      01-<layer>-<slug>/spec.md
      02-<layer>-<slug>/spec.md
```

`<layer>` is `api`, `app`, `agent` or `infra`. Task numbers are the stack order.

## Approval

- Each document has a `status` in its frontmatter: `draft` → `approved` (→ `in-progress` → `done`
  for the breakdown).
- Only the user approves. Claude sets `status: approved` and `approved: <date>` only after the
  user says so explicitly in the conversation, and never on its own judgment.
- Approval lives in the file, not in chat, so every session can see it.
- No step starts until the step before it is approved. No code is written in `api/`, `app/` or
  `agent/` for a feature until its task specs are approved.
- If anything changes after approval, the document goes back to `draft`, gets a line in its
  changelog, and the user approves it again. Specs and tasks it affects are updated before
  work continues.

## 1. Requirements (`requirements.md`)

- Ask about the feature until the open questions are settled; don't guess at gaps.
- Write the sections in `_templates/requirements.md`: problem, users, scope, non-goals,
  assumptions, constraints, non-functional requirements, open questions.
- End with acceptance criteria, numbered `AC-1`, `AC-2`, …, each in Given / When / Then form
  and testable by a person or an automated test.
- Approval needs zero open questions.

## 2. Task breakdown (`task-breakdown.md`)

- Split the work into tasks, each owned by one layer: `api` (backend session), `app` (frontend
  session), `agent` (Claude Agent SDK service) or `infra` (`docker-compose.yml`, `.env`, anything at the repo
  root; done by the coordinator only with the user's go-ahead).
- Pin down the contract between layers (endpoints, request and response shapes, errors) before
  any task that depends on it.
- Each task is one PR: reviewable (about 400 changed lines or fewer), builds on the task before
  it, and leaves `main` working when merged. Hide unfinished user-facing work until the last task.
- Map every AC to at least one task. An AC with no task blocks approval.

## 3. Task specs (`tasks/NN-<layer>-<slug>/spec.md`)

- One folder per task, written from `_templates/task-spec.md`: files to touch, contract used,
  implementation steps, tests to add, the folder's checks, branch, base branch, ACs covered.
- Specs follow the conventions in the owning folder's `CLAUDE.md` (or `agent/AGENTS.md`).
- The user approves the specs (all at once is fine) before implementation starts.

## 4. Implementation (stacked PRs)

- Branches: `feat/<NNN>-<slug>/00-docs` holds the feature folder and is the stack's base; each task
  gets `feat/<NNN>-<slug>/<NN>-<layer>-<slug>`, based on the task before it.
- Every session shares one checkout (the containers mount it), so only one task is in progress at
  a time. The coordinator session sends one task at a time with `/feature-dispatch`; the owning
  session implements it with `/stacked-pr` and reports back before the next task starts.
- Approving the specs authorizes commits, pushes and PRs on that feature's task branches only.
  Never commit to `main`; force-push only with `--force-with-lease` on a task branch when restacking.
- A task is done when the folder's checks pass, the spec's tests exist, and its PR is open with
  the right base.
- Merge bottom-up. After a PR merges, rebase the next branch onto `main`, retarget its PR to
  `main`, and restack the rest.
- The feature is done when every PR has merged and every AC has been checked against the
  running app. Then set the breakdown's `status: done`.
