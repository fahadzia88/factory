---
feature: 007-bugs-updates
task: 12-app-ticket-chat
layer: app
status: draft
approved:
branch: feat/007-bugs-updates/12-app-ticket-chat
base: feat/007-bugs-updates/11-app-ticket-workspace
covers: [AC-25, AC-26, AC-37]
---

# 12 — Agent window

## Goal

A chat window with the agent to the right of `details.md` (below it on a narrow screen). After each turn the markdown and the ticket reload.

## Contract

Consumes `GET …/tickets/{tid}/messages`, `POST …/tickets/{tid}/messages` (NDJSON, the same events as the feature chat: `session`, `text`, `tool`, `done`, `error`, `cancelled`) and `POST …/tickets/{tid}/cancel` from [task-breakdown.md](../../task-breakdown.md), unchanged. No question cards.

## Files

Under `app/app/`:

- `lib/features.ts` — change: extract the NDJSON request/reader from `sendFeatureMessage` into an exported `streamChat(path, body, { signal, onEvent })`; `sendFeatureMessage` calls it with identical behaviour.
- `lib/tickets.ts` — change: `listTicketMessages`, `sendTicketMessage` (uses `streamChat`), `cancelTicketChat`.
- `components/tickets/use-ticket-chat.ts` — new: the hook, modelled on `use-feature-chat.ts` without questions; reuses `ChatTextMessage`/`ChatHistory` types from it.
- `components/tickets/ticket-chat.tsx` — new: the window, modelled on `feature-chat.tsx`.
- `routes/ticket-detail.tsx` — change: pass `<TicketChat …/>` as the workspace panel's `side` slot and bump `refreshKey` on each finished turn.

## Steps

1. `streamChat` moves the existing fetch, bearer header, error mapping to `ApiError`, line buffering and event filtering out of `sendFeatureMessage` unchanged. The event type stays `FeatureChatEvent` minus `questions` for tickets (ignored if it arrives).
2. `useTicketChat(projectId, ticketId, onTurnEnd)`: stored history on mount (reload on error), `send` (optimistic user bubble and streaming reply bubble, tool badges, restore draft on failure), `stop` calling the cancel route, abort on unmount. Same behaviour as `useFeatureChat` for everything but questions.
3. `ticket-chat.tsx`: header "Agent" with Working/Ready badge, scrolling log (`role="log"`, `aria-live="polite"`, max height as the feature chat), agent messages through `Markdown`, user messages plain, textarea with Enter to send and Shift+Enter for a new line, Send and Stop buttons, error `Alert` with the session-expired path (copy `ChatError`). Empty state: "Ask the agent to investigate this ticket" with an example. Placeholder text: "Ask the agent to investigate…" before approval and "Ask the agent to make the change…" once `mode` is `implementer`.
4. A small banner above the log shows the agent's mode ("Investigator: reads code and writes findings in details.md" / "Implementer: can change code in the worktree").
5. On each turn end the page revalidates the ticket (mode, `hasFindings`) and bumps the details pane's `refreshKey`, so the markdown shows what the agent wrote (AC-26).
6. Layout: the window sits in the panel's `side` column; at phone width it is below the markdown with the log max height reduced; no horizontal scrolling (AC-37).

## Tests

No app test runner; checks plus a manual pass with the agent running:

- Open an in-progress ticket: markdown on the left, chat on the right; on 375 px the chat is below (AC-25, AC-37).
- Ask the agent to investigate: it reads code, `details.md` on the page updates after the turn, and no other file changed (AC-26).
- Ask it to change code before Approve: it explains it is in investigator mode.
- Stop mid-turn; reload the page and see the stored conversation.
- Feature chat on a feature page still works (shared `streamChat`).

## Checks

- `docker exec skeleton-app npm run typecheck`
- `docker exec skeleton-app npm run build`
- `docker exec skeleton-app npx prettier --check <files>`

## Out of scope

- Question cards, changes to the feature chat's behaviour, any agent or API change.
