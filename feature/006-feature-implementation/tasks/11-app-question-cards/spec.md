---
feature: 006-feature-implementation
task: 11-app-question-cards
layer: app
status: draft
approved:
branch: feat/006-feature-implementation/11-app-question-cards
base: feat/006-feature-implementation/10-app-feature-tasks
covers: [AC-19, AC-20, AC-21, AC-22, AC-23, AC-24, AC-25]
---

# 11 — Question cards in the feature chat

## Goal

The chat shows the agent's questions as selectable cards, sends the answers as the user's next message, treats a typed
reply as the answer, and shows questions and answers again after a reload.

## Contract

Consumes, from task-breakdown.md ("Questions"):

- Stream line `{"type":"questions","id":7,"questions":[{ "header", "question", "multiSelect", "options":[{ "label", "description" }] }]}`.
- `GET …/messages` → items `{ "kind": "message", "id", "role", "text", "createdAt" }` and
  `{ "kind": "questions", "id", "status": "pending" | "answered" | "superseded", "questions": […],
  "answers": [{ "header", "selected": [], "other" }] | null, "answerMessageId", "createdAt" }`.
- `POST …/messages` with `{ "message": "…" }` or `{ "questionsId": 7, "answers": [{ "header", "selected": ["…"], "other": null }] }`;
  errors `400 invalid_answers`, `409 questions_not_pending`.
- The first option of every question is the recommended one; "Other" is added by the app.

## Files

Under `app/app/`:

- `lib/features.ts` — change: types `QuestionSet`, `Question`, `QuestionOption`, `QuestionAnswer`, `HistoryItem`;
  `listFeatureMessages` returns `HistoryItem[]`; `FeatureChatEvent` gains `{ type: "questions"; id; questions }` (and `CHAT_EVENTS`);
  `sendFeatureMessage(projectId, featureId, input: string | { questionsId; answers }, …)`; `formatAnswers(questions, answers)`
  mirroring the API's message text ("Answers to your questions:\n1. Token: …", first option marked "(recommended)").
- `components/features/use-feature-chat.ts` — change: `ChatMessage` becomes a union `kind: "message" | "questions"`; the history
  mapping reads `kind`; a `questions` event appends a pending questions item after the turn's reply; `answer(questionsId, answers)`;
  `send` marks pending questions items `answered` (answers null) before streaming and restores them if the send fails.
- `components/features/question-cards.tsx` — new: `QuestionCards({ item, disabled, onSubmit })`.
- `components/features/feature-chat.tsx` — change: render `QuestionCards` for questions items; composer placeholder
  "Answer the cards or type a reply…" while a pending item exists; the composer stays enabled (only a running turn disables it).

## Steps

1. Types and calls in `lib/features.ts`. Unknown `kind` items are skipped. `formatAnswers` is the only place the text is built on
   the client (used for the local echo of the user message).
2. `QuestionCards`: one `Card` per set with a header `Badge` per question, the question text, and options:
   - single-select: `RadioGroup` (`components/ui/radio-group`); multi-select: `Checkbox` items (`components/ui/checkbox`); each option
     is a labelled row with the label in medium weight and the description muted; the first has a "Recommended" `Badge`;
   - a last "Other" option with the same control and an `Input` (labelled "Other answer") that appears when it is chosen;
   - a "Send answers" `Button` enabled when every question has a selection or a non-empty Other answer, and `disabled` while a
     turn runs; nothing is preselected, so the user's choice is always deliberate;
   - text is plain (no Markdown); all text wraps (`break-words`, `min-w-0`).
3. Answered items render read-only: each question with the chosen labels (and "Other: …"); `answers: null` → "Answered in the chat".
   `superseded` → muted "Replaced by newer questions" with no controls. Pending items stay interactive across a reload.
4. Submit: `chat.answer(item.id, answers)` → local echo `formatAnswers(...)` as the user bubble, streams the reply, marks the item
   `answered` with the chosen answers. A `409 questions_not_pending` / `400 invalid_answers` shows in the existing `ChatError` and
   reloads the history.
5. Keyboard and labels: every control reachable by Tab/arrow keys, `fieldset` + `legend` per question, visible focus. No custom key
   handling beyond what Base UI gives.
6. 375 px: cards are full width, buttons stack; no horizontal scroll.

## Tests

No test runner in the app. Manual checks against a running agent/API (12 is not merged yet: use `curl` to
`POST /api/projects/{id}/features/{fid}/questions` and a message to reload, or the API's test data):

- AC-19: a pending set shows one card per question, the recommended option first with the badge, an "Other" field, 1–4 questions.
- AC-20: multi-select allows several; single-select only one.
- AC-21: Send answers → the user bubble shows the formatted text and the agent continues.
- AC-22: with cards pending, typing a reply in the composer and sending marks the cards answered ("Answered in the chat").
- AC-23: reload → questions and answers are in the history, in order, answered ones read-only.
- AC-24: an option labelled "Approve" is sent as its label like any other (the agent's rules are task 12).
- AC-25: 375 px and keyboard-only operation.

## Checks

- `docker exec factory-app npm run typecheck`, `docker exec factory-app npm run build`, `docker exec factory-app npx prettier --check` on the files above.

## Out of scope

- Markdown in chat text (16). The agent tool and instructions (12). Changing locked `components/agent/*`.
