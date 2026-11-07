---
feature: 004-features
task: 06-api-feature-chat
layer: api
status: approved
approved: 2026-11-06
branch: feat/004-features/06-api-feature-chat
base: feat/004-features/05-api-feature-steps
covers: [AC-22, AC-23, AC-27]
---

# 06 — Feature chat relay

## Goal

The API brokers each feature's chat with the agent: it stores the conversation, sends each message
to the agent's `POST /agent/v1/chat` with the feature's session id and a context line, relays the
agent's NDJSON stream to the app as it arrives, and can cancel a running turn. No locked agent or
app file changes. AC-22 and AC-23 are covered on the API side; the app shows them in 10.

## Contract

Provides, copied from task-breakdown.md:

### Routes (this task's rows)

| Method & path | Request | Response | Errors (`code`) |
| ------------- | ------- | -------- | ---------------- |
| `GET /api/projects/{id}/features/{featureId}/messages` | — | `200` `[{ "id", "role": "user"\|"assistant", "text", "createdAt" }]`, oldest first | — |
| `POST /api/projects/{id}/features/{featureId}/messages` | `{ "message": "Start the requirements" }` | `200` `application/x-ndjson`: the agent's events (below) | before the stream: `400 invalid_request` (blank), `409 chat_busy`, `502 agent_unavailable` |
| `POST /api/projects/{id}/features/{featureId}/messages/cancel` | — | `200` `{ "cancelled": true }` | — |

On every route: `404 project_not_found`, `404 feature_not_found` (no such feature, or it belongs
to another project), `400 invalid_request` (non-numeric id, unreadable body, missing required
field).

### Feature chat (app ↔ api ↔ agent)

The API brokers the chat so no locked agent or app file changes:

- `POST …/messages`: the API stores the user's message, then posts to the agent's
  `POST /agent/v1/chat` (agent/README.md, "HTTP API") with `sessionId` = the feature's
  `agent_session_id` (omitted the first time) and the message prefixed with one context line:

  ```
  [Feature context: projectId=12 featureId=21 code=004-features. Use the feature_* tools with these ids.]

  Start the requirements
  ```

- It relays each NDJSON line to the app as it arrives: `session`, `text` (`delta`), `tool` (`id`,
  `name`, `status`), and exactly one of `done` (`text`), `error` (`message`), `cancelled`. On
  `session` it stores `agent_session_id`; on `done` it stores the assistant's `text`. An agent
  `404 session_not_found` starts a new session once and retries.
- Agent `409 session_busy` → `409 chat_busy`; agent unreachable or `5xx` → `502
  agent_unavailable`; silence longer than `chat-idle-timeout` → an `error` line, then the stream
  ends.
- `…/messages/cancel` calls the agent's `POST /agent/v1/session/<id>/cancel`.
- The API calls the agent with its own `RestClient` bean (`featureAgentRestClient`): the same base
  URL and bearer token as the locked `agentRestClient`, but the idle timeout above, since
  `agentRestClient` gives up after 30 s of silence.

### api config (this task's row)

| Property | Env | Default | For |
| -------- | --- | ------- | --- |
| `app.feature.chat-idle-timeout` | `FEATURE_CHAT_IDLE_TIMEOUT` | `5m` | Longest silence on the agent's stream (a long tool call) before the turn fails. |

### Consumed: the agent's HTTP API

From `agent/README.md`, "HTTP API": `POST /agent/v1/chat` with `{"message", "sessionId"?}` and
`Authorization: Bearer <API_TOKEN>`. Before a stream it answers JSON errors
`{"ok": false, "code", "error"}`: `400 invalid_request`, `401 unauthorized`, `404
session_not_found`, `409 session_busy`. Otherwise `200` NDJSON, one event per line, ending with
exactly one of `done`, `error`, `cancelled`; unknown types are to be ignored. Closing the
connection cancels the turn. `POST /agent/v1/session/:id/cancel` answers `{ ok, cancelled }`.

**Not used:** the locked `agent.service.AgentService` and its `agentRestClient` still speak the
old eve protocol (`/eve/v1/session…`), and the client's 30 s read timeout would cut a long turn.
This task neither calls nor changes them; the skeleton fix is reported separately.

## Files

Main code under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `config/FeatureConfig.kt` — new: `@Bean fun featureAgentRestClient(...)`.
- `service/FeatureChat.kt` — new: `@Service` with `messages`, `send`, `cancel`.
- `service/AgentChatStream.kt` — new: reads the agent's NDJSON response line by line and tracks
  `sessionId`, `done` text and the terminal event.
- `dto/FeatureDtos.kt` — change: `SendMessageRequest(val message: String? = null)`,
  `FeatureMessageResponse(id, role, text, createdAt)` (`role` lowercased), `CancelResponse(cancelled)`.
