---
feature: 002-pull-requests
status: approved
approved: 2026-11-05
---

# Pull requests

## Problem

Projects record which GitHub repository each codebase lives in (feature 001), but the app shows
nothing about the work happening in those repositories. To see what's waiting for review, people
have to leave the app and open each repository on GitHub.

## Users

People who sign in to the app with the shared password. They want to see a repository's open pull
requests at a glance, jump to one on GitHub, or look at what a pull request changes without
leaving the app.

## Scope

- **Data source:** open pull requests are read live from GitHub through the API, using the API's
  own GitHub token. Nothing is stored.
- **List:** a "Pull requests" card on each project's detail page (`/projects/:id`) lists the open
  pull requests of the project's repository.
  - The list loads on its own after the rest of the page, so a slow GitHub doesn't hold the page
    up.
  - It is paginated: 20 per page, newest first, with Previous and Next.
  - Each row shows:
    - the title and `#number`;
    - the author's avatar and login;
    - a "Draft" badge for draft pull requests;
    - the head → base branches;
    - the last updated time.
  - The title opens the details page in the app. An icon link opens the pull request on GitHub in
    a new tab.
  - The card is shown for every project, archived and unverified ones included.
- **Details page** (`/projects/:id/pulls/:number`):
  - **Summary:**
    - the title, `#number`, and a "Draft" badge when it applies;
    - the author;
    - head → base branches;
    - created and updated times;
    - the number of commits, and total additions and deletions;
    - an "Open on GitHub" link.
  - **Description:** rendered as GitHub-flavored Markdown. Raw HTML in it is not rendered.
  - **Changed files:** paginated at 30 per page. Each file shows:
    - its path, and the previous path when it was renamed;
    - its status (added, modified, removed, renamed);
    - its additions and deletions.
  - **Diffs:** each file expands, collapsed by default, to its unified diff, with added and removed
    lines colored. A file GitHub gives no patch for (binary, or too large) says so and links to
    the file on GitHub.
  - A breadcrumb leads back: Projects › project name › pull request.

## Non-goals

- Changing anything on GitHub: no reviews, comments, approvals, merges, labels or new pull
  requests. The feature is read-only.
- Listing closed or merged pull requests. A details page for one still works (AC-14).
- Assistant (agent) tools for pull requests; they can come in a later feature.
- Pull requests across all projects on one page, or for a repository that isn't a project.
- CI checks, labels and reviewers.
- Storing pull requests in the app's database or syncing them in the background.
- Repositories outside GitHub.

## Assumptions

- The API calls GitHub with the token from feature 001 (`API_GITHUB_TOKEN`). That token
  needs "Pull requests: read" on the repositories, on top of "Metadata: read".
- GitHub's REST API is the source: list open pull requests, get one pull request, list its
  files.
- The pull request list asks GitHub for one page at a time, so it costs one request per page.
  The details page costs two: the pull request and one page of its files.
- A pull request that is no longer open (merged or closed since the list loaded) still opens on
  the details page, with its state shown. The list only shows open ones.
- Diffs are shown as GitHub returns them (the `patch` of each file). Syntax highlighting is not
  required.

## Constraints

- **api:** follow `api/CLAUDE.md`: a feature package under `com.toptier.custom`, reuse the
  `github` `RestClient`, bearer auth on every route, `ProblemDetail` errors, upstream failure →
  502, upstream timeout → 504. Tests use `MockRestServiceServer` with fixtures shaped like real
  GitHub responses.
- **app:** follow `app/CLAUDE.md`: `clientLoader` pages inside the app layout, `PageHeader`,
  `Card`, `Empty`, `Alert` for errors, usable at phone width.
- GitHub rate limits: 5,000 requests an hour for a token. Each page load costs one or two
  requests.
- GitHub returns at most 3,000 changed files for one pull request.
- The description is untrusted text from GitHub: it is rendered without raw HTML, and links in
  it open in a new tab.

