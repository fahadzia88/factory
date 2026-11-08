---
feature: 005-probe
task: 01-api-feature-files
layer: api
status: draft
approved:
branch: feat/005-probe/01-api-feature-files
base: feat/005-probe/00-docs
covers: [AC-1, AC-3, AC-4, AC-5, AC-8, AC-9, AC-11]
---

# 01 — Feature files service

## Goal

A `FeatureFiles` service that lists the folder tree of a feature's worktree folder and reads one
markdown file from it, with the path guard that keeps every read inside that folder. No route yet
(task 02). The exception handler gets the new error codes because its `when` over the sealed
`FeatureException` must stay exhaustive.

## Contract

Provides the behavior behind these routes (task 02 exposes them), copied from task-breakdown.md:

### Where the files come from

- The root is `<feature.worktree.path>/<feature.folder>` (the feature's docs-branch worktree, from
  `FeatureService.get`), read from disk, so uncommitted edits show (AC-5). GitHub is never called.
- A "path" in both routes is relative to that root (`requirements.md`, `tasks/01-api-x/spec.md`),
  with `/` separators, no leading `/`.
- `.git` entries are never listed or served. A symlink is listed only when its real path is inside
  the root; otherwise it is skipped. Entries are sorted folders first, then files, each by name
  (case-insensitive).

### Tree (`…/files`)

- `root` is the feature's `folder` (`"feature/005-probe"`); `entries` is the nested tree.
- Directory entry: `{ name, path, type: "directory", children: [...] }` (children possibly empty).
  File entry: `{ name, path, type: "file", markdown: true|false }`.
- `markdown` is true when the name ends in `.md` (case-insensitive).
- The folder is empty or doesn't exist yet: `entries` is `[]` (AC-9).
- The tree is capped at 500 entries and 8 levels deep; anything beyond is left out.
- `409 worktree_missing` when the feature has no worktree, or it is `missing`; `detail` says to
  check the branch out again on the project page (AC-10).

### Content (`…/files/content?path=<path>`)

- Result: `path`, `content` (the file as UTF-8 text), `size` (bytes), `updatedAt` (modified time).
- Errors:
  - `400 invalid_path`: blank, absolute, contains `..` or a NUL, names `.git`, or its real path
    (symlinks followed) is outside the root (AC-11). Nothing is read.
  - `404 file_not_found`: nothing at that path, or it is a directory (AC-8).
  - `415 unsupported_file_type`: not a `.md` file (AC-4).
  - `413 file_too_large`: over 200 KB.
  - `409 worktree_missing`: as above.

## Files

Main code under `api/src/main/kotlin/com/toptier/custom/feature/`:

- `service/FeatureFiles.kt` — new: `@Service FeatureFiles(featureService: FeatureService)` with
  `tree(projectId, featureId): FeatureFileTree` and `read(projectId, featureId, path): FeatureFileContent`.
- `service/FeatureFileModels.kt` — new: `FeatureFileTree(root: String, entries: List<FeatureFileNode>)`,
  `FeatureFileNode(name, path, directory: Boolean, markdown: Boolean, children: List<FeatureFileNode>)`
  and `FeatureFileContent(path, content, size: Long, updatedAt: Instant)`.
- `exception/FeatureExceptions.kt` — change: add subclasses of `FeatureException`:
  `InvalidFilePathException`, `FeatureFileNotFoundException`, `UnsupportedFileTypeException`,
  `FileTooLargeException`, `WorktreeMissingException(branch: String)`.
- `controller/FeatureExceptionHandler.kt` — change: add the five branches to the `when` in
  `handleFeature` (below).

Tests: `api/src/test/kotlin/com/toptier/custom/feature/FeatureFilesTests.kt` (new).

## Steps

1. **Exceptions**, each with a plain-words message the handler returns as `detail`:
   - `InvalidFilePathException`: "That path isn't inside the feature's folder.";
   - `FeatureFileNotFoundException`: "That file doesn't exist.";
   - `UnsupportedFileTypeException`: "Only markdown (.md) files can be opened.";
   - `FileTooLargeException`: "That file is larger than 200 KB.";
   - `WorktreeMissingException(branch)`: "The worktree for $branch is missing. Check it out again on
     the project page."
2. **Handler branches** in `handleFeature`: `InvalidFilePathException` → `400 invalid_path`;
   `FeatureFileNotFoundException` → `404 file_not_found`; `UnsupportedFileTypeException` →
   `415 unsupported_file_type` (`HttpStatus.UNSUPPORTED_MEDIA_TYPE`); `FileTooLargeException` →
   `413 file_too_large` (`HttpStatus.CONTENT_TOO_LARGE`); `WorktreeMissingException` →
   `409 worktree_missing`. Same `problem(status, code, ex.message!!)` helper.
