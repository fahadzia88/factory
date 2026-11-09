---
feature: 006-feature-implementation
task: 12-agent-ask-questions
layer: agent
status: draft
approved:
branch: feat/006-feature-implementation/12-agent-ask-questions
base: feat/006-feature-implementation/11-app-question-cards
covers: [AC-19, AC-24]
---

# 12 — `ask_questions` tool

## Goal

The agent asks structured questions through a tool, and its instructions say when to use it and how approval works with it.

## Contract

From task-breakdown.md. Tool `ask_questions`: input `projectId`, `featureId`, `questions` (the question set below); it
`POST /api/projects/{projectId}/features/{featureId}/questions` and answers "Shown to the user. End your turn and wait for the
answers." The API returns `201 { "id" }` or `400 invalid_questions`.

Rules the schema enforces: 1–4 questions; `header` 1–12 characters; `question` 1–300; `multiSelect` boolean (default false); 2–4
options, the first is the recommended one; `label` 1–60, unique within a question, never "Other" (any case); `description` 1–200.

## Files

- `agent/src/tools/ask-questions.ts` — new: exports `questionsSchema` (Zod) and `askQuestions` built with `tool()`.
- `agent/src/tools/ask-questions.test.ts` — new.
- `agent/src/agent.ts` — change (`shared`, additive): import and add `askQuestions` to `tools`.
- `agent/src/instructions.md` — change (`shared`): a "Questions" section; edit the existing bullet that says to ask questions "in the
  chat (a short numbered list, …)" to point at it.

## Steps

1. `questionsSchema`: `z.array(question).min(1).max(4)` with `question = z.object({ header, question, multiSelect: z.boolean().default(false),
   options: z.array(option).min(2).max(4) })` and a `.superRefine` that adds an issue for a duplicate or "other" label.
   Describe fields so the model knows: the first option is the recommended one, put "(Recommended)" nowhere in labels, no "Other" option.
2. `askQuestions = tool("ask_questions", description, { ...featureIds, questions: questionsSchema }, handler)`: `apiPost` the body
   `{ questions }`; success → `toolText("Shown to the user. End your turn now and wait for their answers; they arrive as your next message.")`;
   an `ApiError` → `toolError("Couldn't show the questions", error)`.
3. Instructions, new section "# Questions" (and the bullet change):
   - Ask choices with `ask_questions`, not as a numbered list: 1–4 questions per call, short headers, 2–4 options with descriptions, the
     recommended option first, `multiSelect` only when several can be true. The user can always answer "Other" or type a reply.
   - After calling it, stop: write at most one short sentence and end the turn. The answers (or the user's typed reply) are the next
     message; a typed reply is the answer too.
   - Approval: when a document is ready, ask with an option labelled exactly "Approve" (and e.g. "Request changes") for that document.
     The "Approve" answer counts as the user's explicit yes for that document only. Any other answer is not an approval; never record
     approval from silence, from a different option, or for a different document. Approval rules in `rules.md` are unchanged.
   - Don't ask what a tool can tell you; don't ask more than needed.

## Tests

`ask-questions.test.ts` (`node:test`, stubbed `fetch` as the other tool tests):

- `questionsSchema.safeParse` accepts a minimal valid set; rejects 0 and 5 questions, a 13-character header, 1 and 5 options, a
  duplicate label ("A", "a"), label "Other", an over-long description (AC-19).
- The handler posts to `/api/projects/12/features/21/questions` with the bearer token and the body, and returns the "Shown to the user"
  text; a `400 invalid_questions` problem comes back as a tool error with its `detail`.
- `instructions.md` contains the "Approve" rule (a simple `includes` check) (AC-24).

## Checks

- `docker exec factory-agent npm run typecheck`, `docker exec factory-agent npm test`.

## Out of scope

- Changing `chat.ts`, `server.ts` or the locked `README.md`. Rendering (11). Task tools (13, 14).
