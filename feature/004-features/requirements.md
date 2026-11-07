---
feature: 004-features
status: approved
approved: 2026-11-06
---

# Features

## Problem

Projects (001) have a checkout and worktrees (003), but the factory keeps no record of the features
being built on them. The feature workflow (requirements → breakdown → specs → stacked PRs) exists
only as files in the factory repo's `feature/` folder and as Claude Code skills in
`.claude/skills/`. From the app you can't start a feature on a project or see where each feature
stands, and the agent can't run the workflow inside a project's own repository. The agent's old
workflow subagents (`agent/agent/`) still use eve and aren't wired into the running agent.

## Users

- **People who sign in to the app** (shared password, no individual accounts). They start a
  feature on a project by naming it, work through its steps with the agent in the feature's chat,
  and want to see each feature's status and the status of each of its steps.
- **The agent.** It runs a project's workflow steps for a feature using that project's own skills,
  writes the feature's documents in the feature's worktree, and records the status of the feature
  and of each step through the API.
- **The API.** It creates and numbers features, sets up each feature's worktree and folder, reads
  the project's workflow, and gives the agent everything it needs to find the feature.

## Scope

- **A feature belongs to one project.** It has a number (`NNN`, per project), a name, a slug, a
  status, steps, one worktree, a chat conversation, and created and updated times.
- **Workflow file.** Each project's repository defines its workflow in `feature/workflow.json`,
  next to its `feature/rules.md`:
  - the feature states, in order; the first is the state a new feature starts in;
  - the step types, in order, each with a key, a title, the skill folder that runs it (under
    `.claude/skills/`), and its states, the first being the state a new step starts in.
  The factory repository gets its own `feature/workflow.json` describing its current workflow.
- **Create.** On a non-archived project with a ready checkout, the project page has a "New
  feature" action that asks for a name. The API then:
  1. reads and validates `feature/workflow.json` from the project's main worktree, and refuses
     with a message naming the problem if it is missing or invalid;
  2. picks the next number and derives the slug from the name;
  3. creates the feature's worktree on branch `feat/<NNN>-<slug>/00-docs` from the default branch,
     through the checkout services (003);
  4. creates `feature/<NNN>-<slug>/` in that worktree with the project's
     `feature/_templates/requirements.md`, filled with the feature id and name;
  5. commits it and pushes the branch to GitHub;
  6. stores the feature record, in the first feature state, with a copy of the workflow it was
     created under.
  If a step after the worktree fails, the API undoes what it did (worktree, record) and reports
  the failure; nothing half-made is left.
- **Statuses.**
  - The agent sets the feature's status and each step's status through the API.
  - The API accepts only states the feature's workflow lists; anything else is refused.
  - People can't change statuses in the app; they see them.
- **Steps.**
  - The agent adds a step record when it starts a step: the step type, a title (for example
    "Spec: 01-api-feature-records"), and optionally the document's path in the feature folder.
  - A step type can be added more than once (one spec per task, for example).
  - The agent updates a step's status, title and document path as it works.
- **Agent skills.**
  - For a feature, the agent reads each step type's skill (`SKILL.md`) from the feature's
    worktree, so every project's own skills and `rules.md` apply.
  - The agent follows the skill with its own narrow tools: read files in the worktree, write files
    only in the feature folder, commit, push through the API (003), add and update steps, and set
    the feature's status.
  - Skills written for Claude Code mention things the agent doesn't have (for example
    AskUserQuestion or `/feature-breakdown`). The agent's instructions map them: questions are
    asked in the chat, and the next step is suggested in words.
  - Approvals follow the project's `rules.md`: the agent records an approval only after the user
    says so in the chat.
- **Feature chat.**
  - The feature page has a chat with the agent bound to that feature: every turn knows which
    project and feature it is about.
  - Each feature has one conversation. Opening the page again continues it.
- **Association.** The API gives the agent the feature's worktree path, branch, folder path,
  workflow and current status. The agent never builds paths or branch names itself.
- **App.**
  - Project page: a "Features" card listing the project's features (number, name, status, last
    updated), newest first, each linking to its page, with a "New feature" action.
  - Feature page (`/projects/:id/features/:featureId`): name, number, status, branch and worktree
    path, created and updated times, the steps in the order they were added with their type,
    title, status and updated time, and the chat.

## Non-goals

- **Implementation is feature 005.** Dispatching tasks, writing code, task worktrees, stacked PRs
  and restacking. Already decided for 005: checks run in GitHub CI only (the agent doesn't run
  project commands), and the API opens and retargets pull requests with its own token; the agent
  keeps no GitHub credentials.
- Renaming, archiving or deleting a feature. A workflow's own end states (for example `done`,
  `abandoned`) mark a finished feature.
