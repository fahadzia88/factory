---
feature: 005-probe
status: draft
approved:
---

# Probe

## Problem

A feature's documents (requirements, task breakdown, task specs) live in its folder `feature/<NNN>-<slug>/` on the feature's docs branch worktree. Today the only way to read them is to open the repository outside the app. The feature details page shows no folder contents, so users can't browse or read the documents next to the chat that produces them.

## Users

People who run features in the app (reviewers and approvers) who want to browse and read a feature's documents from its details page without leaving the app.

## Scope

- A navigation tree on the feature details page showing the folders and files inside this feature's folder (`feature/<NNN>-<slug>/`), read from the feature's worktree.
- A markdown viewer on the same page that renders a selected `.md` file from the tree.
- Navigating between all markdown files in the folder, including nested ones (for example `tasks/01-.../spec.md`).

## Non-goals

- Editing, creating, renaming or deleting files from the page (the agent chat still writes documents).
- Showing files outside the feature's folder.
- Viewing non-markdown files (see open questions).
- Diffs or history of the documents.

## Assumptions

- The feature's worktree is checked out on the server and is the source of truth for the folder contents.
- Feature folders are small (tens of files), so the whole tree can be loaded in one request.

## Constraints

- Contents come from the worktree on the docs branch, not from GitHub.
- Must reuse the existing feature details page and, where it fits, the existing markdown renderer (`app/app/components/pull-requests/markdown.tsx`).
- Existing feature page and chat behavior must keep working.
- Only paths inside the feature's folder may be served (no path traversal).

## Non-functional requirements

- Security: the API only returns files under the feature's folder; `..`, absolute paths and symlinks pointing outside are rejected. Markdown is rendered without executing raw HTML or scripts.
- Performance: the tree loads in one request; a file loads on selection.
- Accessibility: the tree is keyboard navigable and has proper labels and roles; the selected file is announced.
- Layout: usable at phone width (tree and viewer don't need to be side by side).

## Open questions

1. Should the tree and viewer refresh automatically after the agent writes or commits a document, or only on page load or manual refresh?
2. Should the tree list only markdown files, or all files (with non-markdown shown as disabled or as plain text)?
3. Where on the details page should it go: a tab beside the chat, a side panel, or a section below?
4. Which version should be shown: the working tree (including uncommitted changes) or the last commit on the branch?

## Acceptance criteria

To be written once the open questions are settled.

## Changelog

- 2026-11-07 — Created.
- 2026-11-07 — First draft of problem, scope and constraints; open questions listed.
