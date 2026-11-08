import { api, ApiError, apiErrorMessage, problemDetail } from "~/lib/api"

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
