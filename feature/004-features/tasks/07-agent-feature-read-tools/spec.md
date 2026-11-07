---
feature: 004-features
task: 07-agent-feature-read-tools
layer: agent
status: approved
approved: 2026-11-06
branch: feat/004-features/07-agent-feature-read-tools
base: feat/004-features/06-api-feature-chat
covers: [AC-16, AC-18, AC-21]
---

# 07 — Feature read tools

## Goal

Give the agent four read-only `mcp__app` tools that load a feature from the API and read its
worktree on the shared `/repos` volume: `feature_get`, `feature_read_skill`, `feature_list_files`
and `feature_read_file`. A shared module, `src/feature-api.ts`, loads the feature and enforces
the path rules in code, so task 08's write tools reuse the same checks.

## Contract

From task-breakdown.md, unchanged.

"Workflow file (project repo ↔ api ↔ agent)":

`feature/workflow.json` in the project's repository, read from the main worktree
(`/repos/<project-id>/feature/workflow.json`) on create:

```json
{
  "featureStates": ["requirements", "breakdown", "specs", "implementation", "done", "abandoned"],
  "steps": [
    { "key": "requirements", "title": "Requirements", "skill": "feature-requirements", "states": ["draft", "approved"] },
    { "key": "breakdown", "title": "Task breakdown", "skill": "feature-breakdown", "states": ["draft", "approved"] },
    { "key": "spec", "title": "Task spec", "skill": "feature-task-spec", "states": ["draft", "approved"] }
  ]
}
```

- The validated workflow is stored on the feature as JSON and is what statuses are checked
  against. The API returns it as `workflow` in the Feature JSON.

"Feature (JSON)":

```json
{
  "id": 21,
  "projectId": 12,
  "number": 4,
  "code": "004-features",
  "name": "Features",
  "slug": "features",
  "status": "requirements",
  "branch": "feat/004-features/00-docs",
  "folder": "feature/004-features",
  "worktree": { "id": 9, "path": "/repos/12.worktrees/feat__004-features__00-docs", "missing": false },
  "workflow": { "featureStates": ["…"], "steps": [{ "key": "…", "title": "…", "skill": "…", "states": ["…"] }] },
  "steps": [
    {
      "id": 5,
      "position": 1,
      "type": "requirements",
      "typeTitle": "Requirements",
      "title": "Requirements",
      "documentPath": "feature/004-features/requirements.md",
      "status": "draft",
      "states": ["draft", "approved"],
      "createdAt": "2026-11-06T10:00:00Z",
      "updatedAt": "2026-11-06T10:00:00Z"
    }
  ],
  "createdAt": "2026-11-06T10:00:00Z",
  "updatedAt": "2026-11-06T10:00:00Z"
}
```

- `worktree` is `null` when no worktree with `branch` exists; the app shows that as missing too.
  `missing` is 003's flag (folder not on disk).

