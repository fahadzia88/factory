---
feature: 001-projects
status: approved
approved: 2026-11-04
---

# Projects

## Problem

The app has no domain data yet: after signing in, the Dashboard is an empty placeholder and there
is nothing to create or manage. The factory builds software, but it has no record of the software
projects it works on: which codebases exist, where their repos live, what state they're in.

## Users

People who sign in to the app with the shared password. There are no individual user accounts,
so everyone who signs in sees and manages the same projects. They want to register the codebases
the factory works on, find them again, see their status at a glance, and ask the Assistant about
them.

## Scope

- **A project is a software project**: a codebase the factory builds, with a GitHub repository.
- **Fields:**
  - name (required);
  - repository URL (required);
  - description (optional, plain text);
  - status: `planning`, `active`, `maintenance` or `retired`.
  The system keeps created and updated times, whether the project is archived, and whether its
  repository has been verified.
- **Operations:** create, list, view and edit projects; archive a project and restore it. No
  permanent delete.
- **New projects:** the create form has a status field that starts on `planning`; any status can be
  picked.
- **Uniqueness:** no two projects (archived included) share a name, ignoring case. No two projects
  share a repository URL, compared as exact text.
- **Repository check:**
  - The repository must be a GitHub URL, `https://github.com/<owner>/<repo>`, public or private.
  - When a project is saved with a new or changed repository URL, the API checks with GitHub that
    the repository exists. It uses its own read-only GitHub token.
  - A repository the token can't see counts as not found, and the save is rejected.
  - If GitHub can't be reached, rate-limits the check, or doesn't answer in time, the project still
    saves, marked "Repository not verified". The check runs again on the project's next save.
- **Archived projects** are read-only, with a Restore action. Restoring makes them editable again
  and keeps their status.
- **Navigation:** a "Projects" item in the top nav, with a list page (`/projects`), a detail page
  (`/projects/:id`), and create and edit forms.
- **List page:**
  - a name search, a status filter (any status, or one), and a "Show archived" switch;
  - sortable by name, status or last updated (default: last updated, newest first);
  - 20 per page, paginated by the API;
  - the search, filters, sort and page are kept in the URL.
- **Dashboard:** in place of its empty state, a count of non-archived projects per status (each
  linking to the list filtered by that status), the 5 most recently updated non-archived projects,
  and a "View all" link.
- **Assistant:** can read every field of every project, archived ones included. It can't change
  anything.

## Non-goals

- Individual user accounts, roles or per-user permissions (the app has one shared password).
- Permanently deleting a project.
- The Assistant creating, editing, archiving or restoring projects.
- Repositories outside GitHub.
- Treating differently written URLs of the same repository as duplicates. With exact-text
  matching, `https://github.com/acme/app` and `https://github.com/acme/App.git` count as different
  repositories.
- Syncing anything else from GitHub (branches, commits, stars), or re-checking repositories in the
  background.
- Linking projects to features, PRs or other records (later features can attach to projects).

## Assumptions

- Name: up to 100 characters. Description: up to 2,000 characters. Leading and trailing spaces are
  trimmed before validation and the uniqueness check.
- "Show archived" adds archived projects to the list, each with an "Archived" badge. It doesn't
  show archived projects only.
- Changing only the name, description or status of a verified project doesn't call GitHub. An
  unverified project is re-checked on every save until a check succeeds.
- Archiving asks for confirmation; restoring doesn't.
- The number of projects is in the hundreds at most, which keeps search and counts simple.
- The Assistant reads projects through the API with the token it already uses.

## Constraints

- **api:** follow the existing patterns in `api/CLAUDE.md`:
  - a `project` package under `com.toptier.custom`;
  - bearer-token auth on every route, with no new public route;
  - `ProblemDetail` errors;
  - typed `@ConfigurationProperties`;
  - one `RestClient` bean for GitHub.
- **Schema:** there's no migration tool, so the schema comes from the JPA entities
  (`ddl-auto=update`).
- **GitHub token:** the API needs a new secret, a read-only GitHub token with Metadata read on the
  repositories projects may use. It is separate from the agent's workflow token and is never sent
  to the browser.
- **app:** pages go inside the authenticated app layout and use the existing components, following
  `app/CLAUDE.md`.

## Non-functional requirements

- **Security:** the GitHub token lives only in the API's environment and never appears in
  responses or logs. GitHub errors are mapped to the outcomes above, without raw upstream details.
- **Performance:** the GitHub check times out after 5 seconds and the save continues as
  unverified. List and Dashboard requests answer within 1 second for a few hundred projects.
- **Accessibility:** forms have labels and announce field errors. The list's sort and filter
  controls work by keyboard.
- **Layout:** the list, detail, forms and Dashboard work at phone width (375 px) without
  horizontal page scrolling.

## Open questions

- None.

## Acceptance criteria

### Navigation and access

- **AC-1** — Given a signed-in user, when any page inside the app layout is shown, then the top nav
  has a "Projects" item that opens `/projects`.
- **AC-2** — Given a visitor who isn't signed in, when they open `/projects` or
  `/projects/<id>`, then they are sent to `/login`. Given an API request without a valid bearer
  token, when it calls any projects endpoint, then it gets `401`.

