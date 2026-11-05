---
feature: 001-projects
task: 06-agent-project-tools
layer: agent
status: approved
approved: 2026-11-05
branch: feat/001-projects/06-agent-project-tools
base: feat/001-projects/05-infra-api-github-token
covers: [AC-26, AC-27, AC-28]
---

# 06 — Assistant can read projects

## Goal

Give the Assistant two read-only tools, `list_projects` and `get_project`, that call the API, plus
instructions saying it can read projects but never change them. The agent is the Node service on
the Claude Agent SDK (`agent/src/`), so the tools are SDK `tool()`s served as the in-process MCP
server `app`.

## Contract

From task-breakdown.md, "agent → api":

- `list_projects({ q?, status?, includeArchived = true, sort?, direction? })` calls
  `GET {API_URL}/api/projects` with `Authorization: Bearer ${API_TOKEN}`. It fetches pages of 100
  until done and returns the Project JSON as is.
- `get_project({ id })` calls `GET {API_URL}/api/projects/{id}` and returns the Project, or
  `{ found: false }` on `404`.
- There are no write tools. The instructions say the Assistant can't change projects and point to
  the Projects page.

The model sees the tools as `mcp__app__list_projects` and `mcp__app__get_project`, which
`allowedTools: ["mcp__app"]` already pre-approves.

Project JSON (consumed): `{ id, name, repositoryUrl, description|null, status, archived,
repositoryVerified, createdAt, updatedAt }`. `status` is one of `planning`, `active`,
`maintenance`, `retired`.

## Files

- `agent/src/api.ts` — new: `apiGet(path, params?)` for read-only API calls.
- `agent/src/tools/list-projects.ts` — new: `listProjects` tool.
- `agent/src/tools/get-project.ts` — new: `getProject` tool.
- `agent/src/tools/projects.test.ts` — new: `node:test` tests for both tools with a stubbed
  `fetch`.
- `agent/src/agent.ts` — change: add both tools to the `tools` array.
- `agent/src/instructions.md` — change: add a "Projects" section.
- `agent/package.json` — change: add `"test": "node --test src/**/*.test.ts"`.
- `agent/README.md` — change: under "Tools", list the two project tools and `npm test`.

## Steps

1. Read `agent/AGENTS.md` and the README's "Tools" section. For the `tool()` signature and its
   result type, grep `node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts` for `tool(` and
   `CallToolResult`. Use erasable TypeScript only, with relative imports ending in `.ts`.
2. **`src/api.ts`**:
   - `export class ApiError extends Error` with `status: number`.
   - `export async function apiGet<T>(path: string, params: Record<string, string | number |
     boolean | undefined> = {}): Promise<T>`:
     - base URL from `process.env.API_URL` (default `http://localhost:8080`);
     - query from `URLSearchParams`, skipping `undefined`;
     - headers `authorization: Bearer ${process.env.API_TOKEN}` and `accept: application/json`;
     - a 10 s timeout with `AbortSignal.timeout(10_000)`.
   - On a non-2xx response, throw `ApiError(status, message)`. The message is the
     `ProblemDetail.detail` when the body is JSON with one, else `API request failed (<status>)`.
     Never put the token in a message.
3. **`src/tools/list-projects.ts`**: `export const listProjects = tool("list_projects",
   description, shape, handler)`.
   - Description: "List the factory's software projects, archived ones included unless
     includeArchived is false. Returns name, repository URL, status, description, archived and
     repositoryVerified for each."
   - Shape (Zod):
     - `q`: `z.string().optional()`, with `describe("Part of a project name")`;
     - `status`: `z.enum(["planning", "active", "maintenance", "retired"]).optional()`;
     - `includeArchived`: `z.boolean().optional()`, treated as `true` when omitted;
     - `sort`: `z.enum(["updatedAt", "name", "status"]).optional()`;
     - `direction`: `z.enum(["asc", "desc"]).optional()`.
   - Extras: `{ annotations: { readOnlyHint: true } }`, as for `get_project`.
   - Handler: request `page = 1, 2, …` with `size: 100` until `page >= totalPages`, and stop
     after 20 pages. Return one text block with
     `JSON.stringify({ totalItems, truncated, projects })`, where `truncated` is true only when it
     stopped at 20 pages.
   - On `ApiError` or a network error, return `{ content: [{ type: "text", text: "Couldn't load
     projects: <message>" }], isError: true }` instead of throwing.
