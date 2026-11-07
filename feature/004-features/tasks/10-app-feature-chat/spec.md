---
feature: 004-features
task: 10-app-feature-chat
layer: app
status: approved
approved: 2026-11-06
branch: feat/004-features/10-app-feature-chat
base: feat/004-features/09-app-feature-page
covers: [AC-16, AC-20, AC-22, AC-23, AC-28, AC-29]
---

# 10 — Feature chat

## Goal

The feature page gets a chat with the agent, bound to that feature through the API's broker. It
loads the feature's history, sends a message and shows the reply as it streams, with tool
activity and a Stop button. After each turn it reloads the feature so new statuses and steps
show. The chat is a project-owned component that looks like the locked Assistant chat, but
neither imports nor changes it.

## Contract

Consumes, from task-breakdown.md:

- Every route needs `Authorization: Bearer <API_TOKEN>` (`401` otherwise) and returns errors as
  `ProblemDetail` (`application/problem+json`) with a `code` property the app and agent switch on
  and a `detail` they can show as is. On every route: `404 project_not_found`,
  `404 feature_not_found` (no such feature, or it belongs to another project),
  `400 invalid_request` (non-numeric id, unreadable body, missing required field).

- **Routes**

  | Method & path | Request | Response | Errors (`code`) |
  | ------------- | ------- | -------- | ---------------- |
  | `GET /api/projects/{id}/features/{featureId}` | — | `200` Feature | — |
  | `GET /api/projects/{id}/features/{featureId}/messages` | — | `200` `[{ "id", "role": "user"\|"assistant", "text", "createdAt" }]`, oldest first | — |
  | `POST /api/projects/{id}/features/{featureId}/messages` | `{ "message": "Start the requirements" }` | `200` `application/x-ndjson`: the agent's events (below) | before the stream: `400 invalid_request` (blank), `409 chat_busy`, `502 agent_unavailable` |
  | `POST /api/projects/{id}/features/{featureId}/messages/cancel` | — | `200` `{ "cancelled": true }` | — |

- **Feature chat (app ↔ api ↔ agent)**

  The API brokers the chat so no locked agent or app file changes:

  - `POST …/messages`: the API stores the user's message, then posts to the agent's
    `POST /agent/v1/chat` (agent/README.md, "HTTP API") with `sessionId` = the feature's
    `agent_session_id` (omitted the first time) and the message prefixed with one context line:

    ```
    [Feature context: projectId=12 featureId=21 code=004-features. Use the feature_* tools with these ids.]

    Start the requirements
    ```

  - It relays each NDJSON line to the app as it arrives: `session`, `text` (`delta`), `tool`
    (`id`, `name`, `status`), and exactly one of `done` (`text`), `error` (`message`),
    `cancelled`. On `session` it stores `agent_session_id`; on `done` it stores the assistant's
    `text`. An agent `404 session_not_found` starts a new session once and retries.
  - Agent `409 session_busy` → `409 chat_busy`; agent unreachable or `5xx` → `502
    agent_unavailable`; silence longer than `chat-idle-timeout` → an `error` line, then the
    stream ends.
  - `…/messages/cancel` calls the agent's `POST /agent/v1/session/<id>/cancel`.

- **app → api:** `lib/features.ts` with the types and calls through `lib/api.ts`. The chat reads
  the NDJSON stream with `fetch` (same-origin `/api`, bearer token from `lib/password.ts`), since
  `api()` reads whole bodies. Errors show the problem's `detail`; `5xx` codes get "Try again".

## Files

- `app/app/lib/features.ts` — change: `FeatureChatEvent` type and `sendFeatureMessage()`, the
  streaming call.
- `app/app/components/features/use-feature-chat.ts` — new: `useFeatureChat(projectId,
  featureId, onTurnEnd)`, history, send, stream state, cancel.
- `app/app/components/features/feature-chat.tsx` — new: `FeatureChat`, the card.
- `app/app/routes/feature-detail.tsx` — change: render `<FeatureChat>` below the Steps card and
  pass `onTurnEnd={() => revalidator.revalidate()}`.

`components/agent/agent-chat.tsx` is **locked** and speaks eve; this task copies its look (card,
bubbles, tool badges, input group) into project code and imports nothing from it.

## Steps

