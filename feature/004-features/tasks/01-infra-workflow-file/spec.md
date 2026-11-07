---
feature: 004-features
task: 01-infra-workflow-file
layer: infra
status: approved
approved: 2026-11-06
branch: feat/004-features/01-infra-workflow-file
base: feat/004-features/00-docs
covers: [AC-3]
---

# 01 — The factory's `feature/workflow.json`

## Goal

Add `feature/workflow.json` to the factory repository, describing the workflow it follows today
(`feature/rules.md`): the feature states and the requirements, breakdown and spec steps, each
pointing at its skill in `.claude/skills/`. Nothing reads it yet; task 04 does.

## Contract

From task-breakdown.md, "Workflow file (project repo ↔ api ↔ agent)":

`feature/workflow.json` in the project's repository, read from the main worktree
(`/repos/<project-id>/feature/workflow.json`) on create:

```json
{
  "featureStates": ["requirements", "breakdown", "specs", "implementation", "done", "abandoned"],
  "steps": [
    { "key": "requirements", "title": "Requirements", "skill": "feature-requirements", "states": ["draft", "approved"] },
    { "key": "breakdown", "title": "Task breakdown", "skill": "feature-breakdown", "states": ["draft", "approved"] },
    { "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] }
  ]
}
```

- Valid: parses as JSON; `featureStates` and `steps` non-empty; every state matches
  `^[a-z][a-z0-9-]{0,39}$`, no duplicates within a list; every step has a unique `key` (same
  pattern), a non-blank `title` (≤ 100), a `skill` (same pattern) and non-empty `states`; every
  `.claude/skills/<skill>/SKILL.md` exists in the main worktree. Unknown properties are ignored.
- Also required on create: `feature/_templates/requirements.md`.

## Base files

- `feature/workflow.json` is not in `.claude/base-files`, so it is project code. `feature/rules.md`
  and `feature/_templates/*` are locked and aren't touched.
- Whether the skeleton should ship a `feature/workflow.json` (and list it as `shared`) is for the
  user to decide in the skeleton repo; this task doesn't change `.claude/base-files`.

## Files

- `feature/workflow.json` — new: exactly the JSON in the contract, formatted with 2-space indent
  and one step per line as shown, ending with a newline.

## Steps

1. Check that `.claude/skills/feature-requirements/SKILL.md`,
   `.claude/skills/feature-breakdown/SKILL.md` and `.claude/skills/feature-task-spec/SKILL.md`
   exist, and that `feature/_templates/requirements.md` exists.
2. Write `feature/workflow.json` with the contract's content.
3. Validate by hand against the contract's rules: `python3 -m json.tool feature/workflow.json`
   parses it; the states and keys match the pattern and have no duplicates.
4. Commit on the task branch with the coordinator's go-ahead, push, open the PR against
   `feat/004-features/00-docs`.

## Tests

- No automated test: the `api` container mounts only `./api`, so API tests can't read this file.
  Once 01 and 04 are merged, creating a feature on the factory project runs the API's validator
  on it (AC-3).
- Manual: `python3 -m json.tool feature/workflow.json` succeeds; the three `SKILL.md` files exist
  (AC-3).

## Checks

- `python3 -m json.tool feature/workflow.json > /dev/null`
- `ls .claude/skills/feature-requirements/SKILL.md .claude/skills/feature-breakdown/SKILL.md .claude/skills/feature-task-spec/SKILL.md feature/_templates/requirements.md`

## Out of scope

- Reading or validating the file in code (02, 04).
- Steps for implementation (`feature-dispatch`, `stacked-pr`): that's feature 005, which adds
  them to this file.
- Shipping the file from the skeleton.
