---
feature: 005-probe
task: 05-app-documents-tab
layer: app
status: draft
approved:
branch: feat/005-probe/05-app-documents-tab
base: feat/005-probe/04-app-document-viewer
covers: [AC-1, AC-2, AC-6, AC-7, AC-8, AC-9, AC-10, AC-13, AC-16]
---

# 05 — Documents tab

## Goal

The feature page gets a "Documents" tab beside the agent chat: the file tree and the viewer, a
refresh button, refresh after each chat turn, and the empty and missing-worktree states. This is
the task that makes the feature visible.

## Contract

Consumes, copied from task-breakdown.md:

- `GET /api/projects/{id}/features/{featureId}/files` → `200 { "root", "entries": [...] }`
  (`entries` is `[]` for an empty or missing folder); `409 worktree_missing`.
- `GET /api/projects/{id}/features/{featureId}/files/content?path=<path>` → `200 { "path",
  "content", "size", "updatedAt" }`; `404 file_not_found` and the other errors listed in task 04.
- Errors are `ProblemDetail` with a `code` and a `detail` the app can show as is.
- From feature 004: the page `routes/feature-detail.tsx` and `FeatureChat`, whose `onTurnEnd`
  prop is called after every turn (task 10 of that feature, which the page already uses to
  revalidate). This task adds no prop to the chat.

## Files

- `app/app/components/features/documents-tab.tsx` — new: `DocumentsTab`.
- `app/app/routes/feature-detail.tsx` — change: put the chat and the tab in `Tabs`, count chat
  turns for the refresh.

`routes.ts` is unchanged; the page already exists.

## Steps

1. **`DocumentsTab({ projectId, featureId, refreshKey })`:**
   - State: `tree`: `{ status: "loading"; tree: FeatureFileTree | null } | { status: "loaded"; tree:
     FeatureFileTree } | { status: "error"; code: string | null; error: FeatureError }`;
     `selectedPath: string | null`; `viewerKey: number`.
   - `loadTree()` calls `getFeatureFiles` (stale answers ignored with a request-id ref). Run it on
     mount, whenever `refreshKey` changes (AC-6) and from the refresh button (AC-7). Each of these
     also bumps `viewerKey` so the open file reloads with the tree; keep the previous tree visible
     while reloading (no flash).
   - A refreshed tree that no longer lists `selectedPath` keeps the selection, so the viewer shows
     "This file no longer exists" while the tree no longer has it (AC-8).
2. **Header row:** `flex items-center justify-between`: a muted line with `tree.root` in
   `font-mono text-xs break-all`, and a refresh `Button` (`variant="outline"`, `size="sm"`,
   `RefreshCwIcon`, label "Refresh", `aria-label="Refresh documents"`) disabled and showing a
   `Spinner` while a load is in flight.
3. **Body by state:**
   - first load (`loading`, no tree): a `Skeleton` tree (five rows) next to a `Skeleton` viewer;
   - `error` with code `worktree_missing`: `Alert` (not destructive) titled "The worktree is
     missing" with the problem's `detail` ("…Check it out again on the project page.") and a link
     "Go to project" to `/projects/:projectId` (AC-10);
   - other `error`: `Alert variant="destructive"` "Couldn't load the documents" with the message and
     "Try again" when `retryable` (calls `loadTree`);
   - `loaded` with `entries.length === 0`: `Empty` (no border) with `FolderOpenIcon`, title "No
     documents yet", description "Documents appear here once the agent writes them." (AC-9);
   - otherwise the two-pane layout: `div.grid.gap-4.md:grid-cols-[16rem_minmax(0,1fr)]`, the tree
     in a bordered `div` with `max-h-72 overflow-auto md:max-h-[40rem]` containing
     `<FileTree entries={tree.entries} selectedPath onSelect={setSelectedPath} />`, and
     `<DocumentViewer projectId featureId path={selectedPath} reloadKey={viewerKey} />` (AC-1, AC-2).
     Below `md` the tree is above the viewer (AC-13).
   - A reload that fails after a tree was shown replaces the body with the error as above.
2. **Page change** in `routes/feature-detail.tsx` (task 09/10 of feature 004), keeping the details
   and Steps cards exactly as they are:
   - `const [turns, setTurns] = useState(0)`; the chat's `onTurnEnd` becomes `() => {
     revalidator.revalidate(); setTurns((n) => n + 1) }`.
   - Replace the bare `<FeatureChat …/>` with `<Tabs defaultValue="agent" key={feature.id}>`:
     `TabsList` with `TabsTrigger value="agent"` ("Agent", `BotIcon`) and `TabsTrigger
     value="documents"` ("Documents", `FileTextIcon`); `TabsContent value="agent" keepMounted`
     holding the unchanged `<FeatureChat projectId featureId onTurnEnd />`; `TabsContent
     value="documents" keepMounted` holding `<DocumentsTab projectId={feature.projectId}
     featureId={feature.id} refreshKey={turns} />`. Both panels stay mounted so a running chat
     turn isn't lost when switching tabs and the open document and its selection are kept.
   - No other change to the chat, its props or its hook (AC-16).
3. **Layout** (AC-13): `TabsList` is `w-fit`, fits at 375 px; the panel content is `min-w-0`; no
   fixed widths other than the `16rem` tree column from `md` up.
4. Prettier on the touched files.

## Tests

The app has no unit test setup. By hand, against the running stack (tasks 01–04 in the stack, the
agent configured), on a feature created through the API:

- the feature page shows an "Agent" tab (the chat as before) and a "Documents" tab; Documents shows
  the tree of `feature/<code>/` with `requirements.md`; folders expand (AC-1);
- click `requirements.md`: it renders as formatted markdown and is marked selected in the tree
  (AC-2);
- ask the agent in the chat to add a line to `requirements.md`; when the turn ends, switch to
  Documents: the tree and the open file show the change with no page reload (AC-6); do it again
  while a document is open, then switch tabs, and see it updated;
- edit a file in the worktree by hand and press Refresh: the tree and open file update (AC-7);
- delete the open file in the worktree and press Refresh: the viewer says "This file no longer
  exists" and the tree no longer lists it (AC-8);
- a feature whose folder is empty (or removed in the worktree) shows "No documents yet", not an
  error (AC-9);
- remove the feature's worktree on the project page and reload: Documents shows "The worktree is
  missing" with the check-out message and the link (AC-10);
- chat, steps, details and the other sections behave as before; switching tabs mid-turn doesn't
  interrupt the reply (AC-16);
- at 375 px the tree sits above the viewer and the page doesn't scroll sideways (AC-13);
- with the API stopped the tab shows "Couldn't load the documents" with Try again.

## Checks

- `docker exec factory-app npm run typecheck`
- `docker exec factory-app npm run build`
- `docker exec factory-app npx prettier --check app/components/features/documents-tab.tsx app/routes/feature-detail.tsx`

## Out of scope

- Tree and viewer internals (tasks 03–04); any API change.
- Auto-selecting a file on open, remembering the selection across page loads, deep links to a file.
- Editing or creating documents from the page.
