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

- A "Documents" tab on the feature details page, beside the chat, with a navigation tree of the folders and files inside this feature's folder (`feature/<NNN>-<slug>/`), read from the feature's worktree.
- A markdown viewer in the same tab that renders a selected `.md` file from the tree.
- Navigating between all files in the folder, including nested ones (for example `tasks/01-.../spec.md`). All files and folders are listed; only `.md` files can be opened.
- Showing the current worktree contents, including uncommitted edits.
- Refreshing the tree and open file automatically after each chat turn, plus a manual refresh button.

## Non-goals

- Editing, creating, renaming or deleting files from the page (the agent chat still writes documents).
- Showing files outside the feature's folder.
- Viewing non-markdown files (they're listed but disabled).
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
- Layout: usable at phone width; the tree stacks above the viewer.

## Open questions

- None. (Approval needs this list empty.)

## Acceptance criteria

- **AC-1** — Given a feature whose worktree is checked out, when I open its details page and select the Documents tab, then I see a tree of the folders and files in `feature/<NNN>-<slug>/`, with nested folders expandable and collapsible.
- **AC-2** — Given the Documents tab is open, when I select a `.md` file in the tree, then its content is rendered as formatted markdown in the viewer and the file is marked as selected in the tree.
- **AC-3** — Given the tree contains nested folders such as `tasks/01-x/spec.md`, when I expand them and select that file, then it opens in the viewer.
- **AC-4** — Given the folder contains non-markdown files, when I view the tree, then they are listed but disabled, and selecting them doesn't open the viewer.
- **AC-5** — Given a file has uncommitted edits in the worktree, when I open it, then the viewer shows the edited content.
- **AC-6** — Given the Documents tab is open, when the agent finishes a chat turn that changed documents, then the tree and the open file update without a page reload.
- **AC-7** — Given the Documents tab is open, when I press the refresh button, then the tree and the open file reload from the worktree.
- **AC-8** — Given the open file was deleted, when the tree refreshes, then the viewer shows a "file no longer exists" message and the tree no longer lists it.
- **AC-9** — Given the feature folder is empty or missing, when I open the Documents tab, then I see an empty state saying there are no documents yet, not an error.
- **AC-10** — Given the feature's worktree is missing, when I open the Documents tab, then I see a message telling me to check the branch out again on the project page.
- **AC-11** — Given a request for a path outside the feature folder (for example `../rules.md`, an absolute path, or a symlink pointing outside), when it reaches the API, then it is rejected and no file content is returned.
- **AC-12** — Given a markdown file contains raw HTML or script tags, when it is rendered, then no script executes.
- **AC-13** — Given a phone-width screen, when I use the Documents tab, then the tree appears above the viewer and both are usable without horizontal page scrolling.
- **AC-14** — Given I use only the keyboard, when I focus the tree, then I can move, expand, collapse and open files with the arrow, Enter and Space keys.
- **AC-15** — Given I am not signed in, when the tree or file endpoints are called, then the request is refused as for the other feature endpoints.
- **AC-16** — Given the feature page before this change, when I use the chat and the existing tabs or sections, then they behave as before.

## Changelog

- 2026-11-07 — Created.
- 2026-11-07 — First draft of problem, scope and constraints; open questions listed.
- 2026-11-08 — Open questions settled (auto-refresh plus manual button, list all files with only .md openable, Documents tab beside chat, worktree version); acceptance criteria written.