1. **`sendFeatureMessage(projectId, featureId, message, { signal, onEvent })`** in
   `lib/features.ts`:
   - `fetch(\`${API_BASE_URL}/api/projects/${projectId}/features/${featureId}/messages\`)` with
     `POST`, JSON body `{ message }`, `Accept: application/x-ndjson`, `Content-Type:
     application/json`, `Authorization: Bearer ${getPassword()}` when set, and `signal`. Use the
     same `import.meta.env.VITE_API_BASE_URL ?? ""` base as `lib/api.ts` (`lib/api.ts` is locked
     and doesn't export it; declare the same constant).
   - Not `ok` → read the body as JSON when the content type has `json`, and throw `new
     ApiError(status, body)` so `featureError` maps it like every other call.
   - `ok` → read `response.body` with `getReader()` and a `TextDecoder` (`stream: true`), split
     on `\n`, keep the trailing partial line, `JSON.parse` each non-empty line, and call
     `onEvent` with it. Ignore unknown `type`s and unparseable lines.
   - Resolves with the final event (`done`, `error` or `cancelled`), or `{ type: "error",
     message: "The connection closed before the reply finished." }` if the body ends without
     one.
   - `FeatureChatEvent` =
     `{ type: "session"; sessionId } | { type: "text"; delta } | { type: "tool"; id; name;
     status: "running" | "completed" | "failed" } | { type: "done"; text } | { type: "error";
     message } | { type: "cancelled" }`.
2. **`useFeatureChat(projectId, featureId, onTurnEnd)`** returns `{ history, turn, error,
   send, stop, reloadHistory }`:
   - `history`: `{ status: "loading" } | { status: "loaded"; messages: ChatMessage[] } |
     { status: "error"; error: FeatureError }`. Loaded with `listFeatureMessages` on mount and
     when `featureId` changes (stale answers ignored with a request id ref, as in
     `use-checkout.ts`). `ChatMessage` = `{ key: string; role: "user" | "assistant"; text:
     string; tools: { id; name; status }[]; failed?: boolean }`; stored messages have no tools.
   - `turn`: `null` or `{ abort: AbortController; thinking: boolean }`. Only one turn at a
     time; `send` does nothing while one is open.
   - `send(text)`: trims; appends the user message and an empty assistant message; starts
     `sendFeatureMessage` with a new `AbortController`. `text` events append the `delta` to the
     assistant message and clear `thinking`; `tool` events add or update an entry by `id`;
     `done` replaces the assistant text with the event's `text`; `error` sets `error` to the
     event's message (the assistant message keeps what streamed); `cancelled` marks nothing and
     leaves what streamed. A thrown `ApiError` before the stream (`409 chat_busy`, `502
     agent_unavailable`, `400`) marks the user message `failed`, removes the empty assistant
     message, puts the text back in the draft (returned so the component can restore it), and
     sets `error` from `featureError`. On every end, clear `turn` and call `onTurnEnd()` so the
     page reloads the feature's status and steps (AC-20).
   - `stop()`: `cancelFeatureChat(projectId, featureId)` (errors ignored). Don't abort the fetch
     first: the API's stream ends with `cancelled`. Abort the fetch only on unmount (the effect
     cleanup), which also makes the API cancel the turn when the connection closes.
   - `chat_busy` error text: the API's `detail`; the hook adds nothing.
