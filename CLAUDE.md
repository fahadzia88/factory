# skeleton

- Each folder has its own rules: `api/CLAUDE.md`, `app/CLAUDE.md`, `agent/AGENTS.md`.
- New features follow `feature/rules.md`: requirements → breakdown → task specs → stacked PRs, with
  the user's approval at each gate. Read it before starting any feature work.

## Base files

In a project repo (one with a `base` git remote), the files listed in `.claude/base-files` come
from the skeleton. A hook enforces the list, but judge before you try an edit, and never get
around it with shell commands (`sed -i`, redirects, `git checkout` of other content).

- **`locked`**: don't edit. Decide the scope of the change:
  - it would help every project (a bug fix, security fix, shared component, convention or
    tooling change) → stop and ask the user to make it in the skeleton repo and then merge
    `base`; describe the change precisely, with the file and the diff you'd make;
  - it's specific to this project → build it in project-owned code that extends the base (a new
    package, component, route, or config property) without touching the locked file;
  - it truly needs a project-only change to that file → explain why and ask the user to add the
    path to `.claude/base-overrides` themselves.
- **`shared`**: extension points (routes, nav, dependencies, properties, compose services, agent
  instructions). Keep changes additive and project-specific; the user confirms each edit.
- Anything not listed is project code.
- In the skeleton repo itself none of this applies. When adding a file there that every project
  should keep, add it to `.claude/base-files`.
