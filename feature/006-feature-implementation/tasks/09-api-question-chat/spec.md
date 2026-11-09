---
feature: 006-feature-implementation
task: 09-api-question-chat
layer: api
status: draft
approved:
branch: feat/006-feature-implementation/09-api-question-chat
base: feat/006-feature-implementation/08-api-question-store
covers: [AC-19, AC-21, AC-22, AC-23]
---

# 09 — Questions in the chat

## Goal

The chat broker relays a `questions` line to the app, takes structured answers (or a typed reply) as the user's next
message, and returns the history with question sets in place.

## Contract

From task-breakdown.md, "Questions":

- **Relay.** When the API relays the agent's NDJSON and sees `tool` with name `mcp__app__ask_questions` and status
  `completed`, it writes before that line `{"type":"questions","id":7,"questions":[…]}` for the newest pending set. When the
  turn's `done` message is stored, the sets created in the turn get `after_message_id` = that message.
- **Answer.** `POST …/messages` accepts `{ "message": "…" }`, or
  `{ "questionsId": 7, "answers": [{ "header": "Token", "selected": ["Keep GIT_TOKEN"], "other": null }] }` (`message` then
  optional). Each answer needs a `selected` label (exactly one unless `multiSelect`) or `other` text, or both. Refused before
  streaming: `400 invalid_answers`, `409 questions_not_pending`. The user's message text:

  ```
  Answers to your questions:
  1. Token: Keep GIT_TOKEN (recommended)
  2. Turn size: Other: split into two turns
  ```

  It is stored as the user message, the set is marked `answered` with the answers and `answer_message_id`, and the text goes
  to the agent. A plain typed message while a set is pending marks that set `answered` with no answers.
- **History.** `GET …/messages` returns items in order, each message followed by the sets whose `after_message_id` is it
  (sets with none go by `created_at`):

  ```json
  [ { "kind": "message", "id": 31, "role": "assistant", "text": "…", "createdAt": "…" },
    { "kind": "questions", "id": 7, "status": "pending", "questions": [ … ],
      "answers": null, "answerMessageId": null, "createdAt": "…" } ]
  ```

## Files

Under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `service/AgentChatStream.kt` — change: `AgentChatEvent` gains `name: String?` and `status: String?` read like the other fields.
- `service/FeatureChat.kt` — change: `send(projectId, featureId, request: SendMessageRequest, out)`; `messages(...)` becomes
  `history(...)` returning the ordered items; relay and attach logic below.
- `service/AnswerFormatter.kt` — new: validates answers against a set and renders the user message text.
- `service/FeatureQuestions.kt` — change: `answer(featureId, setId, answers, messageId)`, `answerPendingByTyping(featureId, messageId)`,
  `attach(ids, messageId)`, `pending(featureId)`, `createdAfter(featureId, id)`.
- `dto/FeatureDtos.kt` — change: `SendMessageRequest(message, questionsId: Long?, answers: List<AnswerInput>?)`,
  `AnswerInput(header, selected: List<String>?, other: String?)`; `FeatureMessageResponse` gains `kind = "message"`; new
  `ChatItemResponse` (sealed) with `MessageItem` and `QuestionsItem(kind = "questions", id, status, questions, answers, answerMessageId, createdAt)`.
- `controller/FeatureChatController.kt` — change: `send` passes the whole request; `list` returns `chat.history(...)`.
- `exception/FeatureExceptions.kt`, `controller/FeatureExceptionHandler.kt` — change: `InvalidAnswersException` →
  `400 invalid_answers`; `QuestionsNotPendingException` → `409 questions_not_pending`.

## Steps

1. **Validation before streaming** (inside `send`, before `open`): if `questionsId` is set, load the set (`findByIdAndFeatureId`,
   else `questions_not_pending`), it must be `PENDING`, `answers` required; `AnswerFormatter` checks each answer's `header`
   matches a question (all questions answered, in order), each `selected` label exists in that question's options, at most
   one unless `multiSelect`, and `selected` or non-blank `other` present (`other` ≤ 500 characters). The text to send is the
   formatted block; `message` is ignored then. Without `questionsId`, `message` is required as today.
2. **Format** `"<n>. <header>: <parts>"`, parts joined by `, `: a selected label (the first option gets ` (recommended)`), and
   `Other: <text>` for free text. First line `Answers to your questions:`.
3. **After the agent accepts the turn** (inside `open(...).use`): save the user message; if `questionsId` → `answer(...)` with the
   answers JSON and the message id; else if a pending set exists → `answerPendingByTyping(featureId, messageId)` (status
   `ANSWERED`, `answers` null, `answer_message_id` set).
4. **Relay:** remember `lastSetId` (highest set id before the turn). On a `tool` event with `name == "mcp__app__ask_questions"`
   and `status == "completed"`, load the newest `PENDING` set with `id > lastSetId`, write
   `{"type":"questions","id":…,"questions":[…]}` (payload parsed back to JSON) and add its id to `turnSets`, then relay the tool
   line. No such set (the tool failed on the API) → just the tool line.
5. **On `done`:** after saving the assistant message, `attach(turnSets, message.id)`. On `error`/`cancelled` the sets keep
   `after_message_id = null`.
6. **History:** messages by `created_at, id`; each followed by its attached sets (by id); sets without `after_message_id` are
   merged in by `created_at`. Item `status` lower-cased.
7. DTOs and handler additions as above. `FeatureMessageResponse.from` keeps its signature plus the `kind` field.

## Tests

- `FeatureChatTests` (existing, `MockRestServiceServer` streams) updated for the new signature, plus:
  - a stream with `tool … ask_questions completed` after a set was stored → the app-side output has a `questions` line before
    the tool line; the set's `after_message_id` equals the stored assistant message (AC-19, AC-23);
  - structured answers: the agent receives the formatted text after the context line; the stored user message equals it; the
    set is `answered` with answers and `answer_message_id` (AC-21);
  - a typed message while a set is pending → set `answered`, no answers (AC-22); no pending set → no change;
  - invalid answers (unknown label, two labels for a single-select, missing question, empty) → `InvalidAnswersException`, nothing
    stored, nothing sent; answering an answered/superseded/other feature's set → `questions_not_pending`;
  - a cancelled turn leaves `after_message_id` null and the history still places the set by time (AC-23).
- `AnswerFormatterTests` — format of single, multi, other-only, both (AC-21).
- `FeatureChatControllerTests` — `GET` returns `kind` items in order with a questions item; `POST` with answers streams; `400`/`409`
  codes; `401`.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`.

## Out of scope

- The app (11) and the agent tool (12). Editing answers after sending.
