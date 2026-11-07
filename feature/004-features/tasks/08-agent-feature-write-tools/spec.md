---
feature: 004-features
task: 08-agent-feature-write-tools
layer: agent
status: approved
approved: 2026-11-06
branch: feat/004-features/08-agent-feature-write-tools
base: feat/004-features/07-agent-feature-read-tools
covers: [AC-11, AC-12, AC-13, AC-14, AC-16, AC-17, AC-19, AC-20]
---

# 08 — Feature write tools and instructions

## Goal

Give the agent the tools that change a feature: write and edit files in the feature folder,
commit and push them, set the feature's status, and add and update steps. Rewrite the "Feature
workflow" section of `src/instructions.md` so the agent runs a project's own skills for the
feature named in the chat's context line.

## Contract

From task-breakdown.md, unchanged.

"Routes" (the rows this task calls):

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET /api/projects/{id}/features/{featureId}` | — | `200` Feature | — |
| `PUT /api/projects/{id}/features/{featureId}/status` | `{ "status": "breakdown" }` | `200` Feature | `409 invalid_status` (problem has `allowed`: the workflow's states) |
| `POST /api/projects/{id}/features/{featureId}/steps` | `{ "type": "spec", "title": "Spec: 01-api-x", "documentPath": "feature/004-features/tasks/01-api-x/spec.md" }` (`documentPath` optional) | `201` Step | `409 invalid_step_type` (`allowed`: step keys), `400 invalid_step` (blank or long title, path not under `folder`) |
| `PATCH /api/projects/{id}/features/{featureId}/steps/{stepId}` | any of `status`, `title`, `documentPath` | `200` Step | `404 step_not_found`, `409 invalid_status` (`allowed`: the type's states), `400 invalid_step` |

On every route: `404 project_not_found`, `404 feature_not_found` (no such feature, or it belongs
to another project), `400 invalid_request`. Every route needs `Authorization: Bearer <API_TOKEN>`.

From feature 003 (unchanged), the push route `feature_commit` calls:

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `POST /api/projects/{id}/checkout/worktrees/{worktreeId}/push` | `{ "force": false }` | `200` `{ "branch": "…", "head": "a1b2c3d" }` | `404 worktree_not_found`, `409 push_default_branch`, `409 push_rejected` (non-fast-forward, or the lease failed), `409 worktree_missing`, `409 project_archived`, `502`/`503`/`504` as fetch |

"Feature chat": the API prefixes each user message with one context line:

```
[Feature context: projectId=12 featureId=21 code=004-features. Use the feature_* tools with these ids.]