- `controller/FeatureChatController.kt` — new:
  `@RequestMapping("/api/projects/{id}/features/{featureId}/messages")`. In the same package as
  `FeatureController`, so `FeatureExceptionHandler` covers it.
- `controller/FeatureExceptionHandler.kt` — change: `chat_busy`, `agent_unavailable`, blank
  message.
- `exception/FeatureExceptions.kt` — change: `ChatBusyException`, `AgentUnavailableException`
  (feature's own, distinct from the agent package's), `BlankMessageException`.

Tests under `api/src/test/kotlin/com/toptier/custom/feature/`: `FeatureChatTests.kt`,
`FeatureChatControllerTests.kt` (new).

## Steps

1. **`FeatureConfig.featureAgentRestClient`** (`RestClient.Builder`, `AgentProperties`,
   `SecurityProperties`, `FeatureProperties`): `baseUrl(agentProperties.url)`, `Authorization:
   Bearer ${securityProperties.apiToken}`, `SimpleClientHttpRequestFactory` with connect timeout
   5 s and read timeout `featureProperties.chatIdleTimeout` (the read timeout applies to each read,
   so it bounds silence, not the whole turn). Inject it with
   `@Qualifier("featureAgentRestClient")`; other `RestClient` beans already exist, so never rely
   on type alone.