## Non-functional requirements

- Read-only. The token never reaches the browser.
- Pages answer within a few seconds; the GitHub call times out after 5 seconds, as in feature
  001.
- Usable at 375 px without horizontal page scrolling. Long file paths wrap or truncate.

## Open questions

- None.

## Acceptance criteria

### List on the project page

- **AC-1** — Given a project whose repository has open pull requests, when the user opens the
  project's detail page, then a "Pull requests" card lists them, newest first. Each row shows the
  title, `#number`, the author's avatar and login, head → base branches and the last updated time,
  plus a "Draft" badge on draft pull requests.
- **AC-2** — Given the list, when the user clicks a pull request's title, then the app opens its
  details page. When the user clicks its GitHub link, then the pull request opens on GitHub in a
  new tab.
- **AC-3** — Given a repository with more than 20 open pull requests, when the card loads, then
  it shows 20, and Next and Previous move between pages without reloading the rest of the
  project page.
- **AC-4** — Given a repository with no open pull requests, when the card loads, then it shows
  "No open pull requests".
- **AC-5** — Given an archived or unverified project, when its detail page opens, then the
  "Pull requests" card is shown and loads as for any other project.
- **AC-6** — Given GitHub is slow, when the project's detail page opens, then the project's
  details show at once and the card shows a loading state until the list arrives.

### Details page

- **AC-7** — Given an open pull request, when the user opens its details page, then it shows the
  title, `#number`, a "Draft" badge when it applies, the author, head → base branches, the created
  and updated times, the number of commits, the total additions and deletions, and an "Open on
  GitHub" link.
- **AC-8** — Given a pull request whose description has Markdown (headings, lists, code, links,
  task lists, tables), when the details page shows it, then it is rendered as Markdown. Raw HTML
  in it shows as text and is never executed, and links open in a new tab. An empty description
  shows "No description".
- **AC-9** — Given a pull request, when the details page loads, then it lists the changed files.
  Each shows its path, its status (added, modified, removed, renamed, with the previous path for a
  rename), and its additions and deletions.
- **AC-10** — Given more than 30 changed files, when the details page loads, then it shows 30,
  and Next and Previous move between pages of files.
- **AC-11** — Given a changed file, when the user expands it, then its unified diff shows, with
  added lines and removed lines colored differently. Files start collapsed.
- **AC-12** — Given a file GitHub returns no patch for (binary or too large), when it is
  expanded, then it says the diff isn't available here and links to the file on GitHub.
- **AC-13** — Given the details page, when the user uses the breadcrumb, then it leads back to the
  project and to the projects list.
- **AC-14** — Given a pull request that has been merged or closed, when its details page opens,
  then it shows its details with a "Merged" or "Closed" badge instead of failing.

### Errors and permissions

- **AC-15** — Given a signed-out visitor, when they open a pull request page, then they are sent
  to sign in. The API answers `401` to pull request requests without the token.
- **AC-16** — Given a pull request number that doesn't exist in the repository, or a project id
  that doesn't exist, when its details page opens, then a "Pull request not found" page shows with
  a link back to the project.
- **AC-17** — Given the repository doesn't exist on GitHub or the API's token can't see it, when
  the card or details page loads, then it says GitHub couldn't find the repository or the token
  can't see it, instead of a broken page.
- **AC-18** — Given GitHub is unreachable, rate-limits the API, rejects its token, isn't
  configured with a token, or doesn't answer within 5 seconds, when the card or details page
  loads, then an error message with a "Try again" button shows. On the project page, the rest of
  the page still works.

### Layout

- **AC-19** — Given a 375 px wide screen, when the user uses the card, the details page and the
  diffs, then the page doesn't scroll horizontally. Long paths wrap, and wide diff lines scroll
  inside their own box.

## Changelog

- 2026-11-05 — Created.
- 2026-11-05 — Open questions settled with the user; acceptance criteria written.
- 2026-11-05 — Approved.
