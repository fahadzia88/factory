import { api, ApiError, problemDetail } from "~/lib/api"

export const PROJECT_STATUSES = [
  "planning",
  "active",
  "maintenance",
  "retired",
] as const

export type ProjectStatus = (typeof PROJECT_STATUSES)[number]

export const STATUS_LABEL: Record<ProjectStatus, string> = {
  planning: "Planning",
  active: "Active",
  maintenance: "Maintenance",
  retired: "Retired",
}

export function isProjectStatus(value: unknown): value is ProjectStatus {
  return PROJECT_STATUSES.includes(value as ProjectStatus)
}

export type Project = {
  id: number
  name: string
  repositoryUrl: string
  description: string | null
  status: ProjectStatus
  archived: boolean
  repositoryVerified: boolean
  createdAt: string
  updatedAt: string
}

export type ProjectPage = {
  items: Project[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export type ProjectSort = "updatedAt" | "name" | "status"
export type SortDirection = "asc" | "desc"

export type ProjectListParams = {
  q?: string
  status?: ProjectStatus
  includeArchived?: boolean
  sort?: ProjectSort
  direction?: SortDirection
  page?: number
}

export type ProjectSummary = {
  counts: Record<ProjectStatus, number>
  recent: Project[]
}

export type ProjectInput = {
  name: string
  repositoryUrl: string
  description: string
  status: ProjectStatus
}

export function listProjects(params: ProjectListParams = {}) {
  const search = new URLSearchParams()
  if (params.q) search.set("q", params.q)
  if (params.status) search.set("status", params.status)
  if (params.includeArchived) search.set("includeArchived", "true")
  if (params.sort) search.set("sort", params.sort)
  if (params.direction) search.set("direction", params.direction)
  if (params.page && params.page > 1) search.set("page", String(params.page))
  const query = search.toString()
  return api.get<ProjectPage>(`/api/projects${query ? `?${query}` : ""}`)
}

export const getProject = (id: number) =>
  api.get<Project>(`/api/projects/${id}`)

export const createProject = (input: ProjectInput) =>
  api.post<Project>("/api/projects", input)

export const updateProject = (id: number, input: ProjectInput) =>
  api.put<Project>(`/api/projects/${id}`, input)

export const archiveProject = (id: number) =>
  api.post<Project>(`/api/projects/${id}/archive`)

export const restoreProject = (id: number) =>
  api.post<Project>(`/api/projects/${id}/restore`)

export const getProjectSummary = () =>
  api.get<ProjectSummary>("/api/projects/summary")

/** Field messages from a rejected save (`errors` in the problem detail), keyed by field. */
export function projectFieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {}
  const errors = (problemDetail(error) as { errors?: unknown }).errors
  if (!errors || typeof errors !== "object") return {}
  return Object.fromEntries(
    Object.entries(errors).filter(
      (entry): entry is [string, string] => typeof entry[1] === "string"
    )
  )
}

/** `owner/repo` from a GitHub URL, for compact display. */
export function repositoryName(url: string) {
  return url.replace(/^https:\/\/github\.com\//, "").replace(/\/$/, "")
}

const dateTime = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
})

export function formatDateTime(iso: string) {
  return dateTime.format(new Date(iso))
}
