---
name: feature-requirements
description: Start a new feature or revise its requirements. Creates feature/<NNN>-<slug>/requirements.md, works through open questions with the user, and writes numbered Given/When/Then acceptance criteria. Use when the user brings a feature request or asks to change an existing feature's requirements.
argument-hint: <feature description or existing feature folder>
---

# Feature requirements

Gate 1 of the workflow in `feature/rules.md`. Read that file first.

## Steps

1. **Find or create the folder.** For a new feature, pick the next number after the highest
   `feature/NNN-*` folder and a short kebab-case slug, then copy
   `feature/_templates/requirements.md` to `feature/<NNN>-<slug>/requirements.md`. For an
   existing feature, open its requirements.md; if it's `approved`, set it back to `draft` and add
   a changelog line before changing anything.
2. **Learn the context.** Read the parts of `api/`, `app/` and `agent/` the feature touches, and
   any memory about relevant constraints (external API limits, for example), so your questions
   are specific.
3. **Draft.** Fill in every section from what the user said. List anything you can't answer
   under "Open questions" rather than guessing.
4. **Settle the questions.** Ask the open questions with AskUserQuestion (up to 4 at a time,
   with a recommended option first). Update the file after each round. Repeat until the list
   is empty.
5. **Write acceptance criteria.** Number them `AC-1`, `AC-2`, …. Each one is Given / When /
   Then, describes something observable, and can be checked by a test or a person. Cover the
   main path, empty states, errors and permissions. Keep to the agreed scope.
6. **Ask for approval.** Show the AC list in chat with a link to the file and ask the user to
   approve it or ask for changes.
7. **Record approval** only when the user explicitly approves: set `status: approved` and
   `approved: <today>`, and add a changelog line. Then suggest `/feature-breakdown`.

## Rules

- Don't write task plans or code here.
- Never mark the file approved while open questions remain or without the user's explicit yes.