2. **`FeatureChat`** injects `FeatureService`, `FeatureRepository`, `FeatureMessageRepository`,
   the client, `JsonMapper`, `Clock`.
   - `messages(projectId, featureId)`: `featureService.load(...)`, then
     `findByFeatureIdOrderByCreatedAtAscIdAsc` (AC-23).
   - A `ConcurrentHashMap.newKeySet<Long>()` of features with a turn open: a second `send` while
     one runs → `ChatBusyException` ("The agent is still answering the previous message. Wait for
     it or stop it.") without calling the agent.
   - `send(projectId, featureId, raw: String?, out: OutputStream)`:
     1. `feature = load(...)`; `text = raw?.trim()`, blank → `BlankMessageException` (`400
        invalid_request`, "message is required").
     2. Claim the feature in the busy set (released in `finally`).
     3. `open(feature, text)` → an `AgentChatStream` (step 3). Errors here are thrown before
        anything is written, so they become ProblemDetail.
     4. Save the user message (`USER`, `text` without the context line, `createdAt = now`) (AC-23).
     5. Relay: for each line from the stream, write `line + "\n"` to `out` and `flush()`. All lines
        are relayed unchanged, unknown types included. On `session` with a new id, set
        `feature.agentSessionId` and save. On `done`, save an `ASSISTANT` message with its `text`.
     6. A `SocketTimeoutException` while reading → write
        `{"type":"error","sessionId":"<id>","message":"The agent didn't send anything for <describe(idle)>, so the turn was stopped."}`
        and end. Any other `IOException` from the agent → an `error` line "The connection to the
        agent was lost." An `IOException` writing to `out` (the app hung up) → close the agent
        response (that cancels the turn on the agent) and return.
     7. A stream that ends without `done`, `error` or `cancelled` → write an `error` line "The
        agent stopped without answering."
   - `open(feature, text)`:
     - `body = mapOf("message" to contextLine(feature) + "\n\n" + text)` plus `"sessionId" to
       feature.agentSessionId` when set; `contextLine = "[Feature context: projectId=${feature.projectId} featureId=${feature.id} code=${code}. Use the feature_* tools with these ids.]"` (AC-22).
     - `client.post().uri("/agent/v1/chat").contentType(APPLICATION_JSON).body(body).exchange({ _, response -> response }, false)`
       so the response stays open for reading.
     - `200` → `AgentChatStream(response, jsonMapper)`.
     - `404` with `code == "session_not_found"` and a session id was sent → clear
       `feature.agentSessionId`, save, and retry once without it.
     - `409` (`session_busy`) → `ChatBusyException`.
     - `401`/`403` → `AgentUnavailableException("The agent rejected the API token.")`; other
       statuses → `AgentUnavailableException("The agent answered <status>.")`; a
       `ResourceAccessException` → `AgentUnavailableException("The agent can't be reached. Try again.")`.
       Close the response on every non-`200` path.
   - `cancel(projectId, featureId)`: load; no session id → `false`; else
     `POST /agent/v1/session/{id}/cancel` and return its `cancelled` (default `false`); agent
     unreachable or non-2xx → `AgentUnavailableException`.
3. **`AgentChatStream`** (`AutoCloseable`): wraps `response.body.bufferedReader(UTF_8)`;
   `lines(): Sequence<String>` skips blank lines; `parse(line)` with `jsonMapper.readTree` and
   `type`, `sessionId`, `text`; a line that isn't JSON is relayed but otherwise ignored.
   `terminal` is set by `done`, `error` or `cancelled`.
4. **`FeatureChatController`**:
   - `GET ""` → `List<FeatureMessageResponse>`.
   - `POST ""` takes `@RequestBody SendMessageRequest` and `HttpServletResponse`. It writes
     synchronously on the request thread (no `StreamingResponseBody`, so no MVC async timeout
     applies): the service is called with a lazily-started output — headers
     (`Content-Type: application/x-ndjson;charset=UTF-8`, `Cache-Control: no-cache`,
     `X-Accel-Buffering: no`, status `200`) are set only when the first line is written, so an
     error thrown from `open` still reaches `FeatureExceptionHandler` as ProblemDetail.
   - `POST "/cancel"` → `CancelResponse`.
   - **No async timeout.** Because the turn is written synchronously and never returns
     `StreamingResponseBody`, `Callable` or `DeferredResult`, `spring.mvc.async.request-timeout`
     doesn't apply; don't switch to an async return type. A turn is bounded only by the agent's
     idle timeout (`chat-idle-timeout`) and the connection.
   - **No compression, flush per line.** `server.compression` isn't enabled in
     `application.properties` today; leave it off (if it's ever enabled, `application/x-ndjson`
     must not be in `server.compression.mime-types`). Never set `Content-Encoding`; call
     `flush()` on the servlet output after every line so Vite's `/api` proxy passes each line
     on as it arrives.
5. **Handler additions:** `ChatBusyException` → `409 chat_busy`; `AgentUnavailableException` →
   `502 agent_unavailable` (logged at info); `BlankMessageException` → `400 invalid_request`.

## Tests

`FeatureChatTests` (`@DataJpaTest`; a `@TestConfiguration` provides `featureAgentRestClient` built
from a `RestClient.Builder` bound with `MockRestServiceServer.bindTo(builder)`; feature rows
inserted directly; output to a `ByteArrayOutputStream`). Agent fixtures are NDJSON shaped like
`agent/README.md`'s example:

- first message: the request has no `sessionId` and its `message` starts with
  `[Feature context: projectId=<p> featureId=<f> code=001-x. Use the feature_* tools with these ids.]`
  followed by a blank line and the text (AC-22); every line is relayed in order; the feature now
  has the `session` id; `messages` returns the user text (without the context line) and the
  `done` text, oldest first (AC-23);
- second message: the request carries the stored `sessionId` (AC-23);
- agent `404 {"code":"session_not_found"}` then `200`: the second request has no `sessionId`, and
  the new id is stored;
- agent `409 session_busy` → `ChatBusyException`, nothing written, the user message not stored;
  agent `500` and a connection refused (`withException(ConnectException())`) →
  `AgentUnavailableException`;
- a stream whose body throws `SocketTimeoutException` after the `session` line → the output ends
  with one `error` line naming the timeout; no assistant message stored;
- a stream that ends after `text` lines with no terminal event → an `error` line;
- an `error` event is relayed and no assistant message is stored; an unknown event type is relayed
  unchanged;
- a second `send` while the first is blocked (the first's body stream waits on a latch) →
  `ChatBusyException`;
- `cancel` with no session → `false` and no request; with one → posts to
  `/agent/v1/session/<id>/cancel` and returns `true`.

`FeatureChatControllerTests` (`@WebMvcTest(FeatureChatController::class)`, importing
`SecurityConfig` and `FeatureExceptionHandler`, `@MockitoBean FeatureChat`):

- all three routes answer `401` without a token (AC-27);
- `GET` answers `200` with `role` `"user"`/`"assistant"`;
- `POST` with a stubbed `send` that writes two lines → `200`, content type `application/x-ndjson`,
  body exactly those lines, no `Content-Encoding` header, and the request is not async-started
  (`mvcResult.request.isAsyncStarted == false`);
- blank `message` → `400 invalid_request`; `ChatBusyException` → `409 chat_busy`;
  `AgentUnavailableException` → `502 agent_unavailable`; `feature_not_found` → `404`;
- `POST /cancel` → `200 {"cancelled": true}`.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.
- With the stack up (`docker-compose up -d mysql agent`, then `bootRun` in `factory-api`):
  `curl -N -X POST -H 'authorization: Bearer 123456' -H 'content-type: application/json'
  -d '{"message":"Which feature is this?"}' localhost:8080/api/projects/<p>/features/<f>/messages`
  streams `session`, `text`… and `done`; `GET …/messages` shows both messages; a second message
  continues the same session. Through Vite (`curl -N localhost:3000/api/…/messages`, same body)
  the `text` lines arrive one by one, not all at once at the end, and a turn longer than 30 s
  (ask for something that reads several files) isn't cut off.

## Out of scope

- The agent's `feature_*` tools (07, 08): until they exist the agent can only read the context
  line.
- Storing tool events or partial replies in the history; only user messages and `done` text are
  kept.
- Changing `AgentService`, `agentRestClient` or `vite.config.ts` (locked; the eve mismatch goes to
  the skeleton).
