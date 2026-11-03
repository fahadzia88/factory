# agent

The project's AI agent, built with [eve](https://eve.dev) (Vercel's open-source agent framework, `eve@1.0.0-rc.2`).
eve treats an agent as a directory of files under `agent/` and compiles it into an HTTP service
with durable sessions, streaming, tools, skills and schedules.

In this repo the agent runs in Docker next to `app/` and `api/`. It calls Anthropic directly; there is
no Vercel account and no AI Gateway involved.

```
browser ──► app (Vite :3000) ── /eve/* proxy ──► agent (eve :3000 in Docker, :3001 on host)
                                                     ▲            │
api (Spring :8080) ── AgentService ──────────────────┘            └──► Anthropic API
```

- **app → agent:** the chat UI uses `useEveAgent` from `eve/react`. The Vite dev server proxies
  same-origin `/eve/*` requests to `AGENT_URL`, the same way it proxies `/api/*` to the API.
- **api → agent:** `AgentService` calls the agent's session HTTP API, for server-side work such
  as scheduled jobs or summaries stored in the database.
- **auth:** every caller sends `Authorization: Bearer <API_TOKEN>`, the same static token the API
  checks. The app already has it as the stored password, so one credential covers both services.

## Layout

```
agent/
├── agent/
│   ├── agent.ts            # model + runtime config (defineAgent)
│   ├── instructions.md     # always-on system prompt — edit this to change behaviour
│   └── channels/eve.ts     # HTTP channel /eve/v1/* and who may call it (API_TOKEN bearer)
├── package.json            # scripts: dev, build, start, typecheck, eval
├── AGENTS.md / CLAUDE.md   # guidance for coding agents working in this folder
└── .eve/                   # build output + local session data (gitignored)
```

Add capabilities as more files under `agent/`: `tools/`, `skills/`, `schedules/`, `subagents/`,
`connections/`. The full docs for the installed version are in `node_modules/eve/docs/`; start with
its `README.md`.

## Configuration

| Variable              | Where                | Purpose                                                                 |
| --------------------- | -------------------- | ----------------------------------------------------------------------- |
| `ANTHROPIC_API_KEY`   | root `.env`          | **Required.** Read by `anthropic()` in `agent/agent.ts`.                |
| `API_TOKEN`           | root `.env` (optional) | Bearer token callers must send. Defaults to `123456`, same as the API. |
| `API_URL`             | docker-compose       | Where tools can reach the API (`http://api:8080`).                      |
| `CHOKIDAR_USEPOLLING` | docker-compose       | Hot reload through the macOS bind mount.                                |

The model is set in `agent/agent.ts`:

```ts
model: anthropic("claude-sonnet-5-5"),
```

Pass any Anthropic model ID. To route through Vercel AI Gateway instead, use a string such as
`"anthropic/claude-sonnet-5.5"` and set `AI_GATEWAY_API_KEY`.

## Running

### With Docker (normal)

Add your key to the root `.env`:

```
ANTHROPIC_API_KEY=sk-ant-...
```

Then, from the repo root:

```bash
docker-compose up agent        # or `docker-compose up` for the whole stack
curl http://localhost:3001/eve/v1/health
# {"ok":true,"status":"ready","workflowId":"..."}
```

The container runs `npm install && npm run dev -- --no-ui --host 0.0.0.0 --port 3000`. Edits under
`agent/` reload automatically.

### On the host (terminal chat UI)

Requires Node 24+.

```bash
cd agent
npm install
npm run dev      # opens eve's terminal UI on http://127.0.0.1:2000
```

`localDev()` in `channels/eve.ts` lets local terminal sessions in without a token. It never admits
callers from other containers or machines.

## HTTP API

All routes live under `/eve/v1` and need `Authorization: Bearer $API_TOKEN`.

