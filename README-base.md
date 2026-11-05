# skeleton

A starter stack for new projects: a Spring Boot API, a React frontend, an AI agent and a MySQL
database, all run with Docker Compose. Every project starts as a copy of this repo, and later
skeleton improvements are merged into it.

**This repo is a base, not a project.** Don't build a product here. Create a project repo from it
(see [Starting a new project](#starting-a-new-project)) and put the project's code there.

## What's inside

| Folder   | What                                                                                          | Port (host) |
| -------- | --------------------------------------------------------------------------------------------- | ----------- |
| `api/`   | Spring Boot 4 / Kotlin backend. Static bearer-token auth, JPA on MySQL. See `api/CLAUDE.md`. | 8080        |
| `app/`   | React Router + Vite + shadcn/ui frontend. Proxies `/api` to the API and `/agent` to the agent.| 3000        |
| `agent/` | AI agent built on the Claude Agent SDK, calling Anthropic. See `agent/README.md`.            | 3001        |
| mysql    | MySQL 8.4 (`skeleton` database, data in the `mysql_data` volume).                             | 3306        |

All requests to the API and the agent need `Authorization: Bearer <API_TOKEN>`. The app's sign-in
password is that same token.

## Running it

You need Docker, with the standalone `docker-compose`. The host needs no JDK or Node; everything
runs in containers.

1. Create a `.env` in the repo root. It's gitignored, so never commit it.

   ```
   ANTHROPIC_API_KEY=sk-ant-...   # required by the agent
   API_TOKEN=change-me            # optional; defaults to 123456 (dev only)
   # MYSQL_DATABASE / MYSQL_USER / MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD are optional too
   ```

2. Start the stack:

   ```bash
   docker-compose up -d
   ```

3. The `api` container starts idle. Run the backend inside it:

   ```bash
   docker exec -it -w /workspace skeleton-api ./gradlew bootRun
   ```

4. Open http://localhost:3000 and sign in with your `API_TOKEN`.

Each folder's own README or `CLAUDE.md` covers tests, builds and conventions.

## Starting a new project

A project repo is a copy of the skeleton whose history keeps the skeleton as its **`base`**
remote, so later skeleton updates can be merged in. The project pushes only to its own
`origin`.

```bash
# 1. Create an empty repo for the project on GitHub, e.g. fahadzia88/my-project

# 2. Clone the skeleton under the project's name
git clone git@github.com:fahadzia88/skeleton.git my-project
cd my-project

# 3. Make the skeleton the downstream-only "base" remote
git remote rename origin base
git remote set-url --push base DISABLE   # pushing to the skeleton now fails

# 4. Point origin at the project's own repo and push
git remote add origin git@github.com:fahadzia88/my-project.git
git push -u origin main
```

`git remote -v` should then show:

```
base    git@github.com:fahadzia88/skeleton.git (fetch)
base    DISABLE (push)
origin  git@github.com:fahadzia88/my-project.git (fetch)
origin  git@github.com:fahadzia88/my-project.git (push)
```

Then give the project its own names in `docker-compose.yml`: the top-level `name:`, and each
`container_name`. Otherwise its containers clash with the skeleton's or another project's, and
they share the same MySQL volume.

### Rules for project repos

- **Never push to the skeleton.** A project uses the skeleton only as a downstream `base`
  remote: it fetches and merges from it, nothing else. The `DISABLE` push URL enforces this, so
  don't change it.
- **Make generic fixes in the skeleton itself.** If a fix or improvement belongs in every
  project, make it in a clone of the skeleton repo, commit it there, and merge it down into the
  projects. Don't push it up from a project.
- **Keep project code separate from skeleton code.** Add your own packages, routes, components
  and config instead of rewriting skeleton files, and merges from `base` stay conflict-free.
  Where you must change a skeleton file, keep the change small.
- **Keep project secrets out of the skeleton.** API keys, private keys and project hostnames
  belong in the project's `.env` or gitignored `secrets/` folders, never in this repo.

### Pulling skeleton updates into a project

```bash
git fetch base
git merge base/main      # resolve any conflicts, run the tests, then push to origin
git push origin main
```

## Working on the skeleton

Changes here should be generic: useful to any project and free of project-specific names,
hostnames, keys or domain code. Before committing, check that nothing project-specific has
crept in.
