---
feature: 005-probe
status: in-progress
approved: 2026-11-08
---

# Probe: task breakdown

Requirements: [requirements.md](requirements.md)

## Contracts

The interfaces between layers, fixed before the tasks that depend on them. Both routes need
`Authorization: Bearer <API_TOKEN>` (`401` otherwise) and return errors as `ProblemDetail`
(`application/problem+json`) with a `code` property the app switches on and a `detail` it can show
as is. They sit next to the other routes in `FeatureController`'s package and share its codes:
`404 project_not_found`, `404 feature_not_found` (no such feature, or it belongs to another
project), `400 invalid_request` (non-numeric id, missing `path`). Both are read-only (`GET`); no
route writes, renames or deletes a file.

### Where the files come from

- The root is `<feature.worktree.path>/<feature.folder>` (the feature's docs-branch worktree, from
  `FeatureService.get`), read from disk, so uncommitted edits show (AC-5). GitHub is never called.
- A "path" in both routes is relative to that root (`requirements.md`, `tasks/01-api-x/spec.md`),
  with `/` separators, no leading `/`.
- `.git` entries are never listed or served. A symlink is listed only when its real path is inside
  the root; otherwise it is skipped. Entries are sorted folders first, then files, each by name
  (case-insensitive).

### `GET /api/projects/{id}/features/{featureId}/files`

- Request: none.
- Response `200`:

  ```json
  {
    "root": "feature/005-probe",
    "entries": [
      { "name": "tasks", "path": "tasks", "type": "directory", "children": [
        { "name": "01-api-x", "path": "tasks/01-api-x", "type": "directory", "children": [
          { "name": "spec.md", "path": "tasks/01-api-x/spec.md", "type": "file", "markdown": true }
        ] }
      ] },
      { "name": "requirements.md", "path": "requirements.md", "type": "file", "markdown": true },
      { "name": "diagram.png", "path": "diagram.png", "type": "file", "markdown": false }
    ]
  }
  ```

- `markdown` is true when the name ends in `.md` (case-insensitive). Directories have `children`
  (possibly empty); files don't.
- The folder is empty or doesn't exist yet: `200` with `"entries": []` (AC-9).
- The tree is capped at 500 entries and 8 levels deep; anything beyond is left out.
- Errors: `409 worktree_missing` (the feature has no worktree, or it is `missing`; `detail` says to
  check the branch out again on the project page, AC-10).

### `GET /api/projects/{id}/features/{featureId}/files/content?path=<path>`

- Request: query `path` (required, relative to the root).
- Response `200`: `{ "path": "requirements.md", "content": "…", "size": 1234, "updatedAt": "2026-11-08T04:32:15Z" }`
  (`content` is the file as UTF-8 text, `updatedAt` its modified time).
- Errors:
  - `400 invalid_path`: blank, absolute, contains `..` or a NUL, names `.git`, or its real path
    (symlinks followed) is outside the root (AC-11). Nothing is read.
  - `404 file_not_found`: nothing at that path, or it is a directory (AC-8).
  - `415 unsupported_file_type`: not a `.md` file (AC-4).
  - `413 file_too_large`: over 200 KB.
  - `409 worktree_missing`: as above.

### app → api

`lib/feature-files.ts` with the types (`FeatureFileEntry`, `FeatureFileTree`, `FeatureFile`) and
`getFeatureFiles(projectId, featureId)` / `getFeatureFile(projectId, featureId, path)` through
`lib/api.ts`, which already sends the bearer token and turns problems into errors. The page treats
`worktree_missing`, `file_not_found` and other codes differently (AC-8, AC-10); everything else
shows the problem's `detail` with Try again.

### Feature page hooks

The Documents tab goes on the feature page and chat from feature 004 (tasks 09 and 10 of that
feature). The tab needs one thing from the chat: a callback fired when a chat turn ends, which the
chat already needs to refresh the feature after each turn. If it isn't exposed as a prop, task 05
adds it (a small additive change to the chat component).

## Tasks

Stack order, bottom first. Each task is one PR based on the row above it.