| Method & path                         | Purpose                                                                 |
| ------------------------------------- | ----------------------------------------------------------------------- |
| `GET  /eve/v1/health`                 | Liveness: `{ ok, status: "ready" }`                                     |
| `POST /eve/v1/session`                | Create a session. Body `{"message": "..."}` also starts the first turn. Returns `202 {sessionId, deliveryId}`. |
| `POST /eve/v1/session/:id`            | Send a follow-up `{"message": "..."}`. Returns `{deliveryId}`. Retry `409 session_not_ready` with backoff. |
| `GET  /eve/v1/session/:id/stream`     | NDJSON event stream. `?startIndex=N` resumes from line N.               |
| `POST /eve/v1/session/:id/cancel`     | Cancel the running turn                                                 |
| `POST /eve/v1/session/:id/end`        | End the session                                                         |

```bash
T=123456
SID=$(curl -s -X POST localhost:3001/eve/v1/session \
  -H "authorization: Bearer $T" -H 'content-type: application/json' \
  -d '{"message":"Say hello in five words."}' | sed -E 's/.*"sessionId":"([^"]+)".*/\1/')
curl -N -H "authorization: Bearer $T" localhost:3001/eve/v1/session/$SID/stream
```

How to read the stream (stream version 27):

- Each line is `{"at", "facts": [...]}` (state changes), `{"progress": {...}}` (token deltas), or a
  transport record such as `{"$eve":"heartbeat"}`, `{"$eve":"stream.lease-ended"}` (reconnect with
  `startIndex`) or `{"$eve":"stream.ended"}`.
- The answer is in the `content.completed` facts where `data.kind == "text"` and
  `data.phase == "reply"`. Live text arrives as `progress.type == "content.delta"`.
- A message is finished when `delivery.settled` arrives with its `deliveryId`. The turn's result
  is in `turn.settled.data.outcome`: `completed`, `failed` or `cancelled`.

TypeScript callers should use the SDK (`Client` from `eve/client`, `useEveAgent` from `eve/react`)
instead of parsing the stream by hand.

## Integrations

### app

The app owns the chat UI:

- `vite.config.ts` proxies `/eve` to `process.env.AGENT_URL ?? "http://localhost:3001"`.
- The chat component calls `useEveAgent({ auth: { bearer: () => getPassword() } })` with the
  default `baseUrl` `""`, so requests stay same-origin.
- The app needs the `eve` package as a dependency, for `eve/react`.

### api

The API's `AgentService` reads `AGENT_URL` (`app.agent.url`) and `API_TOKEN`. It creates a session
or sends a message, then reads the NDJSON stream until `delivery.settled` and returns the reply text.

### Letting the agent call the API

Tools are TypeScript files under `agent/tools/` that use `defineTool` from `eve/tools` with a Zod
input schema. A tool reaches the API at `process.env.API_URL` and sends
`Authorization: Bearer ${process.env.API_TOKEN}`. Keep tools narrow and read-only unless you add an
approval step; see `node_modules/eve/docs/tools/`.

## Troubleshooting

- **"AI Gateway authentication failed … set `AI_GATEWAY_API_KEY`"** with the `anthropic()` model:
  eve labels *any* provider `authentication_error` as a gateway failure. The request did go to
  `api.anthropic.com`; your `ANTHROPIC_API_KEY` is missing or invalid.
- **`LoadCompiledManifestError` right after start:** a request arrived before the first compile
  finished. It's harmless once `[DEV] server listening` appears.
- **401 from the agent:** the bearer token doesn't match `API_TOKEN`, or the variable isn't set in
  the container.
- **Dormant session after a restart:** `eve dev` doesn't resume runs from earlier invocations. Start
  a new session, or add `--resume` to the dev command.
- **Session data:** dev sessions are stored under `.eve/` in this folder. Delete it to start clean.

## Production

`npm run build` writes the server to `.output/`. Run it with
`PORT=3000 npm start -- --host 0.0.0.0`, and keep `.eve/.workflow-data` on persistent storage
(or configure a Postgres workflow world). `localDev()` is ignored outside `eve dev`, so the
`API_TOKEN` bearer is the only way in. See `node_modules/eve/docs/guides/deployment/`.
