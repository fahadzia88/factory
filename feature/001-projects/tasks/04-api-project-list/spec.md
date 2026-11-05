---
feature: 001-projects
task: 04-api-project-list
layer: api
status: approved
approved: 2026-11-04
branch: feat/001-projects/04-api-project-list
base: feat/001-projects/03-api-project-endpoints
covers: [AC-12, AC-13, AC-14, AC-15, AC-16, AC-24]
---

# 04 — Project list and summary

## Goal

Add the paged, searchable, sortable project list and the Dashboard summary.

## Contract

From task-breakdown.md.

### `GET /api/projects`

- Query (all optional):
  - `q`: name contains, ignoring case; trimmed, and blank is ignored.
  - `status`: one status.
  - `includeArchived`: `true` adds archived projects; default `false`.
  - `sort`: `updatedAt` (default), `name` or `status`. Status sorts in lifecycle order
    `planning` → `retired`, and `id` breaks ties.
  - `direction`: `asc` or `desc`; default `desc` for `updatedAt`, `asc` otherwise.
  - `page`: 1-based, default `1`.
  - `size`: default `20`, maximum `100`.
- Response `200`:
  `{ "items": [Project], "page": 1, "size": 20, "totalItems": 57, "totalPages": 3 }`. A page past
  the end returns empty `items`.
- Errors: `400` for an unknown `status`, `sort` or `direction`, or `page`/`size` out of range.

### `GET /api/projects/summary`

- Response `200`: `{ "counts": { "planning": 2, "active": 5, "maintenance": 1, "retired": 0 },
  "recent": [Project] }`.
- Only non-archived projects. `counts` always has all four keys; `recent` holds at most 5,
  most recently updated first.

## Files

- `api/src/main/kotlin/com/toptier/custom/project/domain/ProjectRepository.kt` — change: extend
  `JpaSpecificationExecutor<Project>`; add `countByArchivedFalseGroupByStatus` (a JPQL `@Query`
  returning status and count) and `findTop5ByArchivedFalseOrderByUpdatedAtDescIdDesc()`.
- `api/src/main/kotlin/com/toptier/custom/project/service/ProjectService.kt` — change: add
  `list(query)` and `summary()`.
- `api/src/main/kotlin/com/toptier/custom/project/service/ProjectListQuery.kt` — new: validated
  query (q, status, includeArchived, sort, direction, page, size) and `ProjectSort`.
- `api/src/main/kotlin/com/toptier/custom/project/dto/ProjectDtos.kt` — change: add
  `ProjectPageResponse` and `ProjectSummaryResponse`.
- `api/src/main/kotlin/com/toptier/custom/project/controller/ProjectController.kt` — change: add
  `GET` (list) and `GET /summary`.
- `api/src/test/kotlin/com/toptier/custom/project/ProjectListTests.kt` — new.
- `api/src/test/kotlin/com/toptier/custom/project/ProjectControllerTests.kt` — change: list and
  summary routes.

## Steps

1. **`ProjectListQuery.of(q, status, includeArchived, sort, direction, page, size)`** takes the
   raw strings and ints from the controller and throws `ProjectValidationException` with keys
   `status`, `sort`, `direction`, `page`, `size` for bad values:
   - unknown status or sort → "Unknown value.";
   - `page < 1`;
   - `size` outside `1..100`.

   It trims `q`, and blank becomes `null`. Default direction: `desc` for `updatedAt`, `asc`
   otherwise. Task 03's handler already maps the exception to 400; field keys outside the four
   form fields are fine here.
2. **Status ordering:** sorting by `status` uses the `statusOrder` column from task 02 (the enum
   ordinal), so it follows lifecycle order rather than alphabetical order.
3. **`ProjectService.list(query)`** builds a `Specification<Project>`:
   - `archived = false` unless `includeArchived`;
   - `status = …` when set;
   - `lower(name) like %q%` (escape `%` and `_` in `q`).

   Then `PageRequest.of(page - 1, size, Sort.by(direction, field).and(Sort.by(direction, "id")))`,
   where field is `updatedAt`, `name` (sort by `nameKey`, so it ignores case) or `statusOrder`.
   Return items, `page`, `size`, `totalElements`, and `totalPages`. For a page past the end,
   return empty items with the real totals.
4. **`ProjectService.summary()`**: run the grouped count query, fill missing statuses with 0, and
   return keys in lifecycle order. Recent is `findTop5ByArchivedFalseOrderByUpdatedAtDescIdDesc`.
5. **Controller:**
   - `@GetMapping` with `@RequestParam(required = false)` for every query param (`page` and
     `size` as `Int?`) → `ProjectPageResponse`;
   - `@GetMapping("/summary")` → `ProjectSummaryResponse(counts: Map<String, Long>, recent:
     List<ProjectResponse>)`. Use a `LinkedHashMap`, so JSON keys come out in lifecycle order.

   `/summary` doesn't clash with `/{id}`, because literal paths win over patterns; a test covers
   it.

## Tests

**`ProjectListTests`**: `@DataJpaTest` with `@Import(ProjectService::class)`, a mocked checker
returning `FOUND`, and `MutableClock`. Seed projects with distinct `updatedAt` by advancing the
clock.
- 25 non-archived projects → page 1 has 20, newest updated first, `totalItems` 25, `totalPages`
  2; page 2 has 5; page 3 has empty items (AC-12).
- `q = "fac"` matches "Factory" and "Artifacts Factory" but not "Billing"; it ignores case; it
  works with matches beyond the first page; `%` in `q` is literal (AC-13).
- `status = active` returns only active projects (AC-14).
- Archived projects are excluded by default and included with `includeArchived = true` (AC-15).
- Sort by `name` asc and desc ignores case. Sort by `status` asc is
  planning, active, maintenance, retired, and desc is the reverse. Sort by `updatedAt` asc is
  oldest first. Ties are broken by `id` (AC-16).
- Invalid `status`, `sort`, `direction`, `page = 0` and `size = 101` each give a validation error.
- Summary: counts for all four statuses (zeros included) exclude archived; `recent` is at most 5,
  newest first, non-archived only (AC-24).

**`ProjectControllerTests`**:
- `GET /api/projects` passes the query params through and returns the page JSON shape.
- `GET /api/projects/summary` returns `counts` with four keys and `recent`.
- Both are 401 without a token.

## Checks

- `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`.
- The app starts against MySQL.

## Out of scope

- Full-text search across descriptions or URLs.
- Any app or agent code.
