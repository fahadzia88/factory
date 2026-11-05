# agent

The project's AI agent: a small Node HTTP service that runs each chat turn through the
[Claude Agent SDK](https://docs.claude.com/en/docs/agent-sdk/typescript)
(`@anthropic-ai/claude-agent-sdk`). The SDK runs the agent loop (model calls, tools, subagents,
context management) and stores each conversation as a session that later messages resume.

In this repo the agent runs in Docker next to `app/` and `api/` and calls Anthropic directly.

```
browser ──► app (Vite :3000) ── /agent/* proxy ──► agent (Node :3000 in Docker, :3001 on host)
                                                       ▲             │
api (Spring :8080) ── AgentService ────────────────────┘             └──► Anthropic API
```

- **app → agent:** the chat UI posts to same-origin `/agent/v1/chat` and reads the NDJSON reply
  stream. The Vite dev server proxies `/agent/*` to `AGENT_URL`, as it proxies `/api/*` to the API.
- **api → agent:** `AgentService` posts to the same endpoint for server-side work such as scheduled
  jobs or summaries stored in the database, and reads the stream until `done`.
- **auth:** every caller sends `Authorization: Bearer <API_TOKEN>`, the same static token the API
  checks. The app already has it as the stored password, so one credential covers both services.

## Layout

```
agent/
├── src/
│   ├── server.ts         # HTTP routes, session bookkeeping, NDJSON streaming
│   ├── auth.ts           # API_TOKEN bearer check
│   ├── chat.ts           # one turn: query() options and SDK message → stream event mapping
│   ├── agent.ts          # model, effort, tools: edit this to configure the agent
│   ├── instructions.md   # system prompt: edit this to change behaviour
│   └── tools/            # project tools (create it with the first one)
├── package.json          # scripts: dev, start, typecheck
├── AGENTS.md / CLAUDE.md # guidance for coding agents working in this folder
└── .data/                # sessions and the SDK's config dir (gitignored)
```

Node 24 runs the TypeScript files directly, so there is no build step.

## Configuration

| Variable            | Where                  | Purpose                                                                        |
| ------------------- | ---------------------- | ------------------------------------------------------------------------------ |
| `ANTHROPIC_API_KEY` | root `.env`            | **Required.** Used by the SDK for every model call.                            |
| `API_TOKEN`         | root `.env` (optional) | Bearer token callers must send. Defaults to `123456`, same as the API.         |
| `API_URL`           | docker-compose         | Where tools can reach the API (`http://api:8080`).                             |
| `PORT`, `HOST`      | docker-compose         | Listen address. Defaults to `127.0.0.1:3001` on the host.                      |
| `AGENT_DATA_DIR`    | optional               | Where sessions are stored. Defaults to `.data` in this folder.                 |

The model and the rest of the SDK options are in `src/agent.ts`:

```ts
model: "claude-sonnet-5-5",
effort: "medium",
```

`chat.ts` adds the options that keep the agent isolated: no built-in tools (`tools: []`), no
settings files (`settingSources: []`), no permission prompts (`permissionMode: "dontAsk"`). The
SDK's `CLAUDE_CONFIG_DIR` points at `.data/claude`, so the agent never reads your own Claude Code
login, settings or sessions.

## Running

### With Docker (normal)

Add your key to the root `.env`:

```
ANTHROPIC_API_KEY=sk-ant-...
```

Then, from the repo root:

```bash
docker-compose up agent        # or `docker-compose up` for the whole stack
curl http://localhost:3001/agent/v1/health
# {"ok":true,"status":"ready"}
```

The container runs `npm install && npm run dev`; nodemon restarts the server when a file under
`src/` changes (polling, through `CHOKIDAR_USEPOLLING`). Its `node_modules` is a Docker volume, because the SDK installs a native binary for
the platform it runs on.

### On the host

Requires Node 24+.

```bash
cd agent
npm install
npm run dev      # reads ../.env, listens on http://127.0.0.1:3001
```

## HTTP API

All routes live under `/agent/v1`. Everything except `health` needs
`Authorization: Bearer $API_TOKEN`. Errors are JSON: `{"ok": false, "code": "...", "error": "..."}`.

| Method & path                          | Purpose                                                                     |
| -------------------------------------- | --------------------------------------------------------------------------- |
| `GET  /agent/v1/health`                | Liveness: `{ ok, status: "ready" }`                                         |
| `POST /agent/v1/chat`                  | Send `{"message": "...", "sessionId"?: "..."}`. Streams the turn as NDJSON. |
| `POST /agent/v1/session/:id/cancel`    | Cancel the session's running turn: `{ ok, cancelled }`                      |

Leave out `sessionId` to start a conversation; pass the one from the first response to continue
it. `/chat` answers with an error instead of a stream when:

| Status | `code`              | Meaning                                                        |
| ------ | ------------------- | -------------------------------------------------------------- |
| 400    | `invalid_request`   | No `message`, or `sessionId` isn't a session id                |
| 401    | `unauthorized`      | Missing or wrong bearer token                                  |
| 404    | `session_not_found` | No stored session with that id                                 |
| 409    | `session_busy`      | The session is still answering an earlier message; retry later |

Otherwise the response is `200` with one JSON event per line:

| `type`      | Fields                                   | Meaning                                                    |
| ----------- | ---------------------------------------- | ---------------------------------------------------------- |
| `session`   | `sessionId`                              | Always first. Keep the id to continue the conversation.    |
| `text`      | `delta`                                  | Reply text as it streams.                                  |
| `tool`      | `id`, `name`, `status`                   | A tool call: `running`, then `completed` or `failed`.      |
| `done`      | `sessionId`, `text`                      | Last line on success. `text` is the full reply.            |
| `error`     | `sessionId`, `message`                   | Last line when the turn failed.                            |
| `cancelled` | `sessionId`                              | Last line when the turn was cancelled.                     |

Every stream ends with exactly one of `done`, `error` or `cancelled`. Ignore event types you don't
know. Closing the connection before the end cancels the turn.

```bash
curl -N localhost:3001/agent/v1/chat \
  -H 'authorization: Bearer 123456' -H 'content-type: application/json' \
  -d '{"message":"Say hello in five words."}'
# {"type":"session","sessionId":"8f2b138e-…"}
# {"type":"text","delta":"Hello"}
# …
# {"type":"done","sessionId":"8f2b138e-…","text":"Hello there, nice meeting you."}
```

## Tools

A tool is a file under `src/tools/` built with `tool()` from the SDK and a Zod input schema:

```ts
// src/tools/get-health.ts
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";

export const getHealth = tool(
  "get_health",
  "Reports whether the application's API is up.",
  { verbose: z.boolean().optional().describe("Include the full response body") },
  async ({ verbose }) => {
    const response = await fetch(`${process.env.API_URL}/actuator/health`, {
      headers: { authorization: `Bearer ${process.env.API_TOKEN}` },
    });
    const body = await response.text();
    return { content: [{ type: "text", text: verbose ? body : String(response.ok) }] };
  },
);
```

Add it to the `tools` array in `src/agent.ts`. The tools are served as the in-process MCP server
`app`, so the model sees `mcp__app__get_health`, and `allowedTools: ["mcp__app"]` lets them run
without a prompt. Keep tools narrow and read-only unless the API checks the write.

## Troubleshooting

- **`error` event "Not logged in · Please run /login":** `ANTHROPIC_API_KEY` is missing in the
  agent's environment. In Docker, check the root `.env` and recreate the container.
- **401 from the agent:** the bearer token doesn't match `API_TOKEN`, or the variable isn't set in
  the container.
- **`session_not_found` after a restart:** sessions live in `.data/`. Deleting it, or changing
  `AGENT_DATA_DIR`, forgets them.
- **No reload in Docker:** check that `CHOKIDAR_USEPOLLING` is still set on the agent service.

## Production

Run `npm ci --omit=dev && PORT=3000 HOST=0.0.0.0 npm start`, keep `AGENT_DATA_DIR` on persistent
storage, and set a real `API_TOKEN`. Sessions are files on one machine, so run a single instance,
or put sessions in a shared store (the SDK's `sessionStore` option).