"Routes" (the row this task calls):

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET /api/projects/{id}/features/{featureId}` | — | `200` Feature | — |

On every route: `404 project_not_found`, `404 feature_not_found` (no such feature, or it belongs
to another project), `400 invalid_request`. Every route needs `Authorization: Bearer <API_TOKEN>`.

"Agent tools (agent → api, agent → `/repos`)" — the rows this task builds:

New `mcp__app` tools in `agent/src/tools/`. Every tool takes `projectId` and `featureId` and first
loads the feature with `GET /api/projects/{id}/features/{featureId}`; paths come only from that
response.

| Tool | Input | Does |
| ---- | ----- | ---- |
| `feature_get` | ids | The Feature JSON (status, steps, workflow, branch, folder, worktree). |
| `feature_read_skill` | ids, `step` (a step key) | `<worktree>/.claude/skills/<skill>/SKILL.md` for that step's `skill`. Unknown key → error listing the keys. |
| `feature_list_files` | ids, `dir?` | Files under `dir` (default the root) in the worktree, `.git` excluded, at most 500. |
| `feature_read_file` | ids, `path` | A text file in the worktree, at most 200 KB. |

- Path rules (code, not prompt): relative paths only; the resolved real path (symlinks followed)
  must be inside the worktree for reads and inside `<worktree>/<folder>/` for writes; `.git`
  is never readable or writable. Violations fail with a plain message and touch nothing.
- `worktree` `null` or `missing` → every file and commit tool fails with "The worktree for
  <branch> is missing. Check it out again on the project page." (`feature_get` and the status
  and step tools still work).
- API errors surface as the problem's `detail`.

## Base files

- `agent/src/agent.ts` is **`shared`**: adding the four tools to the `tools` array is an additive,
  project-specific edit; the user confirms it.
- `agent/README.md` is **`locked`**. Its "Current tools" list would ideally name the new tools.
  `.claude/base-overrides` currently lists `agent/README.md`, so the edit is allowed; check the
  file before step 7, and if the entry is gone, skip step 7 and say so in the PR. Never edit
  `.claude/base-overrides` or get around the hook.
- `agent/tsconfig.json`, `agent/AGENTS.md`, `src/chat.ts`, `src/server.ts` are locked; don't
  touch them.
- `src/api.ts`, `src/feature-api.ts` and `src/tools/*` are project code.

## Files

- `agent/src/feature-api.ts` — new: the `Feature` types, `loadFeature()`, `requireWorktree()`,
  `resolveRead()`, `resolveWrite()` (used by 08), `toolError()`/`toolJson()` result helpers.
- `agent/src/tools/feature-get.ts` — new: `feature_get`.
- `agent/src/tools/feature-read-skill.ts` — new: `feature_read_skill`.
- `agent/src/tools/feature-list-files.ts` — new: `feature_list_files`.
- `agent/src/tools/feature-read-file.ts` — new: `feature_read_file`.
- `agent/src/tools/feature-ids.ts` — new: the shared Zod shape `featureIds`
  (`{ projectId, featureId }`).
- `agent/src/feature-api.test.ts` — new: path-rule tests on a temp directory.
- `agent/src/tools/feature-read.test.ts` — new: tool tests with stubbed `fetch` and a temp
  worktree.
- `agent/src/agent.ts` — change (shared): import and register the four tools.
- `agent/README.md` — change (locked, override present): "Current tools" gains the four tools.

## Steps

1. **Types and loading (`src/feature-api.ts`).**
   - Export types mirroring the contract: `WorkflowStep { key; title; skill; states: string[] }`,
     `Workflow { featureStates: string[]; steps: WorkflowStep[] }`, `FeatureStep` (the step
     JSON), `FeatureWorktree { id: number; path: string; missing: boolean }`, and `Feature` with
     `worktree: FeatureWorktree | null`. Plain `type` aliases only (erasable TypeScript).
   - `loadFeature(projectId: number, featureId: number): Promise<Feature>` =
     `apiGet<Feature>(`/api/projects/${projectId}/features/${featureId}`)`. Errors propagate as
     `ApiError` (its message is already the problem's `detail`).
   - `requireWorktree(feature): string` returns `feature.worktree.path` or throws
     `new Error(`The worktree for ${feature.branch} is missing. Check it out again on the project page.`)`
     when `worktree` is `null` or `missing` is `true`, or when `existsSync(path.join(p, ".git"))`
     is false (the folder vanished since the API looked).
2. **Path rules (`src/feature-api.ts`).** Both resolvers take the worktree root and a
   caller-supplied relative path and return an absolute path, or throw a plain `Error` naming
   the path and the rule.
   - Reject before touching the disk: empty, absolute (`path.isAbsolute`), containing a NUL
     byte, or any segment equal to `.git` (after `path.normalize`, split on `/`).
   - `root = await realpath(worktree)`. `target = path.resolve(root, rel)`. Find the deepest
     existing ancestor of `target` (walk up with `lstat` until one exists), `realpath` it, and
     rejoin the not-yet-existing tail. The result must equal `base` or start with `base + sep`,
     else throw "<rel> is outside the feature's worktree." (reads) or "<rel> is outside
     <folder>/; only the feature folder can be written." (writes). When `target` itself exists,
     use `realpath(target)` directly so a symlink pointing out is caught.
   - `resolveRead(worktree, rel)`: `base = root`.
   - `resolveWrite(worktree, folder, rel)`: `base = path.join(root, folder)`; `rel` is relative
     to the worktree root (e.g. `feature/004-features/requirements.md`), as the agent sees it
     in `feature_list_files` and `documentPath`.
   - Also reject a result whose path relative to `root` has a `.git` segment (a symlink into
     `.git`).
3. **Result helpers.** `toolJson(value)` → `{ content: [{ type: "text", text: JSON.stringify(value) }] }`;
   `toolText(text)`; `toolError(prefix, error)` → `{ content: [{ type: "text", text: `${prefix}: ${message}` }], isError: true }`,
   as `get-project.ts` does inline today.
4. **`featureIds`** (`src/tools/feature-ids.ts`):
   `{ projectId: z.number().int().positive().describe("From the [Feature context] line"), featureId: z.number().int().positive().describe("From the [Feature context] line") }`.
5. **Tools**, each with `tool(name, description, schema, handler, { annotations: { readOnlyHint: true } })`
   and handlers wrapped in `try`/`catch` → `toolError("Couldn't …", error)`:
   - `feature_get` — `featureIds`. Returns `toolJson(await loadFeature(...))`. A `404` returns
     the error, not `{ found: false }`: the ids come from the context line, so a miss is a real
     problem. Description: "Get the feature: status, steps, workflow (its states and step
     types), branch, folder and worktree."
   - `feature_read_skill` — `featureIds` + `step: z.string().min(1).describe("A step key from the feature's workflow")`.
     Find `workflow.steps` by `key`; none → error "Unknown step <step>. Steps: <keys joined
     with ", ">." `requireWorktree`, then `resolveRead(root, `.claude/skills/${skill}/SKILL.md`)`
     and `readFile` as UTF-8. A missing file → "<path> isn't in the feature's worktree." Return
     the file text with a first line `# Skill <skill> (from <branch>)` so the model knows it's
     the project's own.
   - `feature_list_files` — `featureIds` + `dir: z.string().optional()`. `requireWorktree`,
     `resolveRead(root, dir ?? ".")` (allow `.` as the root). Walk with `readdir(…, { withFileTypes: true })`
     breadth-first, skipping any entry named `.git` and not following symlinked directories.
     Return `toolJson({ files, truncated })` with paths relative to the worktree root, sorted,
     at most 500 (`truncated: true` when more exist). A `dir` that is a file → error.
   - `feature_read_file` — `featureIds` + `path: z.string().min(1)`. `requireWorktree`,
     `resolveRead`, `stat`: not a file → error; over 200 × 1024 bytes → error "<path> is
     <n> KB; only files up to 200 KB can be read."; else the UTF-8 text.
6. **Register (`src/agent.ts`, shared).** Import the four tools and append them to `tools`
   after `getProject`. Nothing else in `agentOptions` changes (`allowedTools: ["mcp__app"]`
   already covers them).
7. **README (locked, override present).** In "Current tools", add one bullet per tool in the
   style of the existing two, and one sentence after the list: "The `feature_*` tools work on
   one feature of a project: paths come from the API, and reads stay inside the feature's
   worktree on `/repos`." Skip if the override entry is gone (see Base files).

## Tests

`node:test`, in the style of `src/tools/projects.test.ts` (stubbed `fetch`, `tool.handler(input, undefined)`).
Each test makes a temp worktree with `mkdtemp` under `os.tmpdir()`: a `.git` file, `.claude/skills/feature-requirements/SKILL.md`,
`feature/004-features/requirements.md`, and an `outside/` sibling directory.

- `feature-api.test.ts`
  - `resolveRead` accepts `feature/004-features/requirements.md` and a not-yet-existing path in
    the worktree; refuses `../outside/x`, `/etc/passwd`, `.git/config`, `a/.git/b`, and a
    symlink in the worktree pointing at `outside/` (AC-19 groundwork for 08; the read side
    here).
  - `resolveWrite` accepts `feature/004-features/new/spec.md`; refuses `feature/004-other/x.md`,
    `README.md`, and a symlink inside the folder that points out of it.
  - `requireWorktree` throws the exact missing message for `worktree: null`, `missing: true`,
    and a path without `.git` (AC-21).
- `tools/feature-read.test.ts`
  - `feature_get` calls `GET /api/projects/12/features/21` with `Bearer test-token` and returns
    the Feature JSON; a `404` problem returns `isError` with the problem's `detail`.
  - `feature_read_skill` returns the temp worktree's `SKILL.md` for step `requirements`, read
    from `worktree.path` in the stubbed response, not from any factory path (AC-16, AC-18).
  - `feature_read_skill` with an unknown step lists the keys; with `worktree: null` returns the
    missing message (AC-21).
  - `feature_list_files` lists the files, never anything under `.git`, and caps at 500 with
    `truncated: true` (create 501 files).
  - `feature_read_file` reads `feature/004-features/requirements.md`; refuses `../outside/x`
    and a 201 KB file.

Manual, once 06 and this task run: in a feature's chat (or with curl on
`POST /api/projects/{id}/features/{fid}/messages`), ask the agent to read the requirements skill;
the tool status shows `mcp__app__feature_read_skill` and the reply quotes the project's skill
(AC-16). With a test project whose `SKILL.md` differs from the factory's, the reply follows that
project's text (AC-18). Remove the feature's worktree on the project page and ask again: the
agent says the worktree is missing (AC-21).

## Checks

- `docker exec factory-agent npm run typecheck`
- `docker exec factory-agent npm test`

## Out of scope

- Write, edit and commit tools, status and step tools, and the `instructions.md` section
  (task 08).
- Searching file contents (no `feature_search`; not in the contract).
- Any API change; if the feature route misbehaves, report it against tasks 03–06.
- The eve-based `agent/agent/` workflow code; it stays unwired and untouched.
