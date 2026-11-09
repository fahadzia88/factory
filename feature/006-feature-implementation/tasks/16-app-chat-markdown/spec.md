---
feature: 006-feature-implementation
task: 16-app-chat-markdown
layer: app
status: draft
approved:
branch: feat/006-feature-implementation/16-app-chat-markdown
base: feat/006-feature-implementation/15-infra-implementation-skill
covers: [AC-26, AC-27, AC-28]
---

# 16 — Markdown in the chat

## Goal

The agent's messages in the feature chat render as GitHub-flavored Markdown, stored and streaming, with the page never scrolling
sideways. The user's messages stay plain text.

## Contract

No API or agent change. Reuses `Markdown` from `app/app/components/pull-requests/markdown.tsx` unchanged: `react-markdown` with
`remark-gfm`, no raw HTML plugin (raw HTML shows as text), `javascript:` URLs dropped by default, links open in a new tab, tables scroll
inside their own wrapper, code blocks scroll inside `pre`.

## Files

- `app/app/components/features/feature-chat.tsx` — change: in `Message`, an assistant message with text renders `<Markdown>{message.text}</Markdown>`
  inside the existing bubble; a user message keeps `whitespace-pre-wrap` plain text.

## Steps

1. Read `feature-chat.tsx`'s `Message`. Split the bubble: assistant → `className="min-w-0 max-w-full rounded-lg bg-muted px-3 py-2 break-words"`
   with `<Markdown>`; user → the current classes and plain `{message.text}`.
2. Give the message wrapper `min-w-0` (it is `flex flex-col` with `max-w-[85%]`) so tables and code blocks can scroll inside it instead of
   widening the page; no change to the log container except `min-w-0` where needed.
3. Wrap `Message` in `React.memo` so earlier messages don't re-parse on every streamed delta (props are the message object; the `Messages` list
   already keeps unchanged message objects).
4. Do not touch `markdown.tsx` or its use in the pull request view. Question cards and tool badges stay plain.
5. Streaming: `message.text` grows by deltas; `react-markdown` handles partial input (an unclosed code fence renders as a code block), so
   nothing else is needed. The `done` event still replaces the text with the final one.

## Tests

The app has no test runner and adding one is out of scope. Manual checks, with the agent or by seeding a stored message through the API:

- AC-26: a reply with `# Heading`, a list, a table, a fenced code block and a link renders as formatted content, also while streaming and
  after reloading the page; the link opens in a new tab.
- AC-27: a reply containing `<script>alert(1)</script>`, `<img src=x onerror=alert(1)>` and `[x](javascript:alert(1))` shows the tags as text and the
  `javascript:` link has no working href; nothing runs. A user message `**not bold**` shows the asterisks.
- AC-28: at 375 px a reply with a ten-column table and a 200-character code line doesn't scroll the page; the table and the code block
  scroll inside the message.

## Checks

- `docker exec factory-app npm run typecheck`, `docker exec factory-app npm run build`,
  `docker exec factory-app npx prettier --check app/components/features/feature-chat.tsx`.

## Out of scope

- Syntax highlighting, copy buttons, Markdown for user messages or question cards, changes to the pull request view.
