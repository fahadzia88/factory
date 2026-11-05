import {
  ArrowDownIcon,
  ArrowUpDownIcon,
  ArrowUpIcon,
  FolderGit2Icon,
  PlusIcon,
  SearchIcon,
  SearchXIcon,
} from "lucide-react"
import { useEffect, useState } from "react"
import { Link, useRevalidator, useSearchParams } from "react-router"

import { PageHeader } from "~/components/page-header"
import {
  ArchivedBadge,
  ProjectStatusBadge,
  UnverifiedBadge,
} from "~/components/projects/project-status-badge"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "~/components/ui/empty"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from "~/components/ui/input-group"
import { Label } from "~/components/ui/label"
import {
  Pagination,
  PaginationContent,
  PaginationEllipsis,
  PaginationItem,
} from "~/components/ui/pagination"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "~/components/ui/select"
import { Spinner } from "~/components/ui/spinner"
import { Switch } from "~/components/ui/switch"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "~/components/ui/table"
import { apiErrorMessage } from "~/lib/api"
import {
  formatDateTime,
  isProjectStatus,
  listProjects,
  PROJECT_STATUSES,
  repositoryName,
  STATUS_LABEL,
  type ProjectSort,
  type ProjectStatus,
  type SortDirection,
} from "~/lib/projects"
import type { Route } from "./+types/projects"

export function meta() {
  return [{ title: "Projects" }]
}

type ListState = {
  q: string
  status?: ProjectStatus
  archived: boolean
  sort: ProjectSort
  dir: SortDirection
  page: number
}

const SORTS: ProjectSort[] = ["updatedAt", "name", "status"]
const DEFAULT_DIRECTION: Record<ProjectSort, SortDirection> = {
  updatedAt: "desc",
  name: "asc",
  status: "asc",
}
const PAGE_SIZE = 20

/** Reads the list view from the URL; anything unknown falls back to the default. */
function parseListParams(params: URLSearchParams): ListState {
  const sortParam = params.get("sort")
  const sort = SORTS.includes(sortParam as ProjectSort)
    ? (sortParam as ProjectSort)
    : "updatedAt"
  const dirParam = params.get("dir")
  const dir =
    dirParam === "asc" || dirParam === "desc"
      ? dirParam
      : DEFAULT_DIRECTION[sort]
  const status = params.get("status")
  const page = Number.parseInt(params.get("page") ?? "", 10)
  return {
    q: params.get("q")?.trim() ?? "",
    status: isProjectStatus(status) ? status : undefined,
    archived: params.get("archived") === "1",
    sort,
    dir,
    page: Number.isInteger(page) && page > 1 ? page : 1,
  }
}

/** The URL for a list view, leaving out defaults so links stay short. */
function toSearchParams(state: ListState) {
  const params = new URLSearchParams()
  if (state.q) params.set("q", state.q)
  if (state.status) params.set("status", state.status)
  if (state.archived) params.set("archived", "1")
  if (state.sort !== "updatedAt") params.set("sort", state.sort)
  if (state.dir !== DEFAULT_DIRECTION[state.sort]) params.set("dir", state.dir)
  if (state.page > 1) params.set("page", String(state.page))
  return params
}

export async function clientLoader({ request }: Route.ClientLoaderArgs) {
  const state = parseListParams(new URL(request.url).searchParams)
  try {
    const page = await listProjects({
      q: state.q || undefined,
      status: state.status,
      includeArchived: state.archived,
      sort: state.sort,
      direction: state.dir,
      page: state.page,
    })
    return { page, error: null }
  } catch (error) {
    return { page: null, error: apiErrorMessage(error) }
  }
}
clientLoader.hydrate = true as const

export function HydrateFallback() {
  return (
    <div className="flex flex-1 items-center justify-center py-16">
      <Spinner className="size-6 text-muted-foreground" />
    </div>
  )
}

const STATUS_ITEMS = [
  { value: "any", label: "Any status" },
  ...PROJECT_STATUSES.map((status) => ({
    value: status,
    label: STATUS_LABEL[status],
  })),
]

