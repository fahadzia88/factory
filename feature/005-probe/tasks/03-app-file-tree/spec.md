---
feature: 005-probe
task: 03-app-file-tree
layer: app
status: approved
approved: 2026-11-08
branch: feat/005-probe/03-app-file-tree
base: feat/005-probe/02-api-feature-file-routes
covers: [AC-1, AC-3, AC-4, AC-13, AC-14]
---

# 03 — File tree

## Goal

`lib/feature-files.ts` (types and the two calls) and an accessible, keyboard-navigable file tree
component. Nothing mounts it yet: task 05 puts it on the feature page.

## Contract

Consumes, copied from task-breakdown.md:

- `GET /api/projects/{id}/features/{featureId}/files` →
  `200 { "root": "feature/005-probe", "entries": [ { "name", "path", "type": "directory", "children": [...] } | { "name", "path", "type": "file", "markdown": true|false } ] }`.
  `entries` is `[]` for an empty or missing folder. Errors: `409 worktree_missing`, plus the
  shared `404 project_not_found`, `404 feature_not_found`, `400 invalid_request`, and `401`.
- `GET /api/projects/{id}/features/{featureId}/files/content?path=<path>` →
  `200 { "path", "content", "size", "updatedAt" }`. Errors: `400 invalid_path`,
  `404 file_not_found`, `415 unsupported_file_type`, `413 file_too_large`, `409 worktree_missing`.
- Errors are `ProblemDetail` with a `code` and a `detail` the app can show as is.

## Files

- `app/app/lib/feature-files.ts` — new: types and calls.
- `app/app/components/features/file-tree.tsx` — new: `FileTree`.

No route, `routes.ts` or page change.

## Steps

1. **`lib/feature-files.ts`**, in the style of `lib/features.ts` (feature 004 task 09):
   - Types: `FeatureFileEntry = { name: string; path: string; type: "directory"; children:
     FeatureFileEntry[] } | { name: string; path: string; type: "file"; markdown: boolean }`,
     `FeatureFileTree { root: string; entries: FeatureFileEntry[] }`,
     `FeatureFile { path: string; content: string; size: number; updatedAt: string }`.
   - `FeatureFilesErrorCode` union: `worktree_missing`, `invalid_path`, `file_not_found`,
     `unsupported_file_type`, `file_too_large`, `project_not_found`, `feature_not_found`,
     `invalid_request`.
   - `getFeatureFiles(projectId, featureId)` → `api.get<FeatureFileTree>(\`/api/projects/${projectId}/features/${featureId}/files\`)`.
   - `getFeatureFile(projectId, featureId, path)` → `api.get<FeatureFile>(\`…/files/content?path=${encodeURIComponent(path)}\`)`.
   - Errors: reuse `featureError()` from `lib/features.ts` (same rules: the problem's `detail`
     first except for `401`; `retryable` when there was no response or `status >= 500`); add
     `featureErrorCode(error): string | null` here that returns the problem's `code` for an
     `ApiError`, so callers can switch on `worktree_missing` and `file_not_found`. Do not edit
     `lib/features.ts` or the locked `lib/api.ts`.
2. **`FileTree`** props: `{ entries: FeatureFileEntry[]; selectedPath: string | null; onSelect:
   (path: string) => void; label?: string }` (`label` defaults to "Feature documents").
   - State: `expanded: Set<string>` (directory paths), `focusedPath: string | null`. State is kept
     when `entries` changes (a refresh), pruned of paths that no longer exist. The ancestors of
     `selectedPath` are expanded when the selection changes (so a file opened elsewhere is visible).
   - Render a flat list of the *visible* nodes in order: `<ul role="tree" aria-label={label}>`; each
     node an `<li role="treeitem">` with `aria-level` (1-based depth), `aria-posinset`,
     `aria-setsize`, `aria-expanded` (directories only), `aria-selected` (true for `selectedPath`),
     `aria-disabled="true"` for files with `markdown: false`, and `tabIndex` 0 for the focused item
     and -1 for the rest (roving tabindex; the first item is focused when none is). Indent with
     `style={{ paddingLeft: \`${level * 0.75}rem\` }}`.
   - Row content: `ChevronRightIcon` (rotated 90° when expanded) for directories or a spacer for
     files; `FolderIcon`/`FolderOpenIcon` for directories, `FileTextIcon` for `.md` files,
     `FileIcon` for others (lucide-react); the name in `min-w-0 truncate`, with `title={path}`.
     Selected row: `bg-accent text-accent-foreground`; disabled rows: `text-muted-foreground
     opacity-60 cursor-not-allowed`; hover and `focus-visible` ring from the theme variables, no
     hard-coded colors.
   - Click: a directory toggles; a markdown file calls `onSelect(path)`; a disabled file does
     nothing (AC-4). Clicking also focuses the row.
   - Keys on the `ul` (handled via `onKeyDown`, using the focused item): `ArrowDown`/`ArrowUp` move
     to the next/previous visible node; `Home`/`End` to the first/last; `ArrowRight` on a collapsed
     directory expands it, on an expanded one moves to its first child; `ArrowLeft` on an expanded
     directory collapses it, otherwise moves to the parent; `Enter` and `Space` act like a click
     (`preventDefault` so Space doesn't scroll); focus moves with `element.focus()` on the target
     row (AC-14).
   - Nothing else: no drag, rename, context menu or typeahead.
3. **Layout** (AC-13): the list is `min-w-0`, rows don't wrap (truncate), so a deep tree never
   makes the page scroll sideways; the parent (task 05) provides the scroll container.
4. Prettier on the touched files.

## Tests

The app has no unit test setup. By hand: temporarily render
`<FileTree>` from `routes/feature-detail.tsx` with the result of `getFeatureFiles` for a feature
whose folder has `requirements.md`, `tasks/01-api-x/spec.md` and a `.png` (do not commit this
wiring), against the running stack with task 02 in the stack:

- the folders and files show, folders first, nested folders expand and collapse by click (AC-1);
- expanding `tasks` → `01-api-x` shows `spec.md`; clicking it calls `onSelect("tasks/01-api-x/spec.md")`
  and the row is selected (AC-3);
- the `.png` row is greyed, has `aria-disabled="true"`, and clicking or pressing Enter does not
  call `onSelect` (AC-4);
- keyboard only: Tab lands on one row; arrows move, Right/Left expand/collapse and move to
  child/parent, Home/End jump, Enter/Space open a file or toggle a folder (AC-14); a screen reader
  (or the accessibility panel) shows `tree`, `treeitem`, levels, expanded and selected states;
- at 375 px a long file name truncates and the page doesn't scroll sideways (AC-13).

Remove the temporary wiring before the PR.

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/lib/feature-files.ts app/components/features/file-tree.tsx`

## Out of scope

- The viewer (task 04) and the Documents tab, refresh and states (task 05).
- Any change to `lib/features.ts`, `lib/api.ts` or `routes.ts`.
