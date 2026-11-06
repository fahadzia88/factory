---
feature: 002-pull-requests
task: 04-app-pull-files
layer: app
status: approved
approved: 2026-11-05
branch: feat/002-pull-requests/04-app-pull-files
base: feat/002-pull-requests/03-app-pull-detail
covers: [AC-9, AC-10, AC-11, AC-12, AC-19]
---

# 04 — Changed files and diffs

## Goal

Add the changed files to the details page: 30 per page, each expanding to a colored unified diff,
with a fallback when GitHub has no patch.

## Contract

Consumes `GET /api/projects/{id}/pulls/{number}/files?page=` → `{ items: [PullRequestFile], page,
size: 30, hasNext }`. Each PullRequestFile has:

- `filename`;
- `previousFilename`, or `null`;
- `status`: `added`, `removed`, `modified`, `renamed`, `copied`, `changed` or `unchanged`;
- `additions`, `deletions`;
- `patch`, or `null`;
- `htmlUrl`.

Errors are as in task 03.

## Files

- `app/app/components/pull-requests/changed-files.tsx` — new: `ChangedFiles` (the list and its
  pager).
- `app/app/components/pull-requests/file-diff.tsx` — new: `FileDiff` (a parsed patch).
- `app/app/app.css` — change (shared file; additive): `--diff-added` and `--diff-removed`
  background tokens for light and dark, registered in `@theme inline` as
  `--color-diff-added` and `--color-diff-removed`.
- `app/app/routes/pull-request-detail.tsx` — change: load the files page, and render
  `ChangedFiles`.

## Steps

1. **Loader:**
   - Read `page` from `?page=`, a positive integer, defaulting to 1.
   - Start `listPullRequestFiles(id, number, page)` without awaiting it, and return the promise
     as `files`. The summary renders first, and the file list streams in under `<Suspense>` +
     `<Await>`.
   - The `errorElement` is an `Alert` with `pullRequestError`'s message and Try again. It must not
     be `null`.
2. **`ChangedFiles`:**
   - A `Card` titled "Changed files", with the count from `pull.changedFiles`.
   - The range: "Showing 31–60 of 75".
   - Previous and Next are `Link`s to `?page=n-1` and `?page=n+1`. Next shows only when
     `hasNext`, and Previous only when `page > 1` (AC-10).
   - An empty page past the end says "No files on this page" and links back to page 1.
3. **Each file** is a native `<details>`, collapsed by default (AC-11). Its `<summary>` row shows:
   - a chevron;
   - the path in `font-mono` with `break-all`, and for a rename `previousFilename → filename`;
   - a status `Badge`;
   - `+n` and `−n`.

   The row is keyboard-operable, which `<details>` gives for free (AC-9).
4. **`FileDiff`:**
   - Split `patch` on newlines and render one row per line in a `<pre>`, inside a box with
     `overflow-x-auto` and `max-h-[32rem] overflow-y-auto`, so wide lines scroll inside it and the
     page doesn't (AC-19).
   - Lines starting with `@@` get muted text on `bg-muted`. `+` lines get `bg-diff-added`, and `-`
     lines `bg-diff-removed`. Keep the leading `+`, `-` or space so the diff still reads without
     colour (AC-11).
   - Render it only when the `<details>` is open: track `open` with `onToggle` state, so a page
     of big diffs isn't all in the DOM.
5. **No patch** (`patch === null`): "The diff isn't available here (binary or too large)." with a
   "View on GitHub" link to `htmlUrl`, in a new tab (AC-12).
6. **The tokens:** pale green and pale red at low chroma, for example light
   `oklch(0.95 0.05 150)` and `oklch(0.95 0.04 25)`, and dark ones at about 0.3 lightness.
   Check the contrast with `--foreground`.
7. Format the touched files with Prettier.

## Tests

By hand, against a PR with more than 30 files, a rename, a binary file and a very long path:

- The rows show the path, status, previous path for the rename, and +/− (AC-9).
- Next and Previous move between pages, and the URL holds `?page=` (AC-10).
- The files start collapsed. Expanding one shows the diff with added and removed lines coloured,
  in light and dark themes (AC-11).
- The binary file says the diff isn't available and links to GitHub (AC-12).
- At 375 px, the long path wraps and a wide diff line scrolls inside its box (AC-19).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check` on the changed files

## Out of scope

- Syntax highlighting.
- Split (side-by-side) view.
- Comments on lines.