3. **`FeatureChat({ projectId, featureId })`**, laid out like the Assistant chat:
   - `Card` with `CardHeader`: `CardTitle` with `BotIcon` and "Agent", and a `StatusBadge`:
     `positive` "Ready" when no turn, `warning` "Working" during one. No "New chat": a feature has
     one conversation (AC-23).
   - `CardContent` `flex min-h-0 flex-col gap-4`, with a message area of `max-h-[32rem]
     min-h-48 overflow-y-auto` (`role="log"`, `aria-live="polite"`, `aria-busy` during a turn):
     - history `loading`: two `Skeleton` bubbles;
     - history `error`: `Alert variant="destructive"` "Couldn't load the conversation" with the
       message and "Try again" (`reloadHistory`) when retryable (AC-28);
     - no messages: `Empty` with `BotIcon`, "Start working on this feature", description "Ask
       the agent to start the next step, for example \"Start the requirements\"."
     - messages: bubbles as in the Assistant chat (`max-w-[85%]`, user right with
       `bg-primary text-primary-foreground`, agent left with `bg-muted`, `whitespace-pre-wrap
       break-words`); each tool as an outline `Badge` with `WrenchIcon`, the tool name without
       the `mcp__app__` prefix, and "running", "done" or "failed"; "Not sent" in
       `text-destructive` under a failed user message; "Thinking…" with a `Spinner` while
       `thinking`. Scroll the end into view as text streams.
   - `error` (turn or send) below the log: `Alert variant="destructive"` with `CircleAlertIcon`,
     "The agent couldn't answer" and the message. A `401` reads "Your session has expired. Sign
     in again." with a "Sign in" button that calls `logout()` and navigates to `/login`, as the
     Assistant chat does.
   - `CardFooter`: a `form` with `InputGroup`, `InputGroupTextarea` (`aria-label="Message the
     agent"`, placeholder "Message the agent…", `max-h-40`; Enter sends, Shift+Enter adds a line,
     not while composing), Stop (`SquareIcon`, outline, `size="sm"`) during a turn, and Send
     (`ArrowUpIcon`, `size="icon-sm"`, `aria-label="Send"`, disabled when the draft is blank,
     the history isn't loaded, or a turn is open).
   - Mount only after hydration (the history read uses the stored password): render the card
     from the page's component, which already runs after `clientLoader`; no SSR fetch.
4. **Page wiring:** in `feature-detail.tsx`, `<FeatureChat projectId={feature.projectId}
   featureId={feature.id} onTurnEnd={() => revalidator.revalidate()} />` after the Steps card.
   Key it by `feature.id` so moving between features starts fresh (AC-23).
5. **Layout** (AC-29): the chat is full width inside the page column; bubbles wrap; long paths
   in replies break (`break-words`); no fixed widths.
6. Prettier on the touched files.

## Streaming through the dev proxy

`vite.config.ts` (locked) proxies `/api` with Vite's default http-proxy, which streams
responses as they arrive and doesn't buffer. The API sends no compression (no
`server.compression` in `application.properties`), so the NDJSON lines reach the browser
one at a time, as the `/eve` comment in `vite.config.ts` describes for the agent. If the API
ever turns on compression for `application/x-ndjson`, the stream would arrive in one piece at
the end; task 06 must not compress it. A proxy timeout doesn't apply (http-proxy sets none by
default); the API's own idle timeout ends a stuck turn.

## Tests

The app has no unit test setup. By hand, against the running stack (tasks 02–09 in the stack,
`ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN` set for `factory-agent`), on a feature created
through the API:

- Send "Which feature is this?": the reply streams in pieces and names the feature's code and
  name without being told (AC-22).
- Send "Start the requirements": tool badges show `feature_read_skill`, then reads and writes;
  the agent asks its questions in the chat; `feature/<code>/requirements.md` in the worktree
  changes; after the turn, the Steps card shows the step the agent added (AC-16).
- Without saying "approve", ask the agent to mark it approved: it doesn't, and the step's status
  stays. Say "Approved": after the turn, the page shows the step's new status without a reload
  (AC-20).
- Reload the page: the earlier messages are listed oldest first and the next message continues
  the conversation (the agent remembers what was said). Another feature's page shows its own,
  separate conversation (AC-23).
- Start a long turn and click Stop: the stream ends, the badge goes back to "Ready", and what
  had streamed stays.
- Send while another tab has a turn open on the same feature: "Not sent" and the busy message.
- With the agent stopped (`docker stop factory-agent`), sending shows the agent-unavailable
  message and the rest of the page works; with the API stopped, the history shows "Couldn't load
  the conversation" with Try again (AC-28).
- At 375 px, the chat and long replies don't cause horizontal scroll (AC-29).

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/lib/features.ts app/components/features/use-feature-chat.ts app/components/features/feature-chat.tsx app/routes/feature-detail.tsx`

## Out of scope

- The Features card and "New feature" form (task 11).
- Changing the locked Assistant chat, `lib/api.ts` or `vite.config.ts`.
- Markdown rendering of replies (plain text, like the Assistant chat).
- Approval buttons in the UI: approvals are said in the chat (requirements).