- People changing a feature's or step's status in the app.
- Importing features that already exist as folders in a repository (the factory's 001–003) as
  records. They are counted when numbering, nothing more.
- A built-in default workflow for projects without `feature/workflow.json`.
- Editing the workflow file from the app.
- The Assistant page listing or discussing features.
- Running the SDK's own skills loading (`settingSources`), or giving the agent built-in Bash, file
  or web tools.

## Assumptions

- **Workflow file shape** (details fixed in the breakdown):
  ```json
  {
    "featureStates": ["requirements", "breakdown", "specs", "implementation", "done", "abandoned"],
    "steps": [
      { "key": "requirements", "title": "Requirements", "skill": "feature-requirements",
        "states": ["draft", "approved"] }
    ]
  }
  ```
  Valid means: parses as JSON, at least one feature state and one step type, no duplicate
  states or keys, and every `skill` folder has a `SKILL.md` in the repository.
- **Numbering:** the next number is one more than the highest of the project's feature records
  and the `feature/<NNN>-*` folders on the main worktree's default branch, padded to three digits.
- **Slug:** the name lowercased, runs of characters other than `a-z` and `0-9` turned into one
  `-`, trimmed of `-`, cut to 40 characters. A name that gives an empty slug is refused.
- **Name:** required, trimmed, up to 100 characters. Names needn't be unique; the number tells
  features apart.
- The workflow copy stored on create is the one statuses are checked against for that feature.
  Changing the project's workflow file affects only features created afterwards.
- Writes during the doc steps happen only on the feature's `00-docs` branch, only under
  `feature/<NNN>-<slug>/`. The agent commits and pushes after each change to a document.
- The API runs one feature create at a time per project, so two features never get the same
  number.
- The project's repository has `feature/_templates/requirements.md`. If it doesn't, create is
  refused like a missing workflow file.
- Removing a feature's worktree on the project page (003) leaves the feature record; the feature
  page then shows the worktree as missing and the agent's tools say so.

## Constraints

- **api:** follow `api/CLAUDE.md`: a `feature` package, bearer auth on every route, `ProblemDetail`
  errors (bad input → 400, missing → 404, conflict or invalid state → 409, GitHub failure → 502,
  timeout → 504), schema from JPA entities, `Clock`. Worktrees, commits' push and fetch go through
  the existing checkout services (003); tests use local bare repos, never GitHub.
- **agent:** follow `agent/AGENTS.md`: narrow `mcp__app` tools only, no built-in tools, erasable
  TypeScript. `src/chat.ts`, `src/server.ts` and `agent/README.md` are locked: the feature binding
  has to fit the existing chat API or be agreed as a skeleton change first. The eve-based
  `agent/agent/` code may be reused or retired, but isn't wired in as is.
- **app:** follow `app/CLAUDE.md`. `components/agent/agent-chat.tsx` is locked; a feature-bound
  chat needs project-owned code around it, or a skeleton change agreed first.
- **Base files:** `feature/rules.md`, `feature/_templates/*` and `.claude/skills/feature-*` are
  locked. `feature/workflow.json` is new and project-owned here; whether the skeleton should ship
  one for every project is for the user to decide in the skeleton repo.
- **Existing worktree rules (003)** stay: branch names validated, paths built by the API, the
  agent has no git credentials.

## Non-functional requirements

- **Security:** the agent writes only inside the feature's folder in its worktree; reads stay
  inside that worktree. No git or GitHub credentials in the agent. Status values are checked by
  the API, not just by prompts.
- **Performance:** creating a feature (worktree, commit, push) answers within the API's git time
  limits from 003; the feature page loads within 1 second without the chat.
- **Accessibility:** the "New feature" form has a label and announces errors.
- **Layout:** the Features card, the form and the feature page, chat included, work at 375 px
  without horizontal page scrolling; long branch names and paths wrap.

## Open questions

- None.

## Acceptance criteria

### Workflow file

- **AC-1** — Given a project whose main worktree has a valid `feature/workflow.json`, when a
  feature is created, then the feature starts in the file's first feature state and the feature
  stores the workflow it was created under.
- **AC-2** — Given a project whose repository has no `feature/workflow.json`, an invalid one (bad
  JSON, no states, duplicate keys, a skill folder without `SKILL.md`), or no
  `feature/_templates/requirements.md`, when the user creates a feature, then it is refused with
  a message naming the file and the problem, and no worktree, branch or record is created.
- **AC-3** — Given the factory repository, when its main worktree is read, then it has a valid
  `feature/workflow.json` describing its current workflow.

### Create

- **AC-4** — Given a non-archived project with a ready checkout, when the user opens its page, then
  a "Features" card has a "New feature" action. Given an archived project or one without a ready
  checkout, then the action is not offered and the API refuses create with `409`.
- **AC-5** — Given the "New feature" form, when the user enters "Feature records" and saves, then
  the user lands on the new feature's page. It shows the next number, the name, the first feature
  state, branch `feat/<NNN>-feature-records/00-docs` and its worktree path.
- **AC-6** — Given that feature, then its worktree is listed on the project's checkout card,
  `feature/<NNN>-feature-records/requirements.md` exists in it from the project's template with the
  feature id and name filled in, it is committed, and the branch is on GitHub at that commit.
- **AC-7** — Given features numbered up to 004 and a folder `feature/006-x/` on the default branch,
  when a feature is created, then it is numbered 007.
- **AC-8** — Given an empty name, a name over 100 characters, or a name with no letters or digits,
  when the user saves, then nothing is created, the field shows an error, and the form keeps what
  was entered.
- **AC-9** — Given the push to GitHub fails or times out during create, when the user saves, then
  the API answers `502` or `504`, the worktree and record are removed, and the form shows the error
  with a way to retry.
- **AC-10** — Given two creates on the same project at the same time, then the two features get
  different numbers and branches.

### Statuses and steps

- **AC-11** — Given a feature, when the agent sets its status to a state its workflow lists, then
  the feature page shows the new status and a new updated time. Given a state the workflow doesn't
  list, then the API refuses with `409` naming the allowed states and nothing changes.
- **AC-12** — Given a feature, when the agent adds a step of a type its workflow lists, then the
  step is recorded in that type's first state with its title and document path, and the feature
  page lists it after the steps added before it. A step type the workflow doesn't list is refused
  with `409`.
- **AC-13** — Given a step, when the agent sets its status to one of its type's states, or changes
  its title or document path, then the feature page shows the change. A state not listed for that
  type is refused with `409`.
- **AC-14** — Given a step type already used on a feature, when the agent adds another step of the
  same type, then both are listed.
- **AC-15** — Given the feature page, then it offers no control to change the feature's or a step's
  status.

### Agent and skills

- **AC-16** — Given a feature, when the user asks in its chat to start the requirements step, then
  the agent follows the project's `.claude/skills/<skill>/SKILL.md` for that step, read from the
  feature's worktree: it asks its questions in the chat and edits
  `feature/<NNN>-<slug>/requirements.md`.
- **AC-17** — Given the agent edits a document, then the change is committed on the feature's
  branch and pushed through the API, and the branch on GitHub has it.
- **AC-18** — Given a project whose skill or `rules.md` differs from the factory's, when the agent
  runs that step for one of its features, then it follows the project's version.
- **AC-19** — Given the agent tries to write outside `feature/<NNN>-<slug>/`, or on a branch other
  than the feature's, then the tool refuses and nothing is written.
- **AC-20** — Given a document waiting for approval, when the user hasn't explicitly approved it in
  the chat, then the agent doesn't record it as approved. When the user approves, then the agent
  sets the document's and the step's status and the page shows them.
- **AC-21** — Given a feature whose worktree was removed, when the agent is asked to work on it,
  then its tools fail with a message saying the worktree is missing, and the feature page shows the
  worktree as missing.

### Feature chat

- **AC-22** — Given a feature page, when the user sends a message, then the agent answers about
  that feature without being told the project or feature name.
- **AC-23** — Given a feature with an earlier conversation, when the user opens its page again,
  then the earlier messages are shown and the conversation continues. Another feature's page shows
  its own conversation.

### List and page

- **AC-24** — Given a project with features, when its page opens, then the Features card lists
  each feature's number, name, status and last updated time, newest first, each linking to its
  page. Given none, then an empty state with "New feature" (when allowed) is shown.
- **AC-25** — Given an id that matches no feature, or a feature of another project, when
  `/projects/:id/features/:featureId` opens, then a "Feature not found" page with a link to the
  project is shown, and the API answers `404`.
- **AC-26** — Given a feature, then neither the app nor the API offers rename, archive or delete.

### Errors, security and layout

- **AC-27** — Given a request without a valid bearer token, when it calls any feature endpoint,
  then it gets `401`.
- **AC-28** — Given the API is unavailable, when the Features card or the feature page loads, then
  an error message with a way to retry is shown and the rest of the project page still works.
- **AC-29** — Given a 375 px wide screen, when the user uses the Features card, the form and the
  feature page with its chat, then nothing scrolls horizontally and long branch names and paths
  wrap.

## Changelog

- 2026-11-06 — Created.
- 2026-11-06 — Open questions settled with the user; implementation split out to 005; acceptance
  criteria written.
- 2026-11-06 — Approved.