| #  | Layer | Task | Depends on | ACs | Branch |
| -- | ----- | ---- | ---------- | --- | ------ |
| 00 | docs  | Feature folder | — | — | `feat/005-probe/00-docs` |
| 01 | api   | `FeatureFiles` service in the `feature` package: tree listing and file read for a feature's worktree folder, with the path guard (`..`, absolute, NUL, `.git`, real path outside the root, symlinks), sorting, caps, size limit, `.md` check. New exceptions (`InvalidPathException`, `FeatureFileNotFoundException`, `UnsupportedFileTypeException`, `FileTooLargeException`, `WorktreeMissingException`). Tests against a temp directory. | 00 | AC-1, AC-3, AC-4, AC-5, AC-8, AC-9, AC-11 | `feat/005-probe/01-api-feature-files` |
| 02 | api   | The two `GET` routes (`…/files`, `…/files/content`), DTOs, exception mapping with the codes above. `@WebMvcTest` incl. `401`, `404` for another project's feature, `409 worktree_missing`, every error code, and no write methods. | 01 | AC-1, AC-2, AC-8, AC-10, AC-11, AC-15 | `feat/005-probe/02-api-feature-file-routes` |
| 03 | app   | `lib/feature-files.ts` and `components/features/file-tree.tsx`: an accessible tree (`role="tree"`, `treeitem`, `aria-expanded`, `aria-selected`), folders expand and collapse, non-markdown files disabled, arrow/Home/End/Enter/Space keys, selection callback. Not mounted anywhere yet. Component tests. | 02 | AC-1, AC-3, AC-4, AC-13, AC-14 | `feat/005-probe/03-app-file-tree` |
| 04 | app   | `components/features/document-viewer.tsx`: loads a file and renders it with the existing `Markdown` component (raw HTML stays text); loading, "file no longer exists" (`file_not_found`) and error with Try again states; a "Select a document" placeholder. Not mounted yet. Component tests. | 03 | AC-2, AC-8, AC-12 | `feat/005-probe/04-app-document-viewer` |
| 05 | app   | "Documents" tab on the feature page beside the chat: tree plus viewer (stacked at phone width, side by side wider), refresh button, automatic refresh when a chat turn ends (tree and open file), empty state, "check the branch out again" state for `worktree_missing`, no change to the chat and existing sections. This is the task that makes the tab visible. | 04 | AC-1, AC-2, AC-6, AC-7, AC-8, AC-9, AC-10, AC-13, AC-16 | `feat/005-probe/05-app-documents-tab` |

All five tasks are `api` or `app`; there is no `agent` or `infra` work (no new tool, config or
compose change). Tasks 01 and 02 add files under `api/` only and touch no `locked` or `shared`
base file.

## AC coverage

| AC    | Tasks          | Verified by |
| ----- | -------------- | ----------- |
| AC-1  | 01, 02, 03, 05 | `FeatureFilesTests` tree of nested folders; controller test of the tree JSON; tree component test (expand/collapse); manual: Documents tab shows the folder |
| AC-2  | 02, 04, 05     | Controller test of the content JSON; viewer test renders headings and tables; manual: select a `.md` file, it is marked selected |
| AC-3  | 01, 03, 05     | Service test for `tasks/01-x/spec.md`; tree test opens a nested file; manual |
| AC-4  | 01, 03         | Service test: `.png` listed with `markdown: false`, content → `415`; tree test: disabled item, no selection callback |
| AC-5  | 01             | Service test: a file edited on disk (never committed) returns the edited text; manual: agent edit shows without a commit |
| AC-6  | 05             | Page test: chat-turn-done callback triggers the tree and open-file requests; manual: ask the agent to edit, the viewer updates with no reload |
| AC-7  | 05             | Page test: refresh button re-requests both; manual |
| AC-8  | 01, 02, 04, 05 | Service and controller test `404 file_not_found`; viewer test shows "file no longer exists"; page test: tree refresh no longer lists it; manual: delete the file in the worktree, refresh |
| AC-9  | 01, 05         | Service test: missing and empty folder → `[]`; page test shows the empty state, no error |
| AC-10 | 02, 05         | Controller test `409 worktree_missing`; page test shows the check-out message; manual with the worktree removed |
| AC-11 | 01, 02         | Service and controller tests: `../rules.md`, `/etc/passwd`, `a/../../x`, NUL, `.git/config`, a symlink to a file outside, a symlinked folder to outside → `400 invalid_path`, nothing read |
| AC-12 | 04             | Viewer test: `<script>alert(1)</script>` appears as text, no script element (the existing `Markdown` component has no HTML plugin) |
| AC-13 | 03, 04, 05     | Manual at 375 px: tree above viewer, no horizontal page scroll (long lines and tables scroll inside the viewer) |
| AC-14 | 03             | Tree component test: arrow keys move, Right/Left expand/collapse, Enter/Space open |
| AC-15 | 02             | `@WebMvcTest` `401` on both routes without a token |
| AC-16 | 05             | Page test: existing sections and chat render unchanged; manual: chat still works |

## Rollout

- The routes (01–02) are read-only and unused until 05.
- The tree and viewer components (03–04) are not mounted anywhere, so nothing user-facing changes
  until 05 adds the Documents tab. 05 is the last task and the only one that changes a page.
- Release: no env vars, config or data. The routes read only the worktrees that already exist.

## Progress

| #  | Spec | PR | Status |
| -- | ---- | -- | ------ |
| 00 | — | [#51](https://github.com/fahadzia88/factory/pull/51) | pr open |
| 01 | [spec](tasks/01-api-feature-files/spec.md) | | not started |
| 02 | [spec](tasks/02-api-feature-file-routes/spec.md) | | not started |
| 03 | [spec](tasks/03-app-file-tree/spec.md) | | not started |
| 04 | [spec](tasks/04-app-document-viewer/spec.md) | | not started |
| 05 | [spec](tasks/05-app-documents-tab/spec.md) | | not started |

## Changelog

- 2026-11-08 — Created.
- 2026-11-08 — Approved.
