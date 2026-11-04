---
name: feature-task-spec
description: Write a detailed implementation spec for each task in an approved feature breakdown, as feature/<NNN>-<slug>/tasks/<NN>-<layer>-<slug>/spec.md. Use after task-breakdown.md is approved, or to revise a task's spec.
argument-hint: <feature folder> [task number]
---

# Feature task specs

Gate 3 of the workflow in `feature/rules.md`. Read that file first.

## Preconditions

- The feature's `task-breakdown.md` has `status: approved`. If not, stop and say so.

## Steps

For each task in the breakdown (or only the one named):

1. **Read** the owning folder's rules (`api/CLAUDE.md`, `app/CLAUDE.md` or `agent/AGENTS.md`)
   and the exact files the task will touch or imitate.
2. **Write the spec** from `feature/_templates/task-spec.md` at
   `feature/<NNN>-<slug>/tasks/<NN>-<layer>-<slug>/spec.md`:
   - frontmatter: branch, base (the task below it, or `00-docs`), the ACs covered;
   - contract copied from the breakdown, unchanged;
   - every file to add or change, with its purpose;
   - numbered steps concrete enough that the owning session can implement them without
     re-planning, using the folder's patterns (packages, error mapping, `clientLoader`, etc.);
   - tests to add, each tied to an AC;
   - the folder's checks from its CLAUDE.md;
   - what's out of scope.
3. **Fill in the progress table** in task-breakdown.md with a link to each spec.
4. **Ask for approval.** List the specs with one line each and ask the user to approve them
   (all at once is fine) or ask for changes.
5. **Record approval** only on an explicit yes: `status: approved`, `approved: <today>` in each
   approved spec. Then suggest `/feature-dispatch`.

## Rules

- A spec may not change a contract. If one needs to change, update the breakdown first and get
  it approved again.
- Don't write code here; specs only.
