import { getPassword } from "~/lib/password"

// Empty by default: in dev, Vite proxies /api to the backend (see vite.config.ts).
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ""

export class ApiError extends Error {
  readonly status: number
  readonly body: unknown

  constructor(status: number, body: unknown) {
    super(`API request failed with status ${status}`)
    this.name = "ApiError"
    this.status = status
    this.body = body
  }
}

export type ApiOptions = Omit<RequestInit, "body"> & {
  /** Bearer token; defaults to the stored password. Pass `null` to send no Authorization header. */
  token?: string | null
  /** Plain objects and arrays are sent as JSON; FormData, Blob, strings etc. are sent as-is. */
  body?: unknown
}

export async function api<T = unknown>(
  path: string,
  { token = getPassword(), body, headers, ...init }: ApiOptions = {}
): Promise<T> {
  const requestHeaders = new Headers(headers)
  requestHeaders.set("Accept", "application/json")
  if (token) requestHeaders.set("Authorization", `Bearer ${token}`)

  let requestBody: BodyInit | undefined
  if (isJsonBody(body)) {
    requestHeaders.set("Content-Type", "application/json")
    requestBody = JSON.stringify(body)
  } else {
    requestBody = body as BodyInit | undefined
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: requestHeaders,
    body: requestBody,
  })

  const data = await parseBody(response)
  if (!response.ok) throw new ApiError(response.status, data)
  return data as T
}

api.get = <T = unknown>(path: string, options?: ApiOptions) =>
  api<T>(path, { ...options, method: "GET" })
api.post = <T = unknown>(path: string, body?: unknown, options?: ApiOptions) =>
  api<T>(path, { ...options, method: "POST", body })
api.put = <T = unknown>(path: string, body?: unknown, options?: ApiOptions) =>
  api<T>(path, { ...options, method: "PUT", body })
api.patch = <T = unknown>(path: string, body?: unknown, options?: ApiOptions) =>
  api<T>(path, { ...options, method: "PATCH", body })
api.delete = <T = unknown>(path: string, options?: ApiOptions) =>
  api<T>(path, { ...options, method: "DELETE" })

function isJsonBody(body: unknown) {
  if (body === undefined || body === null) return false
  if (typeof body !== "object") return false
  return !(
    body instanceof FormData ||
    body instanceof Blob ||
    body instanceof URLSearchParams ||
    body instanceof ArrayBuffer ||
    ArrayBuffer.isView(body) ||
    body instanceof ReadableStream
  )
}

async function parseBody(response: Response) {
  if (response.status === 204) return undefined
  const text = await response.text()
  if (!text) return undefined
  const isJson = response.headers.get("Content-Type")?.includes("json")
  return isJson ? JSON.parse(text) : text
}

export type ProblemDetail = {
  detail?: string
  /** OperationOutcome issues Epic returned; `expression` names the offending element, e.g. ["birthdate"]. */
  issues?: {
    details?: { text?: string }
    diagnostics?: string
    expression?: string[]
  }[]
}

export function problemDetail(error: ApiError): ProblemDetail {
  return (
    error.body && typeof error.body === "object" ? error.body : {}
  ) as ProblemDetail
}

/** A message to show the user for a failed API call: Epic's own reasons when it gave any. */
export function apiErrorMessage(error: unknown) {
  if (!(error instanceof ApiError)) {
    // fetch rejects with a TypeError when the request never got a response
    return error instanceof TypeError
      ? "Couldn't reach the server. Check your connection and try again."
      : "Something went wrong. Try again."
  }
  const problem = problemDetail(error)
  const issues = [
    ...new Set(
      (problem.issues ?? [])
        .map((issue) => issue.details?.text || issue.diagnostics)
        .filter(Boolean)
    ),
  ]
  if (issues.length) return issues.join(" ")
  if (error.status === 401) return "Your session has expired. Sign in again."
  if (error.status === 404) return "Not found."
  if (error.status === 502)
    return "Epic is unavailable right now. Try again shortly."
  return problem.detail ?? `Request failed (${error.status}).`
}
