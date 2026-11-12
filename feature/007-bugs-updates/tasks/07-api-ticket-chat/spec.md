---
feature: 007-bugs-updates
task: 07-api-ticket-chat
layer: api
status: draft
approved:
branch: feat/007-bugs-updates/07-api-ticket-chat
base: feat/007-bugs-updates/06-api-ticket-pull
covers: [AC-25, AC-26, AC-35]
---

# 07 — Ticket chat

## Goal

A chat per ticket with the agent: stored messages, a streamed turn, and cancel, relayed like the feature chat.

## Contract

From [task-breakdown.md](../../task-breakdown.md), unchanged: `GET …/tickets/{tid}/messages` → `[{ id, role, text, createdAt }]`; `POST …/tickets/{tid}/messages` `{ "message" }` → NDJSON stream; `POST …/tickets/{tid}/cancel` → `{ cancelled }`; errors `400 blank_message`, `409 workspace_missing`, `409 chat_busy`, `409 project_archived`, `502 agent_unavailable`. The agent receives `[Ticket context: projectId=<p> ticketId=<t> number=<n> type=<type>. Use the ticket_* tools with these ids.]` before the message. No question cards.

## Files

Under `api/src/main/kotlin/com/toptier/custom/ticket/`:

- `domain/TicketMessage.kt`, `TicketMessageRepository.kt` — new: table `ticket_message` (`id`, `ticket_id`, `role`, `text`, `created_at`); reuses `feature.domain.MessageRole`; `findByTicketIdOrderByCreatedAtAscIdAsc`.
- `domain/TicketRepository.kt` — change: `updateAgentSessionId(id, sessionId)` query.
- `service/TicketChat.kt` — new: history, send, cancel.
- `dto/TicketDtos.kt` — change: `TicketMessageResponse`, `SendTicketMessageRequest`, `TicketCancelResponse`.
- `controller/TicketChatController.kt` — new: the three routes; the same `NdjsonOutput` approach as `FeatureChatController` (write on the request thread, headers on first write).
- `controller/TicketExceptionHandler.kt` — change: map `BlankMessageException` (400 `blank_message`), `ChatBusyException` (409 `chat_busy`), `AgentUnavailableException` (502 `agent_unavailable`, logged at info) individually; map `WorkspaceMissingException` and archived as before.

## Steps

1. `TicketChat` mirrors `FeatureChat` without questions: it uses the existing `featureAgentRestClient` bean, `AgentChatStream`, `FeatureProperties.chatIdleTimeout`, the `Clock`, and the same session handling (`session` event stores `agent_session_id`; a `404 session_not_found` drops the stored session and retries once; `409` from the agent → `ChatBusyException`).
2. `send`: load the ticket; refuse archived; require a ready workspace (`WorkspaceMissingException`); trim the message, blank → `BlankMessageException`; one open turn per ticket (a concurrent set of ticket ids, `ChatBusyException`). Open the agent stream first; then store the user message; relay every event line; on `done` store the assistant reply with `event.text`. A closed client connection stops the relay and closes the stream (cancelling the turn), as in `FeatureChat`.
3. `cancel`: `POST /agent/v1/session/{id}/cancel` for the stored session; false without one.
4. `history` needs no workspace and works for archived projects.
5. Extract nothing from `FeatureChat`; duplicate only the small relay helper if reuse would force changes to feature code (the feature package stays untouched in this task).

## Tests

With the agent stubbed through `MockRestServiceServer`/a fake stream as `FeatureChatTests` does:

- `TicketChatTests` — a turn stores the user message and the reply and relays the lines in order (AC-25, AC-26); the context line contains project, ticket, number and type; session id stored and reused; stale session retried once; second concurrent send → `chat_busy`; blank message; no workspace → `workspace_missing`; archived → `project_archived` on send but `history` works (AC-35); agent down → `agent_unavailable`.
- `TicketChatControllerTests` (`@WebMvcTest`) — `401` on all three routes; error bodies; the stream content type is NDJSON.

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`

## Out of scope

- The agent's tools and instructions (08), the chat window (12), question cards.
