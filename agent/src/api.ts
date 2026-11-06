/** A non-2xx answer from the API. The message is safe to show: it never includes the token. */
export class ApiError extends Error {
  readonly status: number;
  /** The ProblemDetail's `code` (e.g. `checkout_not_found`), when the API sent one. */
  readonly code?: string;

  constructor(status: number, message: string, code?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    if (code !== undefined) this.code = code;
  }
}

type Params = Record<string, string | number | boolean | undefined>;

/** GETs `path` from the application's API with the shared bearer token; for read-only tools. */
export async function apiGet<T>(path: string, params: Params = {}): Promise<T> {
  const url = apiUrl(path);
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) url.searchParams.set(key, String(value));
  }
  return request<T>(url, { method: "GET" }, 10_000);
}

/** POSTs `body` as JSON to `path` with the shared bearer token. An empty response is `undefined`. */
export async function apiPost<T>(path: string, body: unknown, timeoutMs = 10_000): Promise<T> {
  return request<T>(
    apiUrl(path),
    { method: "POST", body: JSON.stringify(body), headers: { "content-type": "application/json" } },
    timeoutMs,
  );
}

function apiUrl(path: string): URL {
  return new URL(path, process.env.API_URL ?? "http://localhost:8080");
}

async function request<T>(
  url: URL,
  init: { method: string; body?: string; headers?: Record<string, string> },
  timeoutMs: number,
): Promise<T> {
  const response = await fetch(url, {
    method: init.method,
    body: init.body,
    headers: {
      ...init.headers,
      authorization: `Bearer ${process.env.API_TOKEN}`,
      accept: "application/json",
    },
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await response.text();
  if (!response.ok) {
    const problem = parseProblem(text);
    throw new ApiError(response.status, problem.detail ?? `API request failed (${response.status})`, problem.code);
  }
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

/** The `detail` and `code` of a ProblemDetail body, when the body is one. */
function parseProblem(text: string): { detail?: string; code?: string } {
  try {
    const body: unknown = JSON.parse(text);
    if (body && typeof body === "object") {
      const detail = "detail" in body && typeof body.detail === "string" ? body.detail : undefined;
      const code = "code" in body && typeof body.code === "string" ? body.code : undefined;
      return { detail, code };
    }
  } catch {
    // Not JSON
  }
  return {};
}
