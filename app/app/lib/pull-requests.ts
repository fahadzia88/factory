import { api, ApiError, apiErrorMessage, problemDetail } from "~/lib/api"

export type PullRequestState = "open" | "closed" | "merged"

export type PullRequestAuthor = { login: string; avatarUrl: string }

export type PullRequestSummary = {
  number: number
  title: string
  state: PullRequestState
  draft: boolean
  author: PullRequestAuthor | null
  headRef: string
  baseRef: string
  createdAt: string
  updatedAt: string
  htmlUrl: string
}

export type PullRequest = PullRequestSummary & {
  body: string | null
  commits: number
  additions: number
  deletions: number
  changedFiles: number
  mergedAt: string | null
  closedAt: string | null
}

export type PullRequestFile = {
  filename: string
  previousFilename: string | null
  status: string
  additions: number
  deletions: number
  patch: string | null
  htmlUrl: string
}

/** GitHub gives no totals, so a page only says whether another one follows. */
export type PullRequestPage<T> = {
  items: T[]
  page: number
  size: number
  hasNext: boolean
}

const base = (projectId: number) => `/api/projects/${projectId}/pulls`

const pageQuery = (page: number) => (page > 1 ? `?page=${page}` : "")

export const listPullRequests = (projectId: number, page = 1) =>
  api.get<PullRequestPage<PullRequestSummary>>(
    `${base(projectId)}${pageQuery(page)}`
  )

export const getPullRequest = (projectId: number, number: number) =>
  api.get<PullRequest>(`${base(projectId)}/${number}`)

export const listPullRequestFiles = (
  projectId: number,
  number: number,
  page = 1
) =>
  api.get<PullRequestPage<PullRequestFile>>(
    `${base(projectId)}/${number}/files${pageQuery(page)}`
  )

export type PullRequestErrorCode =
  | "invalid_request"
  | "project_not_found"
  | "pull_request_not_found"
  | "repository_not_found"
  | "github_error"
  | "github_rate_limited"
  | "github_not_configured"
  | "github_timeout"

export type PullRequestError = {
  code?: PullRequestErrorCode
  message: string
  /** GitHub or the network failed; trying again may work. */
  retryable: boolean
}

/**
 * What to show for a failed pull request call. These routes explain GitHub failures in `detail`,
 * which `apiErrorMessage` would replace with generic text for a 502.
 */
export function pullRequestError(error: unknown): PullRequestError {
  if (!(error instanceof ApiError)) {
    return { message: apiErrorMessage(error), retryable: true }
  }
  const problem = problemDetail(error) as { code?: PullRequestErrorCode }
  const message =
    error.status === 401
      ? apiErrorMessage(error)
      : (problemDetail(error).detail ?? apiErrorMessage(error))
  return { code: problem.code, message, retryable: error.status >= 500 }
}

/** A positive integer from the URL, or null for anything else. */
export function positiveInt(raw: string | null | undefined) {
  const value = Number(raw)
  return Number.isInteger(value) && value > 0 ? value : null
}
