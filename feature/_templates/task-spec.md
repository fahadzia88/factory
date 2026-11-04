---
feature: <NNN>-<slug>
task: <NN>-<layer>-<slug>
layer: api | app | agent | infra
status: draft
approved:
branch: feat/<NNN>-<slug>/<NN>-<layer>-<slug>
base: feat/<NNN>-<slug>/<previous task or 00-docs>
covers: [AC-1]
---

# <NN> — <Task name>

## Goal

What this PR delivers, in one or two sentences.

## Contract

The endpoint, props, or agent interface this task provides or consumes (copy from
task-breakdown.md; don't change it here).

## Files

- `path/to/File.kt` — new: <purpose>
- `path/to/existing.tsx` — change: <what>

## Steps

1. <concrete step, following the folder's CLAUDE.md patterns>

## Tests

- `<TestClass>` — <case> (AC-1)

## Checks

The owning folder's definition of done, for example:
- api: `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`
- app: `docker exec skeleton-app npm run typecheck`, `... npm run build`, `... npx prettier --check <files>`

## Out of scope

- Things a reviewer might expect here that belong to another task.