### Create and edit

- **AC-3** — Given the create form, when it opens, then the status field shows `planning` and
  offers all four statuses.
- **AC-4** — Given a name, a GitHub URL of a repository the API's token can see, an optional
  description and a status, when the user saves, then the project is created. The user lands on
  its detail page, which shows every field, the repository as a link, and the created and updated
  times.
- **AC-5** — Given an empty or over-long name, an empty repository URL, a URL that isn't
  `https://github.com/<owner>/<repo>`, or an over-long description, when the user saves, then
  nothing is saved, the field shows an error, and the form keeps what was entered.
- **AC-6** — Given a project named "Factory" (archived or not), when another project is saved as
  "factory", then the save is rejected with an error on the name field.
- **AC-7** — Given a project with repository URL `https://github.com/acme/app`, when another
  project is saved with exactly that URL, then the save is rejected with an error on the
  repository field. A URL that differs only in case or a trailing `.git` is accepted.
- **AC-8** — Given a repository URL that doesn't exist on GitHub or that the token can't see, when
  the project is saved, then the save is rejected with "Repository not found on GitHub" on the
  repository field.
- **AC-9** — Given GitHub is unreachable, rate-limits the check, or doesn't answer within 5
  seconds, when a project with a new or changed repository URL is saved, then it is saved, and its
  detail page and list row show "Repository not verified".
- **AC-10** — Given an unverified project, when it is saved again and GitHub confirms the
  repository exists, then the "Repository not verified" marker is gone. If GitHub reports it not
  found, the save is rejected as in AC-8.
- **AC-11** — Given a non-archived project, when the user edits any field and saves, then the
  detail page shows the changes and a new updated time. A changed repository URL is checked as in
  AC-8 to AC-9. Changing only other fields of a verified project doesn't call GitHub.

### List

- **AC-12** — Given more than 20 non-archived projects, when the user opens `/projects`, then the
  list shows 20 per page, most recently updated first. Each row shows name, status, repository and
  last updated, and pagination moves between pages.
- **AC-13** — Given the list, when the user searches for part of a name, then only projects whose
  name contains it, ignoring case, are listed, across all pages.
- **AC-14** — Given the list, when the user picks a status in the filter, then only projects with
  that status are listed. Picking "Any status" lists all of them again.
- **AC-15** — Given archived projects exist, when the list opens, then they aren't listed. When the
  user turns on "Show archived", then they are listed too, each with an "Archived" badge.
- **AC-16** — Given the list, when the user chooses to sort by name, status or last updated, then
  the list is sorted by that column. Choosing the same column again reverses the order.
- **AC-17** — Given a search, filter, archived switch, sort and page are set, when the page is
  reloaded, opened from a copied link, or reached with Back, then the same view is shown.
- **AC-18** — Given no projects exist, when the user opens `/projects`, then an empty state with a
  "New project" button is shown. Given projects exist but none match the search and filters, then
  a "No projects match" message with a way to clear the filters is shown.

### Archive and restore

- **AC-19** — Given a non-archived project, when the user archives it and confirms, then it leaves
  the default list, appears when "Show archived" is on, and its detail page says it's archived.
- **AC-20** — Given an archived project, when its detail page opens, then its fields are read-only,
  there is no Edit action, and there is a Restore action. Given an API request to edit an archived
  project, then it is rejected with `409`.
- **AC-21** — Given an archived project, when the user restores it, then it is editable again,
  appears in the default list, and keeps its status.
- **AC-22** — Given any project, then neither the app nor the API offers a way to delete it
  permanently.
- **AC-23** — Given an id that matches no project, when the user opens `/projects/<id>`, then a
  "Project not found" page with a link to the list is shown, and the API answers `404`.

### Dashboard

- **AC-24** — Given non-archived projects exist, when the user opens the Dashboard, then it shows
  the number of non-archived projects in each status, each linking to `/projects` filtered by that
  status. It also shows the 5 most recently updated non-archived projects, each linking to its
  detail page, and a "View all" link to `/projects`.
- **AC-25** — Given no non-archived projects exist, when the user opens the Dashboard, then it
  shows an empty state with a "New project" button that opens the create form.

### Assistant

- **AC-26** — Given projects exist, some of them archived, when the user asks the Assistant which
  projects there are, then it lists every project, archived ones included and marked as archived,
  from the stored data.
- **AC-27** — Given a project exists, when the user asks the Assistant about it by name, then it
  answers with the project's stored fields: repository, status, description, archived and
  verification state.
- **AC-28** — Given the Assistant, when the user asks it to create, edit, archive or restore a
  project, then it says it can't change projects and points to the Projects page, and no project
  changes.

### Errors and layout

- **AC-29** — Given the API is unavailable, when the list, a detail page or the Dashboard loads,
  then an error message with a way to retry is shown instead of a broken page.
- **AC-30** — Given a 375 px wide screen, when the user uses the list, detail page, forms and
  Dashboard, then everything is usable without horizontal page scrolling.

## Changelog

- 2026-11-04 — Created.
- 2026-11-04 — Open questions settled with the user; acceptance criteria written.
- 2026-11-04 — Approved.
