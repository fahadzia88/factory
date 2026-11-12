# #1 `ask_questions` not showing options

Type: bug · Priority: medium · Status: in_progress

## Description

`ask_questions` not showing options in the agent chat window. The options are not visible to the user. Sometimes when it is visible, nothing happens on selection.

## Findings

### 1. The ticket chat has no question cards (decided: out of scope)

`ask_questions` is feature-only. It needs a `featureId` and posts to `/features/{featureId}/questions`. Specs 07 and 12 of `007-bugs-updates` say the ticket chat has no question cards. Decision: tickets have no questions for now, so this ticket doesn't change them.

### 2. Feature chat: cards can be clipped, so options and Send are unreachable

- `app/app/components/features/feature-chat.tsx`, `Messages`: the log is `flex max-h-[32rem] min-h-48 flex-col gap-4 overflow-y-auto`.
- `app/app/components/ui/card.tsx`: `Card` has `overflow-hidden`.
- A flex item with `overflow: hidden` has an automatic minimum height of 0, so it can shrink. Once the history is taller than 32rem, flexbox shrinks the `QuestionCards` card (`w-full min-w-0`, no `shrink-0`) instead of letting the log scroll.
- The card then clips its content: the lower options, "Other" and the "Send answers" footer button are cut off. The user can tick an option but can't reach Send, so nothing happens.
- This is inferred from reading the code. I haven't reproduced it in a browser.

Smaller contributing issues:

- `disabled={busy}`: the cards are inert while the turn is still streaming. The `questions` event arrives when the tool completes, before `done`. If the model keeps going after `ask_questions`, the cards stay disabled with no explanation.
- `QuestionCards` uses `key={question.header}`. Two questions with the same header would collide.

## Plan

Feature chat only, all in the app:

1. `question-cards.tsx`: add `shrink-0` to the `Card` in `PendingCards` and `AnsweredCards`, so the log scrolls instead of the card shrinking.
2. `question-cards.tsx`: show "Waiting for the agent to finish…" in the card footer while `disabled`.
3. `question-cards.tsx`: use `key={`${i}-${question.header}`}` for the question fields.
4. No tests can be run here. Checks run in CI.

## Changes made

All in `app/app/components/features/question-cards.tsx`:

- `shrink-0` on the `Card` of `PendingCards` and `AnsweredCards`, so the chat log scrolls instead of clipping the card.
- "Waiting for the agent to finish…" next to "Send answers" while the cards are disabled.
- Keys of the question fields (pending and answered) are now `${i}-${question.header}`.
