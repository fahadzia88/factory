import { api, ApiError, apiErrorMessage, problemDetail } from "~/lib/api"
import { streamChat, type ChatStreamOptions } from "~/lib/features"

export const TICKET_TYPES = ["bug", "improvement"] as const
export type TicketType = (typeof TICKET_TYPES)[number]

export const TYPE_LABEL: Record<TicketType, string> = {
  bug: "Bug",
  improvement: "Improvement",
}

export const TICKET_PRIORITIES = ["low", "medium", "high", "critical"] as const
export type TicketPriority = (typeof TICKET_PRIORITIES)[number]

export const PRIORITY_LABEL: Record<TicketPriority, string> = {
  low: "Low",
  medium: "Medium",
  high: "High",
  critical: "Critical",
}

export function isTicketType(value: unknown): value is TicketType {
  return TICKET_TYPES.includes(value as TicketType)
}

export type TicketWorktree = { id: number; path: string; missing: boolean }

export type TicketWorkspace = {
  state: "none" | "ready" | "failed"
  error: string | null
  branch: string | null
  folder: string | null
  worktree: TicketWorktree | null
}

export type Ticket = {
  id: number
  projectId: number
  number: number
  type: TicketType
  title: string
  description: string | null
  priority: TicketPriority
  /** A status value like `in_progress`; see `statusLabel` */
  status: string
  statusDescription: string
  /** The statuses the ticket can move to now; empty for an archived project */
  allowedStatuses: string[]
  pullRequestUrl: string | null
  pullNumber: number | null
  workspace: TicketWorkspace
  mode: "investigator" | "implementer"
  approvedAt: string | null
  hasFindings: boolean
  createdAt: string
  updatedAt: string
}

export type StatusInfo = {
  status: string
  description: string
  types: TicketType[]
}

export type TicketEvent = {
  id: number
  kind:
    | "created"
    | "status"
    | "edit"
    | "workspace"
    | "approve"
    | "commit"
    | "push"
    | "pull"
  field: string | null
  oldValue: string | null
  newValue: string | null
  detail: string | null
  createdAt: string
}

export type TicketDetails = {
  path: string
  content: string
  hasFindings: boolean
}

export type TicketInput = {
  type: TicketType | ""
  title: string
  description: string
  priority: TicketPriority
}

export type TicketChanges = Partial<{
  title: string
  description: string | null
  priority: TicketPriority
  pullRequestUrl: string | null
}>

export type TicketFilters = { type?: TicketType; status?: string }

const ticketsPath = (projectId: number) => `/api/projects/${projectId}/tickets`

const ticketPath = (projectId: number, ticketId: number) =>
  `${ticketsPath(projectId)}/${ticketId}`

export function listTickets(projectId: number, filters: TicketFilters = {}) {
  const search = new URLSearchParams()
  if (filters.type) search.set("type", filters.type)
  if (filters.status) search.set("status", filters.status)
  const query = search.toString()
  return api.get<Ticket[]>(
    `${ticketsPath(projectId)}${query ? `?${query}` : ""}`
  )
}

export function createTicket(projectId: number, input: TicketInput) {
  return api.post<Ticket>(ticketsPath(projectId), {
    type: input.type,
    title: input.title,
    description: input.description,
    priority: input.priority,
  })
}

export const getTicket = (projectId: number, ticketId: number) =>
  api.get<Ticket>(ticketPath(projectId, ticketId))

export const editTicket = (
  projectId: number,
  ticketId: number,
  changes: TicketChanges
) => api.patch<Ticket>(ticketPath(projectId, ticketId), changes)

export const changeTicketStatus = (
  projectId: number,
  ticketId: number,
  status: string
) => api.post<Ticket>(`${ticketPath(projectId, ticketId)}/status`, { status })

export const listStatuses = (projectId: number) =>
  api.get<StatusInfo[]>(`${ticketsPath(projectId)}/statuses`)

export const getHistory = (projectId: number, ticketId: number) =>
  api.get<TicketEvent[]>(`${ticketPath(projectId, ticketId)}/history`)

export const getDetails = (projectId: number, ticketId: number) =>
  api.get<TicketDetails>(`${ticketPath(projectId, ticketId)}/details`)

export const retryWorkspace = (projectId: number, ticketId: number) =>
  api.post<Ticket>(`${ticketPath(projectId, ticketId)}/workspace`)

export const approveTicket = (projectId: number, ticketId: number) =>
  api.post<Ticket>(`${ticketPath(projectId, ticketId)}/approve`)

export const commitTicket = (
  projectId: number,
  ticketId: number,
  message: string
) =>
  api.post<{ committed: boolean; sha: string | null }>(
    `${ticketPath(projectId, ticketId)}/commit`,
    { message }
  )

export const pushTicket = (projectId: number, ticketId: number) =>
  api.post<{ branch: string; head: string }>(
    `${ticketPath(projectId, ticketId)}/push`
  )

export const openTicketPull = (
  projectId: number,
  ticketId: number,
  options: { title?: string; replaceLink?: boolean } = {}
) => api.post<Ticket>(`${ticketPath(projectId, ticketId)}/pull`, options)

export type TicketMessage = {
  id: number
  role: "user" | "assistant"
  text: string
  createdAt: string
}

export const listTicketMessages = (projectId: number, ticketId: number) =>
  api.get<TicketMessage[]>(`${ticketPath(projectId, ticketId)}/messages`)

/** Sends a chat message and reads the agent's NDJSON reply as it streams; see `streamChat`. */
export function sendTicketMessage(
  projectId: number,
  ticketId: number,
  message: string,
  options: ChatStreamOptions
) {
  return streamChat(
    `${ticketPath(projectId, ticketId)}/messages`,
    { message },
    options
  )
}

export const cancelTicketChat = (projectId: number, ticketId: number) =>
  api.post<{ cancelled: boolean }>(`${ticketPath(projectId, ticketId)}/cancel`)

export type TicketError = {
  /** null when the request never got a response */
  status: number | null
  code: string | null
  message: string
  retryable: boolean
}

/** The server's reason for a failed ticket call, with its `code`; 5xx and network errors can be retried. */
export function ticketError(error: unknown): TicketError {
  if (!(error instanceof ApiError)) {
    return {
      status: null,
      code: null,
      message: apiErrorMessage(error),
      retryable: true,
    }
  }
  const problem = problemDetail(error) as { code?: unknown; detail?: unknown }
  // lib/api.ts maps every 502 to generic text, so the server's detail comes first
  const message =
    error.status !== 401 &&
    typeof problem.detail === "string" &&
    problem.detail !== ""
      ? problem.detail
      : apiErrorMessage(error)
  return {
    status: error.status,
    code: typeof problem.code === "string" ? problem.code : null,
    message,
    retryable: error.status >= 500,
  }
}

/** A positive integer id from the URL, or null for anything else. */
export function ticketId(raw: string | undefined) {
  const id = Number(raw)
  return Number.isInteger(id) && id > 0 ? id : null
}

/** `in_progress` → "In progress", `incorrect_expectation` → "Incorrect expectation" */
export function statusLabel(status: string) {
  const text = status.replaceAll("_", " ")
  return text.charAt(0).toUpperCase() + text.slice(1)
}
