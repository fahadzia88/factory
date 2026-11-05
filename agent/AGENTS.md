# agent

Rules for working in this folder. `api/` and `app/` have their own. `README.md` describes the
service, its HTTP API and how to run it.

The agent is a small Node HTTP service (`src/server.ts`) that runs each chat turn through `query()`
from the [Claude Agent SDK](https://docs.claude.com/en/docs/agent-sdk/typescript)
(`@anthropic-ai/claude-agent-sdk`). There is no framework: Node runs the `.ts` files directly.

## Where things go

- **Behaviour, tone, guidelines:** `src/instructions.md`, the system prompt. A content-only change
  needs nothing else.
- **Model, effort, tools, subagents, other SDK options:** `agentOptions` in `src/agent.ts`.
  Preserve the model unless the user asks to change it.
- **Tools:** one file per tool under `src/tools/`, built with `tool()` from the SDK and a Zod input
  schema, then added to the `tools` array in `src/agent.ts`. See README.md, "Tools".
- **HTTP routes, auth, stream events:** `src/server.ts`, `src/auth.ts`, `src/chat.ts`. These are
  the contract with `app/` and `api/`; change them only for a change every caller should get, and
  update README.md, "HTTP API" in the same change.

## Rules

- **Stay isolated.** `chat.ts` turns off built-in tools (`tools: []`), filesystem settings
  (`settingSources: []`) and permission prompts (`permissionMode: "dontAsk"`). Don't turn them on:
  this agent serves end users, so Bash, file and web tools would run on their behalf. Give it
  narrow project tools instead.
- **Only `mcp__app` tools are pre-approved.** Anything else is denied without asking.
- **Tools that call the API** use `process.env.API_URL` and send
  `Authorization: Bearer ${process.env.API_TOKEN}`. Keep them read-only unless the user asks for a
  write and the API checks it.
- **Types come from the SDK.** Read `node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts` for an
  option or message type instead of guessing; it's large, so grep for the name.
- **Erasable TypeScript only** (no enums, namespaces or parameter properties), and relative imports
  end in `.ts`. `tsconfig.json` enforces both.

## Validate the change

`npm run typecheck`. For a behaviour change, run `npm run dev` and send a message with curl
(README.md, "HTTP API").
