import { createHash, timingSafeEqual } from "node:crypto";
import type { IncomingMessage } from "node:http";

const sha256 = (value: string) => createHash("sha256").update(value).digest();

/**
 * Accepts `Authorization: Bearer <API_TOKEN>`, the same static token the API checks,
 * so the app (through its /agent proxy) and the API can both call the agent.
 */
export function authorized(request: IncomingMessage, expected: string): boolean {
  const match = /^Bearer\s+(.+)$/i.exec(request.headers.authorization ?? "");
  if (!match) return false;
  return timingSafeEqual(sha256(match[1].trim()), sha256(expected));
}