3. **`FeatureFiles.resolveRoot(view)`** (private): `view.worktree` null or `missing` →
   `WorktreeMissingException(view.feature.branch)`. Otherwise `worktreeRoot = Path.of(worktree.path)`,
   `folder = worktreeRoot.resolve(view.feature.folder)`. Not a directory (`Files.isDirectory`) →
   returns `null` (no folder yet). Else `realRoot = folder.toRealPath()`; if `realRoot` isn't under
   `worktreeRoot.toRealPath()` (the folder is a symlink out of the worktree) → `null`. Never serve
   anything for a `null` root.
4. **`tree(projectId, featureId)`**: `view = featureService.get(projectId, featureId)`;
   `root = resolveRoot(view)`; `FeatureFileTree(view.feature.folder, root?.let { list(it, it, "", 1, counter) } ?: emptyList())`.
   - `list(realRoot, dir, relPrefix, level, counter)`: `Files.newDirectoryStream(dir)`; skip name
     `.git`; for each entry get `real = entry.toRealPath()` (catch `IOException` → skip, a broken
     symlink) and skip when `!real.startsWith(realRoot)`; `Files.isDirectory(real)` → directory
     node whose children are `list(...)` when `level < 8`, else an empty list; `Files.isRegularFile(real)`
     → file node with `markdown = name.endsWith(".md", ignoreCase = true)`; anything else skipped.
     `path` = `relPrefix + name` joined with `/`.
   - `counter` is a shared mutable count of nodes added; once it reaches 500 stop adding (loops
     from symlinked folders pointing back inside the root are bounded by this and the depth cap).
   - Sort each level: directories first, then by `name.lowercase()`.
5. **`read(projectId, featureId, path)`**:
   1. Lexical check (no filesystem access): blank, starts with `/` or `\`, contains `\u0000` or `\`,
      or any `/`-separated segment is `..`, `.git` (case-insensitive) or empty after the first →
      `InvalidFilePathException`.
   2. `root = resolveRoot(view) ?: throw FeatureFileNotFoundException()`.
   3. `target = root.resolve(path)`; `!Files.exists(target)` → `FeatureFileNotFoundException`;
      `real = target.toRealPath()`; `!real.startsWith(root)` → `InvalidFilePathException`
      (symlink out of the root).
   4. Not `Files.isRegularFile(real)` (a directory) → `FeatureFileNotFoundException`.
   5. The *requested* name doesn't end in `.md` (ignore case) → `UnsupportedFileTypeException`.
   6. `size = Files.size(real)` over `200 * 1024` → `FileTooLargeException` (before reading).
   7. Content = `String(Files.readAllBytes(real), UTF_8)`; `updatedAt` =
      `Files.getLastModifiedTime(real).toInstant()`; returns `FeatureFileContent(path, content, size, updatedAt)`.
6. Class KDoc on `FeatureFiles` says why the checks are in code (the path comes from a request).
   Tabs for indentation; no new config properties, no use of `Clock`.

## Tests

`FeatureFilesTests` — plain JUnit with a `@TempDir` worktree and `FeatureService` as a
`@MockitoBean`-free Mockito mock (`mock(FeatureService::class.java)` with BDDMockito `given`
returning a `FeatureView` whose `Feature` has `folder = "feature/005-probe"`, `branch =
"feat/005-probe/00-docs"` and `FeatureWorktree(1, tempDir, false)`; the style of
`FeatureReadTests` for building rows is not needed, only the entity setters):

- tree lists `requirements.md`, `tasks/01-x/spec.md` and a `diagram.png` with the right `path`,
  `directory`, `markdown`, sorted folders first then by name (AC-1, AC-3, AC-4);
- tree of a folder holding only `.git`-named entries and a symlink out of the root is `[]` for those
  (never listed) (AC-11);
- a missing folder and an empty folder give `entries == []`, not an error (AC-9);
- depth cap (nine nested folders → the ninth level's children are empty) and the 500-entry cap (AC-1);
- `read` of `requirements.md` returns its text, `size` and `updatedAt`; after rewriting the file
  on disk with no commit, a second `read` returns the new text (AC-5);
- `read` of `tasks/01-x/spec.md` returns the nested file (AC-3);
- `read` of a missing file and of a directory → `FeatureFileNotFoundException` (AC-8);
- `read` of `diagram.png` → `UnsupportedFileTypeException`; of a `.MD` file → allowed (AC-4);
- `read` of a file over 200 KB → `FileTooLargeException`;
- `read` of each of `../rules.md`, `/etc/passwd`, `a/../../x.md`, `a\u0000.md`, `.git/config`,
  `x/.GIT/y.md`, a blank path → `InvalidFilePathException`; a symlink `link.md` to a file outside
  the root, and `out/x.md` through a symlinked folder to outside → `InvalidFilePathException`, and
  the outside file's text never appears in a result (AC-11);
- `worktree == null` and `worktree.missing == true` → `WorktreeMissingException` from both methods.

Skip the symlink cases with `Assumptions` if the filesystem can't create symlinks.

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`

## Out of scope

- The routes, DTOs and `@WebMvcTest` tests (task 02).
- Any app change.
- Writing, renaming or deleting files; reading from git objects or GitHub.