Start the requirements
```

"Agent tools (agent → api, agent → `/repos`)" — the rows this task builds:

| Tool | Input | Does |
| ---- | ----- | ---- |
| `feature_write_file` | ids, `path`, `content` | Writes a file; `path` must be under `folder`. |
| `feature_edit_file` | ids, `path`, `oldText`, `newText` | Replaces one exact, unique occurrence; `path` under `folder`. |
| `feature_commit` | ids, `message` | `git add -- <folder>`, `git commit` (author from `GIT_USER_NAME`/`GIT_USER_EMAIL`, as 003), then `POST …/checkout/worktrees/{worktree.id}/push` (`force: false`). Returns the head SHA, or "nothing to commit". |
| `feature_set_status` | ids, `status` | `PUT …/status`. |
| `feature_add_step` | ids, `type`, `title`, `documentPath?` | `POST …/steps`. |
| `feature_update_step` | ids, `stepId`, `status?`, `title?`, `documentPath?` | `PATCH …/steps/{stepId}`. |

- Path rules (code, not prompt): relative paths only; the resolved real path (symlinks followed)
  must be inside the worktree for reads and inside `<worktree>/<folder>/` for writes; `.git`
  is never readable or writable. Violations fail with a plain message and touch nothing.
- `worktree` `null` or `missing` → every file and commit tool fails with "The worktree for
  <branch> is missing. Check it out again on the project page." (`feature_get` and the status
  and step tools still work).
- API errors surface as the problem's `detail`.
- `instructions.md` gains a "Feature workflow" section: the context line, running a step by
  reading its skill with `feature_read_skill` and following it, `rules.md` and templates read
  from the worktree, Claude Code wording mapped (AskUserQuestion → ask in the chat, a slash
  command → name the next step), approvals recorded only after an explicit yes in the chat,
  commit after each document change, and record steps and statuses with the tools.

## Base files

- `agent/src/agent.ts` and `agent/src/instructions.md` are **`shared`**: edits stay additive and
  project-specific, and the user confirms each one.
- `instructions.md` already has a "# Feature workflow" section, added by this project, that
  describes the eve subagents (`feature-requirements`, `feature-dispatch`, …). They aren't
  registered in `agentOptions`, so that text tells the model to delegate to agents it doesn't
  have. Step 6 replaces that section rather than adding a second one; the user confirms the
  replacement when the edit is made. The "Identity", "Guidelines" and "Projects" sections stay
  as they are.
- `agent/README.md` is **`locked`**; `.claude/base-overrides` lists it. Step 7 is skipped if the
  entry is gone.
- `src/chat.ts`, `src/server.ts`, `agent/tsconfig.json`, `agent/AGENTS.md` stay untouched.

## Files

- `agent/src/api.ts` — change: `apiPut<T>` and `apiPatch<T>`, sharing `request()`.
- `agent/src/feature-git.ts` — new: `gitIn(worktree, args)` (local git, author env, no
  credentials) and `commitFolder(worktree, folder, message)`; a per-worktree queue.
- `agent/src/tools/feature-write-file.ts` — new: `feature_write_file`.
- `agent/src/tools/feature-edit-file.ts` — new: `feature_edit_file`.
- `agent/src/tools/feature-commit.ts` — new: `feature_commit`.
- `agent/src/tools/feature-set-status.ts` — new: `feature_set_status`.
- `agent/src/tools/feature-add-step.ts` — new: `feature_add_step`.
- `agent/src/tools/feature-update-step.ts` — new: `feature_update_step`.
- `agent/src/tools/feature-write.test.ts` — new: file and commit tool tests (temp git repo +
  bare remote; stubbed push route).
- `agent/src/tools/feature-status.test.ts` — new: status and step tool tests (stubbed `fetch`).
- `agent/src/agent.ts` — change (shared): register the six tools.
- `agent/src/instructions.md` — change (shared): replace "# Feature workflow".
- `agent/README.md` — change (locked, override present): "Current tools".

## Steps

1. **`src/api.ts`.** Add
   `apiPut<T>(path, body, timeoutMs = 10_000)` and `apiPatch<T>(path, body, timeoutMs = 10_000)`,
   built exactly like `apiPost` (JSON body, `content-type`, the shared `request()`). Existing
   exports and tests unchanged.
2. **Local git (`src/feature-git.ts`).** Copy the `git()` pattern from
   `agent/agent/lib/repo.ts` (that file stays unwired, so don't import it):
   - `execFile` promisified, `maxBuffer` 64 MB, fixed env `PATH`, `HOME`,
     `GIT_TERMINAL_PROMPT=0`, `GIT_AUTHOR_NAME`/`GIT_COMMITTER_NAME` from `GIT_USER_NAME`
     (default `factory agent`), `GIT_AUTHOR_EMAIL`/`GIT_COMMITTER_EMAIL` from `GIT_USER_EMAIL`
     (default `factory-agent@users.noreply.github.com`). No `GIT_CONFIG_*`, no token; nothing
     written to the repo's config.
   - Errors: stderr trimmed, or "git <cmd> exited with code <n>", with `exitCode`.
   - `serialFor(worktree, work)`: a `Map<string, Promise<unknown>>` queue so two tool calls on
     one worktree never run git at once (different worktrees run in parallel).
   - `commitFolder(worktree, folder, message): Promise<{ committed: false } | { committed: true; sha: string }>`:
     `git add -A -- <folder>`; `git diff --cached --quiet -- <folder>` exit 0 →
     `{ committed: false }`; else `git commit -m <message> -- <folder>` (pathspec, so files
     staged elsewhere by hand are never included), then `git rev-parse --short HEAD`.
3. **File tools** (both: `featureIds` + their fields; `loadFeature`, `requireWorktree`,
   `resolveWrite(root, feature.folder, path)` from 07; errors → `toolError`).
   - `feature_write_file` — `path: z.string().min(1)`, `content: z.string().max(500_000)`.
     `mkdir(dirname, { recursive: true })` (the parent is inside the folder by the rule), then
     `writeFile` UTF-8. Return `toolJson({ path, bytes })`. Description says to commit after
     the document change with `feature_commit`.
   - `feature_edit_file` — `path`, `oldText: z.string().min(1)`, `newText: z.string()`. Read
     the file (missing → error "<path> doesn't exist; use feature_write_file."), count
     occurrences of `oldText` with `indexOf` in a loop: 0 → "oldText isn't in <path>.", >1 →
     "oldText appears <n> times in <path>; include more surrounding text." Else replace the one
     occurrence (`slice`, not `replace`, so `$` in `newText` is literal) and write.
4. **`feature_commit`** — `featureIds` + `message: z.string().min(1).max(200)`.
   `loadFeature`, `requireWorktree`; then inside `serialFor(root, …)`:
   1. `commitFolder(root, feature.folder, message)`.
   2. Unpushed? `git rev-list --count @{u}..HEAD` > 0, or no upstream yet
      (`git rev-parse --abbrev-ref @{u}` fails). Nothing committed and nothing unpushed →
      `toolText("Nothing to commit in <folder>.")`, no push.
   3. Otherwise `apiPost<{ branch: string; head: string }>(`/api/projects/${projectId}/checkout/worktrees/${feature.worktree.id}/push`, { force: false }, 150_000)`
      and return `toolJson({ committed, sha, pushed: head, branch })`.
   4. A push failure returns `isError` with "Committed <sha> locally, but the push failed:
      <detail>" (or "The push failed: <detail>" when nothing new was committed). A later
      `feature_commit` pushes the waiting commit through step 2. Never force.
5. **Status and step tools** (no worktree needed; errors → `toolError`, whose text is the
   problem's `detail`; for `409` with `allowed` the API's detail already names the states):
   - `feature_set_status` — `featureIds` + `status: z.string().min(1).max(40)`.
     `apiPut<Feature>(`…/features/${featureId}/status`, { status })`; returns `toolJson({ status, updatedAt })`.
   - `feature_add_step` — `featureIds` + `type: z.string().min(1)`, `title: z.string().min(1).max(200)`,
     `documentPath: z.string().max(300).optional()`. `apiPost` to `…/steps`, omitting
     `documentPath` when undefined; returns the Step.
   - `feature_update_step` — `featureIds` + `stepId: z.number().int().positive()`, optional
     `status`, `title`, `documentPath`. At least one is required (else error without calling
     the API). `apiPatch` with only the given fields; returns the Step.
   - Descriptions say the allowed values come from `feature_get` (`workflow.featureStates`,
     a step's `states`), and that approval states are set only after the user's explicit yes.
6. **`src/instructions.md` (shared).** Replace the "# Feature workflow" section with one that
   says, in the file's plain bullet style:
   - A message starting with `[Feature context: projectId=… featureId=… code=…]` is about that
     feature; use those ids with every `feature_*` tool and don't ask the user for them. Without
     that line, the feature tools aren't for this conversation (the Assistant page has no
     feature).
   - Start with `feature_get`: the status, the steps so far and the workflow (feature states,
     step types, each type's states).
   - To run a step, read its skill with `feature_read_skill` and follow it. Read
     `feature/rules.md`, `feature/_templates/*` and any file the skill names with
     `feature_read_file`; the project's own files win over anything you remember about
     another project.
   - Skills are written for Claude Code. Where a skill says to use AskUserQuestion, ask the
     questions in the chat (a short numbered list, a recommended option first) and wait. Where
     it names a slash command such as `/feature-breakdown`, say which step comes next and offer
     to start it. Ignore instructions about tools you don't have (Bash, git commands, sessions,
     PRs); in this feature you only write documents in the feature folder.
   - Write only in the feature folder (`feature_write_file`, `feature_edit_file`) and commit
     after each document change (`feature_commit`, a short message like
     `<code>: requirements draft`).
   - When you start a step, add it with `feature_add_step` (its type, a title, the document
     path); keep its status current with `feature_update_step`; move the feature with
     `feature_set_status` when the workflow reaches a new state.
   - Approval: never set an approved state, on a document's frontmatter or a step, until the
     user has said yes to that document in this chat. Asking "should I approve?" doesn't count;
     their explicit approval does.
   - If a tool says the worktree is missing, tell the user to check the branch out again on the
     project page; don't try to work around it.
   - Implementation (dispatching tasks, code, PRs) isn't available yet; say so if asked.
7. **README (locked, override present).** "Current tools": one bullet per new tool; one line that
   the write tools change only the feature folder and push through the API (no git credentials
   in the agent).
8. **Register (`src/agent.ts`, shared).** Import and append the six tools after the 07 tools.
   Write tools get no `readOnlyHint`.

## Tests

`node:test`, stubbed `fetch` as in `src/tools/projects.test.ts`. The git tests use a real temp
repo: `git init --bare remote.git`, `git clone remote.git wt`, one commit with
`feature/004-features/requirements.md`, `.claude/skills/x/SKILL.md` and `README.md`, pushed;
the stubbed `GET …/features/21` returns `worktree.path` = that clone. The stubbed push route runs
`git -C wt push origin HEAD:<branch>` itself, so the test sees a real remote head.

- `tools/feature-write.test.ts`
  - `feature_write_file` writes `feature/004-features/tasks/01-x/spec.md` (parents created)
    (AC-16).
  - `feature_write_file` refuses `README.md`, `feature/004-other/x.md`, `../x`, `/tmp/x`,
    `.git/hooks/pre-commit`, and a path through a symlink inside the folder pointing to the
    worktree root; after each, `git status --porcelain` is unchanged and no file exists at the
    target (AC-19).
  - `feature_edit_file` replaces a unique occurrence (with `$&` in `newText` kept literally);
    refuses zero and two occurrences; refuses a path outside the folder (AC-19).
  - `feature_commit` after a write: one new commit touching only the folder, author and
    committer = `GIT_USER_NAME`/`GIT_USER_EMAIL`, the push route called once with
    `{ force: false }`, and the bare remote's branch at the returned SHA (AC-17).
  - `feature_commit` with a stray change to `README.md` and a change in the folder: the commit
    contains only the folder file; `README.md` stays modified and uncommitted (AC-19).
  - `feature_commit` with nothing to change: "Nothing to commit", no push call.
  - `feature_commit` when the push route answers `409 push_rejected`: `isError`, message has
    the local SHA and the problem's `detail`; a retry with no new changes pushes the unpushed
    commit.
  - `git -C wt config --get user.name` is empty after the tests.
  - Every file and commit tool with `worktree: null` returns the missing message (AC-21).
- `tools/feature-status.test.ts`
  - `feature_set_status` sends `PUT /api/projects/12/features/21/status` `{ status }` with the
    bearer token; a `409 invalid_status` problem surfaces its `detail` as `isError` (AC-11).
  - `feature_add_step` sends `POST …/steps` with `type`, `title`, `documentPath`; without
    `documentPath` the body omits it; `409 invalid_step_type` surfaces (AC-12, AC-14).
  - `feature_update_step` sends `PATCH …/steps/5` with only the given fields; no fields → error
    and no request; `409 invalid_status` surfaces (AC-13).
- `src/api.test.ts` is not needed: `apiPut`/`apiPatch` are covered through the tools above.

Manual, with 06 running (curl on `POST /api/projects/{id}/features/{fid}/messages`, or the chat
once task 10 is in): ask the agent to start the requirements step. It reads the skill, asks its
questions in the chat, edits `requirements.md`, commits, and the branch on GitHub has the change
(AC-16, AC-17). The feature page (09) or `GET …/features/{fid}` shows a `requirements` step.
Say "looks fine" without approving: no approved state is set. Say "Approved": the document's
frontmatter, the step's status and, per the workflow, the feature's status change (AC-20).

## Checks

- `docker exec factory-agent npm run typecheck`
- `docker exec factory-agent npm test`
- `docker exec factory-agent git --version` (the tests need git in the container; it has it for
  003's tools).

## Out of scope

- Dispatching tasks, code writes outside the feature folder, task branches, PRs (feature 005).
- Deleting files (no tool in the contract; a document is emptied or rewritten instead).
- Force pushes, rebases, or any git network command in the agent.
- Code-level enforcement of approvals (the contract leaves it to instructions; the API checks
  only that states exist).
- The Assistant page's chat and the eve-based `agent/agent/` code.
