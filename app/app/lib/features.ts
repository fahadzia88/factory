import { api, ApiError, apiErrorMessage, problemDetail } from "~/lib/api"
import { getPassword } from "~/lib/password"

// lib/api.ts doesn't export its base; the chat streams with fetch, so it needs the same one
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ""

export type FeatureWorkflowStep = {
  key: string
  title: string
  skill: string
  states: string[]
}

export type FeatureWorkflow = {
  featureStates: string[]
  steps: FeatureWorkflowStep[]
}

export type FeatureWorktree = { id: number; path: string; missing: boolean }

export type FeatureStep = {
  id: number
  position: number
  type: string
  typeTitle: string
  title: string
  documentPath: string | null
  status: string
  states: string[]
  createdAt: string
  updatedAt: string
}

export type Feature = {
  id: number
  projectId: number
  number: number
  code: string
  name: string
  slug: string
  status: string
  branch: string
  folder: string
  /** null when no worktree has the feature's branch */
  worktree: FeatureWorktree | null
  workflow: FeatureWorkflow
  steps: FeatureStep[]
  createdAt: string
  updatedAt: string
  /** Set only by create, when the docs pull request couldn't be opened */
  docsPullError?: string | null
}

export type FeatureSummary = {
  id: number
  number: number
  code: string
  name: string
  status: string
  updatedAt: string
}

export type FeatureMessage = {
  id: number
  role: "user" | "assistant"
  text: string
  createdAt: string
}

const featuresPath = (projectId: number) =>
  `/api/projects/${projectId}/features`

export function listFeatures(projectId: number) {
  return api.get<FeatureSummary[]>(featuresPath(projectId))
}

export function getFeature(projectId: number, featureId: number) {
  return api.get<Feature>(`${featuresPath(projectId)}/${featureId}`)
}

export function createFeature(projectId: number, name: string) {
  return api.post<Feature>(featuresPath(projectId), { name })
}

export function listFeatureMessages(projectId: number, featureId: number) {
  return api.get<FeatureMessage[]>(
    `${featuresPath(projectId)}/${featureId}/messages`
  )
}

export function cancelFeatureChat(projectId: number, featureId: number) {
  return api.post<{ cancelled: boolean }>(
    `${featuresPath(projectId)}/${featureId}/messages/cancel`
  )
}

export type FeatureChatTool = {
  id: string
  name: string
  status: "running" | "completed" | "failed"
}

export type FeatureChatEvent =
  | { type: "session"; sessionId: string }
  | { type: "text"; delta: string }
  | ({ type: "tool" } & FeatureChatTool)
  | { type: "done"; text: string }
  | { type: "error"; message: string }
  | { type: "cancelled" }

const CHAT_EVENTS = new Set([
  "session",
  "text",
  "tool",
  "done",
  "error",
  "cancelled",
])

/**
 * Sends a chat message and reads the agent's NDJSON reply as it streams, calling `onEvent` for each
 * event. Resolves with the final `done`, `error` or `cancelled` event; an error before the stream
 * throws `ApiError` like every other call.
 */
export async function sendFeatureMessage(
  projectId: number,
  featureId: number,
  message: string,
  {
    signal,
    onEvent,
  }: { signal?: AbortSignal; onEvent: (event: FeatureChatEvent) => void }
): Promise<FeatureChatEvent> {
  const headers = new Headers({
    Accept: "application/x-ndjson",
    "Content-Type": "application/json",
  })
  const token = getPassword()
  if (token) headers.set("Authorization", `Bearer ${token}`)
  const response = await fetch(
    `${API_BASE_URL}${featuresPath(projectId)}/${featureId}/messages`,
    { method: "POST", headers, body: JSON.stringify({ message }), signal }
  )
  if (!response.ok || !response.body) {
    const type = response.headers.get("Content-Type") ?? ""
    const body = type.includes("json")
      ? await response.json().catch(() => null)
      : await response.text().catch(() => null)
    throw new ApiError(response.status, body)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffered = ""
  let last: FeatureChatEvent | null = null
  const handle = (line: string) => {
    if (!line.trim()) return
    let event: FeatureChatEvent
    try {
      event = JSON.parse(line)
    } catch {
      return
    }
    if (!CHAT_EVENTS.has(event?.type)) return
    if (["done", "error", "cancelled"].includes(event.type)) last = event
    onEvent(event)
  }
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffered += decoder.decode(value, { stream: true })
    const lines = buffered.split("\n")
    buffered = lines.pop() ?? ""
    lines.forEach(handle)
  }
  handle(buffered + decoder.decode())
  return (
    last ?? {
      type: "error",
      message: "The connection closed before the reply finished.",
    }
  )
}

export type TaskCheckRun = {
  name: string
  status: string
  conclusion: string | null
  htmlUrl: string | null
  title: string | null
  summary: string | null
}

export type TaskChecks = {
  state: "none" | "pending" | "success" | "failure"
  runs: TaskCheckRun[]
}

export type TaskPull = {
  number: number
  title: string
  state: "open" | "closed" | "merged"
  draft: boolean
  htmlUrl: string
  baseRef: string
  headSha: string | null
  mergedAt: string | null
  checks: TaskChecks
}

/** One branch of a feature's stack; row 0 is the docs branch */
export type FeatureTask = {
  number: number
  layer: string
  slug: string
  branch: string
  baseBranch: string
  specPath: string | null
  stepId: number | null
  worktree: FeatureWorktree | null
  /** null when no pull request was opened, or GitHub couldn't be read */
  pull: TaskPull | null
  pullError: string | null
  needsRestack: boolean
  createdAt: string
  updatedAt: string
}

export function listFeatureTasks(projectId: number, featureId: number) {
  return api.get<FeatureTask[]>(`${featuresPath(projectId)}/${featureId}/tasks`)
}

export function openDocsPull(projectId: number, featureId: number) {
  return api.post<FeatureTask>(
    `${featuresPath(projectId)}/${featureId}/tasks/0/pull`
  )
}

export type FeatureErrorCode =
  | "project_not_found"
  | "feature_not_found"
  | "invalid_request"
  | "invalid_name"
  | "project_archived"
  | "checkout_not_ready"
  | "workflow_invalid"
  | "branch_checked_out"
  | "git_remote_error"
  | "git_not_configured"
  | "git_timeout"
  | "git_error"
  | "chat_busy"
  | "agent_unavailable"
  | "pull_exists"
  | "task_not_pushed"
  | "github_error"
  | "github_timeout"
  | "github_rate_limited"
  | "github_not_configured"

export type FeatureError = {
  /** null when the request never got a response */
  status: number | null
  code: FeatureErrorCode | string | null
  message: string
  retryable: boolean
}

export function featureError(error: unknown): FeatureError {
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
export function featureId(raw: string | undefined) {
  const id = Number(raw)
  return Number.isInteger(id) && id > 0 ? id : null
}

export function featureNumber(n: number) {
  return String(n).padStart(3, "0")
}

/** States come from each project's workflow, so there's no fixed label map. */
export function stateLabel(state: string) {
  const text = state.replaceAll("-", " ")
  return text.charAt(0).toUpperCase() + text.slice(1)
}
