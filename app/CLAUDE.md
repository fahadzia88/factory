# app/ — React Router frontend

Rules for working in this folder. `api/` (Spring Boot) and `agent/` (eve agent) have their own
owners: don't edit them or `docker-compose.yml` from here; ask for the change instead.

## Running things

- Node runs only in the `skeleton-app` container; the host has none. Run every npm/npx command
  there: `docker exec skeleton-app npm run typecheck`, `... npm run build`,
  `... npx prettier --write <files>`, `... npx shadcn@latest add <component>`.
- Compose is the standalone `docker-compose`, not `docker compose`. To start just the frontend:
  `docker-compose up -d --no-deps app` (add `agent` for the chat). Stop what you started.
- The bind mount lags: a command run right after an edit can read a half-written file
  (prettier then reports bogus syntax errors). Wait a second or two and rerun.
- Vite watches with polling; changing `vite.config.ts` restarts the dev server on its own.
- Before calling work done: typecheck, build, and prettier on the files you changed all pass.
  There is no lint script.

## Stack

- React Router 8 in framework mode with SSR, React 19, TypeScript, Tailwind 4.
- shadcn/ui, style `base-nova`, built on **Base UI**, not Radix: compose with the `render` prop
  (there is no `asChild`), and pass `nativeButton={false}` when a Button renders a link.
  Add components with the shadcn CLI; don't hand-edit `components/ui/` unless fixing a bug.
- Imports use `~/` (`~/components/ui/button`), not the `@/` the README shows.
- Icons: lucide-react. Toasts: sonner via `components/ui/sonner`, mounted once in `root.tsx`.
- Theme: next-themes toggles the `.dark` class on `<html>` from the OS setting; style with the
  CSS variables in `app.css`, never hard-coded colors.

## Patterns

- **Auth.** The password lives in localStorage (`lib/password.ts`). Every route inside
  `routes/app-layout.tsx` is gated by its `clientLoader`; new signed-in pages go in that layout
  in `routes.ts` and need no auth code of their own. Add them to `NAV_ITEMS` if they belong in
  the header.
- **API calls.** Use `api()` / `api.get` etc. from `lib/api.ts`: it adds the bearer token and
  throws `ApiError`. Show failures with `apiErrorMessage(error)` inside an `Alert` from
  `components/ui`. A 401 reads "Your session has expired. Sign in again."
- **Data loading.** Pages load in `clientLoader` with `clientLoader.hydrate = true` and a
  `HydrateFallback`. Slow, optional data goes back as a promise and renders with
  `<Suspense>` + `<Await>`; give `<Await errorElement>` an element, never `null`.
- **Hydration.** Anything that renders differently on the server than the browser (toasts,
  `localStorage` reads) mounts only after hydration.
- **Same-origin backends.** Vite proxies `/api` to `API_URL` and `/eve` to `AGENT_URL`. Call
  them with relative paths; never hard-code a host.
- **Agent chat.** `components/agent/agent-chat.tsx` uses `useEveAgent` from `eve/react`.
  eve's docs are in `node_modules/eve/docs/guides/frontend/`.
- **Pages.** Start with `PageHeader`, use `Card`s for sections, and `Empty` for empty states.
  Layouts must work at phone width without horizontal scrolling.

## Style

- Prettier: no semicolons, double quotes, trailing commas (es5), 80 columns; the Tailwind
  plugin sorts classes. Format the files you touch, not the whole tree.
- Match the surrounding code: few comments, and only ones that say why.
- UI text is plain and sentence case; error messages say what happened and what to do next.
- Don't commit or push unless asked.
