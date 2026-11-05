/** A non-2xx answer from the API. The message is safe to show: it never includes the token. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

type Params = Record<string, string | number | boolean | undefined>;

/** GETs `path` from the application's API with the shared bearer token; for read-only tools. */
export async function apiGet<T>(path: string, params: Params = {}): Promise<T> {
  const url = new URL(path, process.env.API_URL ?? "http://localhost:8080");
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) url.searchParams.set(key, String(value));
  }
  const response = await fetch(url, {
    headers: { authorization: `Bearer ${process.env.API_TOKEN}`, accept: "application/json" },
    signal: AbortSignal.timeout(10_000),
  });
  const text = await response.text();
  if (!response.ok) throw new ApiError(response.status, problemDetail(text) ?? `API request failed (${response.status})`);
  return JSON.parse(text) as T;
}

/** The `detail` of a ProblemDetail body, when the body is one. */
function problemDetail(text: string): string | undefined {
  try {
    const body: unknown = JSON.parse(text);
    if (body && typeof body === "object" && "detail" in body && typeof body.detail === "string") return body.detail;
  } catch {
    // Not JSON
  }
  return undefined;
}
