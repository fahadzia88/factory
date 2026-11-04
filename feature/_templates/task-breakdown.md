---
feature: <NNN>-<slug>
status: draft
approved:
---

# <Feature name>: task breakdown

Requirements: [requirements.md](requirements.md)

## Contracts

The interfaces between layers, fixed before the tasks that depend on them.

### `<METHOD> /api/<path>`

- Request: `{ ... }`
- Response `200`: `{ ... }`
- Errors: `400` <when>, `404` <when>, `502` <when>

## Tasks

Stack order, bottom first. Each task is one PR based on the row above it.

| #  | Layer | Task | Depends on | ACs | Branch |
| -- | ----- | ---- | ---------- | --- | ------ |
| 00 | docs  | Feature folder | — | — | `feat/<NNN>-<slug>/00-docs` |
| 01 | api   | <what it delivers> | 00 | AC-1 | `feat/<NNN>-<slug>/01-api-<slug>` |
| 02 | app   | <what it delivers> | 01 | AC-1 | `feat/<NNN>-<slug>/02-app-<slug>` |

## AC coverage

| AC   | Tasks  | Verified by |
| ---- | ------ | ----------- |
| AC-1 | 01, 02 | <test name or manual check> |

## Rollout

How unfinished work stays hidden until the last task merges, and anything to do on release
(config, env vars, data).

## Progress

| #  | Spec | PR | Status |
| -- | ---- | -- | ------ |
| 01 | [spec](tasks/01-api-<slug>/spec.md) | | not started |

## Changelog

- <date> — Created.
