import { createHash, timingSafeEqual } from "node:crypto";
import { eveChannel } from "eve/channels/eve";
import {
  type AuthFn,
  extractBearerToken,
  localDev,
  withAuthChallenges,
} from "eve/channels/auth";

const sha256 = (value: string) => createHash("sha256").update(value).digest();

/**
 * Accepts `Authorization: Bearer <API_TOKEN>`, the same static token the API checks,
 * so the app (through its /eve proxy) and the API can both call the agent.
 */
function apiToken(): AuthFn<Request> {
  return withAuthChallenges(
    (request) => {
      const expected = process.env.API_TOKEN;
      const token = extractBearerToken(request.headers.get("authorization"));
      if (!expected || !token) return null;
      if (!timingSafeEqual(sha256(token), sha256(expected))) return null;
      return {
        authenticator: "api-token",
        principalId: "api-client",
        principalType: "service",
        attributes: {},
      };
    },
    [{ scheme: "Bearer" }],
  );
}

export default eveChannel({
  auth: [
    apiToken(),
    // Open on localhost for `eve dev` and the REPL; ignored in production
    // and for callers from other containers.
    localDev(),
  ],
});
