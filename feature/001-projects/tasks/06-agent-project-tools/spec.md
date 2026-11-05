---
feature: 001-projects
task: 06-agent-project-tools
layer: agent
status: approved
approved: 2026-11-04
branch: feat/001-projects/06-agent-project-tools
base: feat/001-projects/05-infra-api-github-token
covers: [AC-26, AC-27, AC-28]
---

# 06 — Assistant can read projects

## Goal

Give the root agent two read-only tools, `list_projects` and `get_project`, that call the API,
plus instructions saying it can read projects but never change them. Add evals for AC-26 to
AC-28.

## Contract

From task-breakdown.md, "agent → api (root agent tools)":

- `list_projects({ q?, status?, includeArchived = true, sort?, direction? })` calls
  `GET {API_URL}/api/projects` with `Authorization: Bearer ${API_TOKEN}`. It fetches pages of 100
  until done and returns the Project JSON as is.
- `get_project({ id })` calls `GET {API_URL}/api/projects/{id}` and returns the Project, or
  `{ found: false }` on `404`.
- There are no write tools. The root instructions say the Assistant can't change projects and
  point to the Projects page.

Project JSON (consumed):
`{ id, name, repositoryUrl, description|null, status, archived, repositoryVerified, createdAt,
updatedAt }`, where `status` is one of `planning`, `active`, `maintenance`, `retired`.

## Files

- `agent/agent/lib/api.ts` — new: `apiGet(path, params, signal)`. Uses `process.env.API_URL`
  (default `http://localhost:8080`) and `Authorization: Bearer ${process.env.API_TOKEN}`, and
  throws a plain-language `Error` on non-2xx (status, plus the `ProblemDetail.detail` when
  present).
- `agent/agent/tools/list_projects.ts` — new.
- `agent/agent/tools/get_project.ts` — new.
- `agent/agent/instructions.md` — change: add a "Projects" section.
- `agent/agent/channels/eve.ts` — change: grant `allowToolStubs` to `localDev()` callers only, as
  in eve's docs (`guides/auth-and-route-protection.md`, "Tool replacement permission"), so local
  evals can stub the tools.
- `agent/evals/evals.config.ts` — new: `defineEvalConfig({})`.
- `agent/evals/projects/list-includes-archived.eval.ts` — new.
- `agent/evals/projects/answers-about-one-project.eval.ts` — new.
- `agent/evals/projects/refuses-to-change.eval.ts` — new.
- `agent/README.md` — change: mention the tools under "Letting the agent call the API", and how
  to run `npm run eval`.

## Steps

1. Read `node_modules/eve/docs/tools/index.mdx` and `docs/evals/tool-stubs.mdx` first (as
   `agent/AGENTS.md` asks).
2. **`lib/api.ts`**: `fetch` with `signal`, a 10 s timeout (`AbortSignal.any([signal,
   AbortSignal.timeout(10_000)])`), and query params via `URLSearchParams` (skipping `undefined`).
   Return parsed JSON. Never include the token in errors.
3. **`list_projects`** (`defineTool`):
   - Description: "List the factory's software projects (archived ones included unless asked
     otherwise), with name, repository, status, description, archived and verification state."
   - `inputSchema`: zod `{ q?: string, status?: enum, includeArchived: boolean default true,
     sort?: enum("updatedAt","name","status"), direction?: enum("asc","desc") }`.
   - `execute`: loop `page = 1..` with `size: 100` until `page >= totalPages`; stop at 20 pages
     (2,000 projects) and report `truncated: true`. Return `{ totalItems, projects: items }`.
   - `label.start`: "List projects".
4. **`get_project`**: input `{ id: number int positive }`. Call the API and return the Project; on
   404 return `{ found: false, id }`. Label: "Look up project <id>".
5. Neither tool sets `approval` (read-only, `never()` by default).
6. **`instructions.md`**, a new "# Projects" section:
   - The app tracks the factory's software projects. Use `list_projects` and `get_project` to
     answer questions about them, and include archived projects (say they're archived).
   - When asked about a project by name, find it with `list_projects` (`q`), then answer from its
     stored fields only: repository, status, description, archived, and whether the repository is
     verified.
   - You can't create, edit, archive or restore projects. Say so and point the user to the
     Projects page in the app.
7. **`channels/eve.ts`**: wrap `localDev()` as in the docs example, so its caller gets
   `allowToolStubs: true`. Keep the `apiToken()` authenticator unchanged (no stubs).
8. **Evals** (with `t.session({ stubs })`, so no API is needed):
   - `list-includes-archived`: stub `list_projects` returning three projects, one with
     `archived: true` ("Legacy Portal"). Ask "Which projects do we have?". Assert
     `calledTool("list_projects")`, and that the reply includes all three names and marks Legacy
     Portal as archived (`t.judge` or `messageIncludes`) (AC-26).
   - `answers-about-one-project`: stub `list_projects` (matching any input) returning "Factory"
     with `repositoryUrl` `https://github.com/acme/factory`, status `maintenance` and
     `repositoryVerified: false`. Ask "What's the repo and status of Factory?". Assert the reply
     includes the URL and "maintenance", and mentions that the repository isn't verified (AC-27).
   - `refuses-to-change`: stub `list_projects`. Ask "Archive the Factory project." Assert the
     reply says it can't and mentions the Projects page (judge), and `notCalledTool` for anything
     but `list_projects`/`get_project`. The agent has no write tool, so also assert no `bash` or
     `web_fetch` calls (AC-28).

## Tests

- The three evals above (AC-26, AC-27, AC-28). They need `ANTHROPIC_API_KEY`, because they run
  the real model.

## Checks

- In the agent container (or `docker run --rm -v "$PWD/agent:/workspace" -w /workspace node:24 …`):
  `npm run typecheck` and `npx eve build`.
- `npm run eval -- projects` passes locally.
- With the API running and a project created, ask in the Assistant page "what projects are
  there?" and check that the reply matches.

## Out of scope

- Write tools of any kind.
- Showing projects inside the chat UI (cards, links).