export default function Projects({ loaderData }: Route.ComponentProps) {
  const [searchParams, setSearchParams] = useSearchParams()
  const state = parseListParams(searchParams)
  const revalidator = useRevalidator()
  const [query, setQuery] = useState(state.q)

  function update(patch: Partial<ListState>, replace = false) {
    setSearchParams(toSearchParams({ ...state, page: 1, ...patch }), {
      replace,
    })
  }

  // Back and Forward change the URL; keep the box in step without eating a trailing space
  useEffect(() => {
    setQuery((current) => (current.trim() === state.q ? current : state.q))
  }, [state.q])

  useEffect(() => {
    if (query.trim() === state.q) return
    const timer = setTimeout(() => update({ q: query.trim() }, true), 300)
    return () => clearTimeout(timer)
  }, [query])

  function toggleSort(column: ProjectSort) {
    if (state.sort === column) {
      update({ dir: state.dir === "asc" ? "desc" : "asc" })
    } else {
      update({ sort: column, dir: DEFAULT_DIRECTION[column] })
    }
  }

  const filtersActive = Boolean(state.q || state.status || state.archived)
  const { page, error } = loaderData

  return (
    <>
      <PageHeader
        title="Projects"
        description="The codebases the factory works on."
      >
        <Button render={<Link to="/projects/new" />} nativeButton={false}>
          <PlusIcon aria-hidden />
          New project
        </Button>
      </PageHeader>

      <div className="flex flex-wrap items-center gap-3">
        <form
          role="search"
          className="w-full sm:w-64"
          onSubmit={(event) => {
            event.preventDefault()
            update({ q: query.trim() })
          }}
        >
          <Label htmlFor="project-search" className="sr-only">
            Search projects by name
          </Label>
          <InputGroup>
            <InputGroupAddon>
              <SearchIcon aria-hidden />
            </InputGroupAddon>
            <InputGroupInput
              id="project-search"
              type="search"
              placeholder="Search by name"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </InputGroup>
        </form>
        <Select
          items={STATUS_ITEMS}
          value={state.status ?? "any"}
          onValueChange={(value) =>
            update({ status: isProjectStatus(value) ? value : undefined })
          }
        >
          <SelectTrigger aria-label="Filter by status" className="w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_ITEMS.map((item) => (
              <SelectItem key={item.value} value={item.value}>
                {item.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <div className="flex items-center gap-2">
          <Switch
            id="show-archived"
            checked={state.archived}
            onCheckedChange={(checked) => update({ archived: checked })}
          />
          <Label htmlFor="show-archived">Show archived</Label>
        </div>
      </div>

      {error ? (
        <Alert variant="destructive">
          <AlertTitle>Couldn't load projects</AlertTitle>
          <AlertDescription>
            <p>{error}</p>
            <Button
              variant="outline"
              size="sm"
              className="mt-2"
              disabled={revalidator.state === "loading"}
              onClick={() => revalidator.revalidate()}
            >
              Try again
            </Button>
          </AlertDescription>
        </Alert>
      ) : page && page.totalItems === 0 ? (
        filtersActive ? (
          <Empty className="border border-dashed">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <SearchXIcon />
              </EmptyMedia>
              <EmptyTitle>No projects match</EmptyTitle>
              <EmptyDescription>
                Try another name or status, or clear the filters.
              </EmptyDescription>
            </EmptyHeader>
            <EmptyContent>
              <Button
                variant="outline"
                render={<Link to="/projects" />}
                nativeButton={false}
              >
                Clear filters
              </Button>
            </EmptyContent>
          </Empty>
        ) : (
          <Empty className="border border-dashed">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <FolderGit2Icon />
              </EmptyMedia>
              <EmptyTitle>No projects yet</EmptyTitle>
              <EmptyDescription>
                Add the codebases the factory works on.
              </EmptyDescription>
            </EmptyHeader>
            <EmptyContent>
              <Button render={<Link to="/projects/new" />} nativeButton={false}>
                <PlusIcon aria-hidden />
                New project
              </Button>
            </EmptyContent>
          </Empty>
        )
      ) : page ? (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                <SortableHead
                  label="Name"
                  column="name"
                  state={state}
                  onSort={toggleSort}
                />
                <SortableHead
                  label="Status"
                  column="status"
                  state={state}
                  onSort={toggleSort}
                />
                <TableHead className="hidden sm:table-cell">
                  Repository
                </TableHead>
                <SortableHead
                  label="Last updated"
                  column="updatedAt"
                  state={state}
                  onSort={toggleSort}
                />
              </TableRow>
            </TableHeader>
            <TableBody>
              {page.items.map((project) => (
                <TableRow key={project.id}>
                  <TableCell className="max-w-48 whitespace-normal sm:max-w-none">
                    <Link
                      to={`/projects/${project.id}`}
                      className="font-medium underline-offset-4 hover:underline"
                    >
                      {project.name}
                    </Link>
                    <div className="truncate text-xs text-muted-foreground sm:hidden">
                      {repositoryName(project.repositoryUrl)}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap items-center gap-1.5">
                      <ProjectStatusBadge status={project.status} />
                      {project.archived && <ArchivedBadge />}
                    </div>
                  </TableCell>
                  <TableCell className="hidden sm:table-cell">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span
                        title={project.repositoryUrl}
                        className="text-muted-foreground"
                      >
                        {repositoryName(project.repositoryUrl)}
                      </span>
                      {!project.repositoryVerified && <UnverifiedBadge />}
                    </div>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {formatDateTime(project.updatedAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <ListFooter
            state={state}
            totalItems={page.totalItems}
            totalPages={page.totalPages}
          />
        </>
      ) : null}
    </>
  )
}

function SortableHead({
  label,
  column,
  state,
  onSort,
}: {
  label: string
  column: ProjectSort
  state: ListState
  onSort: (column: ProjectSort) => void
}) {
  const active = state.sort === column
  const Icon = !active
    ? ArrowUpDownIcon
    : state.dir === "asc"
      ? ArrowUpIcon
      : ArrowDownIcon
  return (
    <TableHead
      aria-sort={
        active ? (state.dir === "asc" ? "ascending" : "descending") : "none"
      }
    >
      <Button
        variant="ghost"
        size="sm"
        className="-ml-2"
        onClick={() => onSort(column)}
      >
        {label}
        <Icon
          aria-hidden
          className={active ? undefined : "text-muted-foreground"}
        />
      </Button>
    </TableHead>
  )
}

/** Page numbers to show: the first, the last, and the current page's neighbours. */
function pageNumbers(current: number, total: number): (number | "gap")[] {
  const wanted = new Set([1, total, current - 1, current, current + 1])
  const pages = [...wanted]
    .filter((n) => n >= 1 && n <= total)
    .sort((a, b) => a - b)
  return pages.flatMap((n, i) =>
    i > 0 && n - pages[i - 1] > 1 ? (["gap", n] as const) : [n]
  )
}

function ListFooter({
  state,
  totalItems,
  totalPages,
}: {
  state: ListState
  totalItems: number
  totalPages: number
}) {
  const first = (state.page - 1) * PAGE_SIZE + 1
  const last = Math.min(state.page * PAGE_SIZE, totalItems)
  const link = (page: number) => ({
    search: `?${toSearchParams({ ...state, page })}`,
  })

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-muted-foreground">
      <p>
        {first <= totalItems
          ? `Showing ${first}–${last} of ${totalItems}`
          : `No projects on this page; there are ${totalItems} in all.`}
      </p>
      {totalPages > 1 && (
        <Pagination className="mx-0 w-auto">
          <PaginationContent>
            <PaginationItem>
              <PageButton
                to={link(state.page - 1)}
                disabled={state.page <= 1}
                label="Previous"
              />
            </PaginationItem>
            {pageNumbers(state.page, totalPages).map((n, i) =>
              n === "gap" ? (
                <PaginationItem key={`gap-${i}`}>
                  <PaginationEllipsis />
                </PaginationItem>
              ) : (
                <PaginationItem key={n}>
                  <Button
                    variant={n === state.page ? "outline" : "ghost"}
                    size="icon"
                    aria-current={n === state.page ? "page" : undefined}
                    render={<Link to={link(n)} />}
                    nativeButton={false}
                  >
                    {n}
                  </Button>
                </PaginationItem>
              )
            )}
            <PaginationItem>
              <PageButton
                to={link(state.page + 1)}
                disabled={state.page >= totalPages}
                label="Next"
              />
            </PaginationItem>
          </PaginationContent>
        </Pagination>
      )}
    </div>
  )
}

function PageButton({
  to,
  disabled,
  label,
}: {
  to: { search: string }
  disabled: boolean
  label: "Previous" | "Next"
}) {
  if (disabled) {
    return (
      <Button variant="ghost" disabled>
        {label}
      </Button>
    )
  }
  return (
    <Button variant="ghost" render={<Link to={to} />} nativeButton={false}>
      {label}
    </Button>
  )
}
