# Feature requirements (gate 1)

## Steps

1. **Find or create the feature.** Call `feature_list`.
   - New feature: use `nextNumber` and a short kebab-case slug. `git_checkout` the branch
     `feat/<NNN>-<slug>/00-docs` (it's created from `main`), then write
     `feature/<NNN>-<slug>/requirements.md` from `feature/_templates/requirements.md` on `main`.
   - Existing feature: `git_checkout` its docs branch and open its requirements.md. If it's
     `approved`, set it back to `draft` with a changelog line in your first write.
2. **Learn the context.** Read the parts of `api/`, `app/` and `agent/` the feature touches, and
   their rules (`api/CLAUDE.md`, `app/CLAUDE.md`, `agent/AGENTS.md`), so your questions are
   specific.
3. **Draft.** Fill in every section from what you were told. List anything you can't answer
   under "Open questions" instead of guessing. Commit and push.
4. **Settle the questions.** Ask each open question with `ask_question`, recommended option first.
   Update the file after each answer, and repeat until the list is empty.
5. **Write acceptance criteria**, numbered `AC-1`, `AC-2`, …. Each is Given / When / Then,
   observable, and checkable by a test or a person. Cover the main path, empty states, errors and
   permissions, within the agreed scope.
6. **Ask for approval.** Put the AC list in your reply and ask with `ask_question`: approve, or
   request changes.
7. **Record approval** only on an explicit yes: `doc_set_status` with `status: "approved"` and
   changelog "Approved.", then commit and push. The next step is `feature-breakdown`.

## Rules

- Don't write task plans or code.
- Never mark the file approved while open questions remain or without the user's explicit yes.
