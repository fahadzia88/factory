# Feature task specs (gate 3)

## Preconditions

- The feature's `task-breakdown.md` on its docs branch has `status: approved`. If not, stop and say
  so.

## Steps

`git_checkout` the docs branch. Then, for each task in the breakdown (or only the one named):

1. **Read** the owning folder's rules (`api/CLAUDE.md`, `app/CLAUDE.md` or `agent/AGENTS.md`) and
   the exact files the task will touch or imitate, on `main`.
2. **Write the spec** from `feature/_templates/task-spec.md` at
   `feature/<NNN>-<slug>/tasks/<NN>-<layer>-<slug>/spec.md`:
   - frontmatter: `branch` (exactly the breakdown's branch), `base` (the task below it, or
     `feat/<NNN>-<slug>/00-docs`), `layer`, and the ACs covered;
   - the contract copied from the breakdown, unchanged;
   - every file to add or change, with its purpose;
   - numbered steps concrete enough that the implementer can follow them without re-planning,
     using the folder's patterns;
   - tests to add, each tied to an AC;
   - the folder's checks from its rules file;
   - what's out of scope.
3. **Fill in the progress table** in task-breakdown.md with a link to each spec. The breakdown is
   approved, so this edit would need it back in draft; instead, list the spec links in your reply
   and leave the progress table to `feature-dispatch`, which updates it once the breakdown is
   `in-progress`.
4. **Ask for approval.** Commit and push, list the specs with one line each in your reply, and ask
   with `ask_question`: approve all, or request changes (approving all at once is fine).
5. **Record approval** only on an explicit yes: one `doc_set_status` call with every approved spec
   path and `status: "approved"`, then commit and push. The next step is `feature-dispatch`.

## Rules

- A spec may not change a contract. If one needs to change, say the breakdown must be revised and
  approved again first.
- Don't write code; specs only.
- The `branch` and `base` fields are enforced later: the implementer can only create the branch
  the approved spec names, from the base it names.