4. **`src/tools/get-project.ts`**: `export const getProject = tool("get_project", "Get one
   project by id.", { id: z.number().int().positive() }, handler)`.
   - Extras: `{ annotations: { readOnlyHint: true } }`.
   - Return the Project JSON as text.
   - On `ApiError` 404, return `JSON.stringify({ found: false, id })`, not as an error.
   - Other errors return `isError: true`, as in `list_projects`.
5. **`src/agent.ts`**: import both tools (`./tools/list-projects.ts`, `./tools/get-project.ts`)
   and set `tools = [listProjects, getProject]`. Change nothing else in `agentOptions`: the model,
   `allowedTools` and the isolation settings in `chat.ts` stay as they are.
6. **`src/instructions.md`**, a new "# Projects" section:
   - The app tracks the factory's software projects. Use `list_projects` and `get_project` to
     answer questions about them. Include archived projects, and say that they're archived.
   - When asked about a project by name, find it with `list_projects` (`q`), then answer only from
     its stored fields: repository, status, description, archived, and whether the repository is
     verified ("Repository not verified" means GitHub couldn't be reached when it was saved).
   - You can't create, edit, archive or restore projects. Say so and point the user to the
     Projects page in the app.
   - If a tool returns an error, say the projects couldn't be loaded right now; don't guess.
7. **`package.json`**: add the `test` script. Node 24 runs `.ts` test files directly, so there are
   no new dependencies.
8. **`README.md`**, "Tools": one line per tool, and "Run `npm test` for the tool tests."

## Tests

`src/tools/projects.test.ts` (`node:test` and `node:assert/strict`). Replace `globalThis.fetch`
with a stub that records requests and returns fixture responses, and restore it after each test.
Call each tool directly with `await listProjects.handler(input, undefined)`. `tool()` returns an
`SdkMcpToolDefinition`, whose `handler` property is the function you passed.

- `list_projects` with no input sends `includeArchived=true`, `page=1`, `size=100` and the bearer
  header. Over three pages (`totalPages: 3`), it returns all projects, archived ones included, and
  `truncated: false` (AC-26).
- `list_projects` with `q`, `status`, `sort` and `direction` passes them through as query
  parameters, and `includeArchived: false` sends `false` (AC-26, AC-27).
- `list_projects` stops after 20 pages with `truncated: true`.
- `list_projects` on a 502 returns `isError: true` with the API's detail, and doesn't throw.
- `get_project` returns the stored fields unchanged, including `repositoryVerified: false`
  (AC-27).
- `get_project` on 404 returns `{ found: false, id }` without `isError`.

Model behavior (AC-26 to AC-28) is checked by hand, because the agent has no eval framework. Use
the README's curl against `/agent/v1/chat` with the API running and three seeded projects, one of
them archived:

- "Which projects do we have?": the stream shows a `tool` event for
  `mcp__app__list_projects`, and the reply lists all three, marking the archived one (AC-26).
- "What's the repo and status of <name>?": the reply gives the stored URL and status, and
  mentions an unverified repository when that's the case (AC-27).
- "Archive <name>.": the reply says it can't and points to the Projects page. The stream has no
  tool event other than `mcp__app__list_projects` or `mcp__app__get_project`, and the project is
  unchanged in the API (AC-28).

## Checks

In the agent container (`docker exec factory-agent …`), or the throwaway
`docker run --rm -v "$PWD/agent:/workspace" -w /workspace node:24 …`:

- `npm run typecheck`
- `npm test`
- The three curl checks above, with `npm run dev` (or the running container).

## Out of scope

- Write tools of any kind.
- Showing projects as cards or links inside the chat UI.
- An eval framework for the agent.

## Changelog

- 2026-11-05 — Rewritten for the Claude Agent SDK agent (it moved from eve): SDK `tool()`s in
  `agent/src/tools/`, `node:test` tool tests, and manual chat checks instead of eve evals.
- 2026-11-05 — Approved again.
