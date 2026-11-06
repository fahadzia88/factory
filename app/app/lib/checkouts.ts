import { api, ApiError, apiErrorMessage, problemDetail } from "~/lib/api"

export type CheckoutState = "cloning" | "ready" | "failed"

export type CommitRef = { sha: string; subject: string }

export type Worktree = {
  id: number
  branch: string
  path: string
  main: boolean
  baseBranch: string | null
  missing: boolean
  head: CommitRef | null
  createdAt: string
}

export type Checkout = {
  id: number
  projectId: number
  state: CheckoutState
  error: string | null
  path: string
  // null before the first clone or fetch
  defaultBranch: string | null
  createdAt: string
  lastFetchedAt: string | null
  worktrees: Worktree[]
}

export type UnsavedWork = {
  worktreeId: number
  branch: string
  changedFiles: string[]
  unpushedCommits: CommitRef[]
}

export type AddWorktreeInput = { branch: string; base?: string }

export type PushResult = { branch: string; head: string }

const checkoutPath = (projectId: number) =>
  `/api/projects/${projectId}/checkout`

export function getCheckout(projectId: number) {
  return api.get<Checkout>(checkoutPath(projectId))
}

/** Starts the clone; also retries a failed one. */
export function startCheckout(projectId: number) {
  return api.post<Checkout>(checkoutPath(projectId))
}

export function fetchCheckout(projectId: number) {
  return api.post<Checkout>(`${checkoutPath(projectId)}/fetch`)
}

export function getUnsavedWork(projectId: number) {
  return api.get<UnsavedWork[]>(`${checkoutPath(projectId)}/unsaved`)
}

export function addWorktree(projectId: number, input: AddWorktreeInput) {
  const body: AddWorktreeInput = { branch: input.branch }
  if (typeof input.base === "string" && input.base !== "")
    body.base = input.base
  return api.post<Worktree>(`${checkoutPath(projectId)}/worktrees`, body)
}

export function removeWorktree(
  projectId: number,
  worktreeId: number,
  force = false
) {
  return api.delete<void>(
    `${checkoutPath(projectId)}/worktrees/${worktreeId}${force ? "?force=true" : ""}`
  )
}

export function pushWorktree(
  projectId: number,
  worktreeId: number,
  force = false
) {
  return api.post<PushResult>(
    `${checkoutPath(projectId)}/worktrees/${worktreeId}/push`,
    { force }
  )
}

export type CheckoutErrorCode =
  | "project_not_found"
  | "checkout_not_found"
  | "project_archived"
  | "checkout_exists"
  | "git_not_configured"
  | "checkout_not_ready"
  | "git_remote_error"
  | "git_timeout"
  | "invalid_branch"
  | "base_not_found"
  | "branch_checked_out"
  | "worktree_not_found"
  | "main_worktree"
  | "worktree_unsaved"
  | "push_default_branch"
  | "push_rejected"
  | "worktree_missing"
  | "invalid_request"
  | "git_error"

export type CheckoutError = {
  /** null when the request never got a response */
  status: number | null
  code: CheckoutErrorCode | string | null
  message: string
  retryable: boolean
  /** only for worktree_unsaved */
  unsaved: UnsavedWork | null
}

export function checkoutError(error: unknown): CheckoutError {
  if (!(error instanceof ApiError)) {
    return {
      status: null,
      code: null,
      message: apiErrorMessage(error),
      retryable: true,
      unsaved: null,
    }
  }
  const problem = problemDetail(error) as {
    code?: unknown
    detail?: unknown
    unsaved?: unknown
  }
  const code = typeof problem.code === "string" ? problem.code : null
  // lib/api.ts maps every 502 to generic text, so the server's detail comes first
  const message =
    error.status !== 401 &&
    typeof problem.detail === "string" &&
    problem.detail !== ""
      ? problem.detail
      : apiErrorMessage(error)
  return {
    status: error.status,
    code,
    message,
    retryable: error.status >= 500,
    unsaved:
      code === "worktree_unsaved" && isUnsavedWork(problem.unsaved)
        ? problem.unsaved
        : null,
  }
}

function isUnsavedWork(value: unknown): value is UnsavedWork {
  return (
    typeof value === "object" &&
    value !== null &&
    Array.isArray((value as { changedFiles?: unknown }).changedFiles)
  )
}
