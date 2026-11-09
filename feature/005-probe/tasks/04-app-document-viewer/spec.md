---
feature: 005-probe
task: 04-app-document-viewer
layer: app
status: approved
approved: 2026-11-08
branch: feat/005-probe/04-app-document-viewer
base: feat/005-probe/03-app-file-tree
covers: [AC-2, AC-8, AC-12]
---

# 04 — Document viewer

## Goal

A component that loads one markdown file of the feature and renders it, with its loading, empty,
"file no longer exists" and error states. Nothing mounts it yet: task 05 does.

## Contract

Consumes, copied from task-breakdown.md:

- `GET /api/projects/{id}/features/{featureId}/files/content?path=<path>` →
  `200 { "path", "content", "size", "updatedAt" }`. Errors: `400 invalid_path`,
  `404 file_not_found`, `415 unsupported_file_type`, `413 file_too_large`, `409 worktree_missing`,
  plus `404 project_not_found`, `404 feature_not_found`, `401`. Errors are `ProblemDetail` with a
  `code` and a `detail` the app can show as is.
- The app wrapper is `getFeatureFile()` and `featureErrorCode()` from `lib/feature-files.ts`
  (task 03).

## Files

- `app/app/components/features/document-viewer.tsx` — new: `DocumentViewer`.

No other file changes; `Markdown` (`components/pull-requests/markdown.tsx`) is imported as is.

## Steps

1. **Props:** `{ projectId: number; featureId: number; path: string | null; reloadKey: number }`.
   `reloadKey` is bumped by the parent to reload the open file (task 05).
2. **State and loading** (modelled on `use-checkout.ts`'s stale-answer guard):
   - `state`: `{ status: "idle" } | { status: "loading"; file: FeatureFile | null } | { status:
     "loaded"; file: FeatureFile } | { status: "error"; code: string | null; error: FeatureError }`.
   - An effect on `[projectId, featureId, path, reloadKey]`: `path === null` → `idle`; otherwise
     `loading` and call `getFeatureFile`; a request-id ref ignores answers that arrive after the
     inputs changed or the component unmounted. While reloading the same path keep showing the
     previous `file` (`loading.file`) so a refresh doesn't flash; a new `path` clears it.
   - Failure → `error` with `featureErrorCode(error)` and `featureError(error)`.
3. **Render**, one bordered container (`min-w-0 rounded-lg border`), `aria-busy` while loading,
   `aria-live="polite"`; the heading area focusable (`tabIndex={-1}`, focused on a new `path` so
   keyboard and screen-reader users land on the content, announcing the file name):
   - `idle`: `Empty` with `FileTextIcon`, title "Select a document", description "Choose a
     markdown file from the list to read it here." (also the first-open state);
   - `loading` with no file: `Skeleton` lines (a title bar and three body lines);
   - `loaded` / reloading with a file: a header row with the path in `font-mono text-xs
     break-all` and "Updated <formatDateTime(updatedAt)>" in muted `text-xs` (the same
     `formatDateTime` the feature page uses), then the body: `<Markdown>{file.content}</Markdown>`
     inside `div.min-w-0.overflow-x-auto.p-4`. An empty `content` shows muted "This file is empty.";
   - `error` with code `file_not_found`: `Empty` with `FileQuestionIcon`, title "This file no longer
     exists", description "It was removed or renamed. Refresh the list to see what's there." (AC-8);
   - any other `error`: `Alert variant="destructive"` titled "Couldn't open the file" with the
     message (the `detail` for `worktree_missing`, `unsupported_file_type`, `file_too_large`) and,
     when `retryable`, a "Try again" button that reloads (bump an internal counter).
4. **Safety (AC-12):** `Markdown` renders raw HTML as text and has no HTML plugin. Don't add
   `rehype-raw`, `dangerouslySetInnerHTML` or any HTML pass-through here; links keep the
   component's `target="_blank" rel="noreferrer"`. Relative links to other documents are not
   rewritten (out of scope).
5. **Layout** (AC-13): long lines in code blocks and tables scroll inside the body
   (`overflow-x-auto`), never the page; `min-w-0` on the container.
6. Prettier on the touched file.

## Tests

The app has no unit test setup. By hand, rendering the viewer temporarily from
`routes/feature-detail.tsx` with `path="requirements.md"` (do not commit this wiring), against the
running stack with task 02 in the stack:

- the feature's requirements render as formatted markdown: headings, lists, a GFM table and the
  path/updated line (AC-2);
- a file containing `<script>alert(1)</script>` and `<img src=x onerror=alert(1)>` shows them as
  text; no alert fires and no `script` element is in the DOM (AC-12);
- `path={null}` shows "Select a document"; a path that doesn't exist (`nope.md`) shows "This file
  no longer exists" (AC-8); delete the open file in the worktree, bump `reloadKey`, same message;
- change the file on disk and bump `reloadKey`: the new text appears without flashing a skeleton;
- with the API stopped (`docker stop factory-api`) the viewer shows "Couldn't open the file" with
  Try again; `docker start factory-api` and Try again loads it;
- a 200 KB+ markdown file shows the "larger than 200 KB" message;
- at 375 px a wide table scrolls inside the viewer, not the page (AC-13).

Remove the temporary wiring before the PR.

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/components/features/document-viewer.tsx`

## Out of scope

- The tree and the tab, refresh button and auto-refresh (task 05).
- Rewriting relative links between documents, editing, syntax highlighting.
- Any change to `Markdown`, `lib/api.ts` or `lib/features.ts`.
