import { randomUUID } from "node:crypto";
import { mkdirSync } from "node:fs";
import { type IncomingMessage, type ServerResponse, createServer } from "node:http";
import { resolve } from "node:path";
import { getSessionInfo } from "@anthropic-ai/claude-agent-sdk";
import { authorized } from "./auth.ts";
import { type ChatEvent, runTurn } from "./chat.ts";

const PORT = Number(process.env.PORT ?? 3001);
const HOST = process.env.HOST ?? "127.0.0.1";
const API_TOKEN = process.env.API_TOKEN || "123456";
const MAX_BODY_BYTES = 256 * 1024;
const SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

// Sessions are transcripts written by the SDK under its config dir; keep them in this folder
// (gitignored) so they survive container restarts. The working directory is empty: the agent has
// no file access, and resume finds sessions by the directory they were started in.
const DATA_DIR = resolve(process.env.AGENT_DATA_DIR ?? ".data");
const CWD = resolve(DATA_DIR, "workspace");
process.env.CLAUDE_CONFIG_DIR ??= resolve(DATA_DIR, "claude");
mkdirSync(CWD, { recursive: true });

/** Running turns by session id, for cancel and to refuse a second message while one is open. */
const running = new Map<string, AbortController>();

const server = createServer((request, response) => {
  handle(request, response).catch((error) => {
    console.error(error);
    if (!response.headersSent) fail(response, 500, "internal_error", "Internal error");
    else response.end();
  });
});

async function handle(request: IncomingMessage, response: ServerResponse) {
  const { pathname } = new URL(request.url ?? "/", "http://localhost");
  if (request.method === "GET" && pathname === "/agent/v1/health") {
    return json(response, 200, { ok: true, status: "ready" });
  }
  if (!pathname.startsWith("/agent/v1/")) return fail(response, 404, "not_found", "Not found");
  if (!authorized(request, API_TOKEN)) {
    response.setHeader("www-authenticate", "Bearer");
    return fail(response, 401, "unauthorized", "Missing or invalid bearer token");
  }

  if (request.method === "POST" && pathname === "/agent/v1/chat") return chat(request, response);

  const cancel = /^\/agent\/v1\/session\/([^/]+)\/cancel$/.exec(pathname);
  if (request.method === "POST" && cancel) {
    const turn = running.get(cancel[1]);
    turn?.abort();
    return json(response, 200, { ok: true, cancelled: turn !== undefined });
  }

  return fail(response, 404, "not_found", "Not found");
}

async function chat(request: IncomingMessage, response: ServerResponse) {
  const body = await readJson(request);
  const message = typeof body?.message === "string" ? body.message.trim() : "";
  if (!message) return fail(response, 400, "invalid_request", "message is required");
  const given = body?.sessionId;
  if (given !== undefined && given !== null && (typeof given !== "string" || !SESSION_ID.test(given))) {
    return fail(response, 400, "invalid_request", "sessionId must be a session id returned by this agent");
  }

  const resume = typeof given === "string";
  const sessionId = resume ? given : randomUUID();
  if (running.has(sessionId)) {
    return fail(response, 409, "session_busy", "The session is still answering the previous message");
  }
  const abortController = new AbortController();
  running.set(sessionId, abortController);
  try {
    if (resume && !(await getSessionInfo(sessionId, { dir: CWD }))) {
      return fail(response, 404, "session_not_found", "No such session");
    }
    // A caller that hangs up cancels its turn
    response.on("close", () => {
      if (!response.writableFinished) abortController.abort();
    });
    response.writeHead(200, {
      "content-type": "application/x-ndjson; charset=utf-8",
      "cache-control": "no-cache",
      "x-accel-buffering": "no",
    });
    const emit = (event: ChatEvent) => response.write(`${JSON.stringify(event)}\n`);
    await runTurn({ message, sessionId, resume, cwd: CWD, abortController }, emit);
  } finally {
    running.delete(sessionId);
    response.end();
  }
}

async function readJson(request: IncomingMessage): Promise<Record<string, unknown> | undefined> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) return undefined;
    chunks.push(chunk);
  }
  try {
    const value = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    return value && typeof value === "object" ? value : undefined;
  } catch {
    return undefined;
  }
}

function json(response: ServerResponse, status: number, body: unknown) {
  response.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify(body));
}

/** Error bodies look like {"ok":false,"code":"session_busy","error":"..."}. */
function fail(response: ServerResponse, status: number, code: string, error: string) {
  json(response, status, { ok: false, code, error });
}

server.listen(PORT, HOST, () => console.log(`agent listening on http://${HOST}:${PORT}`));

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    for (const turn of running.values()) turn.abort();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  });
}
