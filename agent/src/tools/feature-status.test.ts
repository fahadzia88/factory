import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, test } from "node:test";
import { featureAddStep } from "./feature-add-step.ts";
import { featureSetStatus } from "./feature-set-status.ts";
import { featureUpdateStep } from "./feature-update-step.ts";

type Result = Awaited<ReturnType<typeof featureSetStatus.handler>>;
type Call = { method: string; url: URL; headers: Headers; body: unknown };

const realFetch = globalThis.fetch;
const IDS = { projectId: 12, featureId: 21 };
let calls: Call[] = [];

function stubFetch(status: number, body: unknown) {
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({
      method: init?.method ?? "GET",
      url: new URL(input instanceof Request ? input.url : input),
      headers: new Headers(init?.headers),
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" },
    });
  }) as typeof fetch;
}

const step = { id: 5, position: 1, type: "spec", typeTitle: "Task spec", title: "Spec", documentPath: null, status: "draft", states: ["draft", "approved"] };

function text(result: Result): string {
  return (result.content[0] as { text: string }).text;
}

beforeEach(() => {
  calls = [];
  process.env.API_URL = "http://api.test:8080";
  process.env.API_TOKEN = "test-token";
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

describe("feature_set_status", () => {
  test("PUTs the status with the bearer token", async () => {
    stubFetch(200, { status: "breakdown", updatedAt: "2026-11-06T11:00:00Z" });

    const result = await featureSetStatus.handler({ ...IDS, status: "breakdown" }, undefined);

    assert.deepEqual(JSON.parse(text(result)), { status: "breakdown", updatedAt: "2026-11-06T11:00:00Z" });
    assert.equal(calls[0].method, "PUT");
    assert.equal(calls[0].url.pathname, "/api/projects/12/features/21/status");
    assert.equal(calls[0].headers.get("authorization"), "Bearer test-token");
    assert.deepEqual(calls[0].body, { status: "breakdown" });
  });

  test("an unlisted state is an error with the API's detail", async () => {
    stubFetch(409, { detail: "\"shipped\" isn't one of this workflow's states: requirements, done.", code: "invalid_status" });

    const result = await featureSetStatus.handler({ ...IDS, status: "shipped" }, undefined);

    assert.equal(result.isError, true);
    assert.equal(text(result), "Couldn't set the status: \"shipped\" isn't one of this workflow's states: requirements, done.");
  });
});

describe("feature_add_step", () => {
  test("POSTs the step, leaving out a missing documentPath", async () => {
    stubFetch(201, step);

    await featureAddStep.handler({ ...IDS, type: "spec", title: "Spec", documentPath: "feature/004-features/tasks/01/spec.md" }, undefined);
    await featureAddStep.handler({ ...IDS, type: "spec", title: "Spec", documentPath: undefined }, undefined);

    assert.equal(calls[0].url.pathname, "/api/projects/12/features/21/steps");
    assert.deepEqual(calls[0].body, { type: "spec", title: "Spec", documentPath: "feature/004-features/tasks/01/spec.md" });
    assert.deepEqual(calls[1].body, { type: "spec", title: "Spec" });
  });

  test("an unknown step type is an error", async () => {
    stubFetch(409, { detail: "\"plan\" isn't a step in this feature's workflow: requirements, spec.", code: "invalid_step_type" });

    const result = await featureAddStep.handler({ ...IDS, type: "plan", title: "Plan", documentPath: undefined }, undefined);

    assert.equal(result.isError, true);
    assert.match(text(result), /isn't a step in this feature's workflow/);
  });
});

describe("feature_update_step", () => {
  test("PATCHes only the given fields", async () => {
    stubFetch(200, { ...step, status: "approved" });

    await featureUpdateStep.handler({ ...IDS, stepId: 5, status: "approved", title: undefined, documentPath: undefined }, undefined);

    assert.equal(calls[0].method, "PATCH");
    assert.equal(calls[0].url.pathname, "/api/projects/12/features/21/steps/5");
    assert.deepEqual(calls[0].body, { status: "approved" });
  });

  test("no fields is an error without a request; an unlisted state surfaces", async () => {
    stubFetch(409, { detail: "\"done\" isn't one of this workflow's states: draft, approved.", code: "invalid_status" });

    const empty = await featureUpdateStep.handler({ ...IDS, stepId: 5, status: undefined, title: undefined, documentPath: undefined }, undefined);
    assert.equal(empty.isError, true);
    assert.equal(calls.length, 0);

    const refused = await featureUpdateStep.handler({ ...IDS, stepId: 5, status: "done", title: undefined, documentPath: undefined }, undefined);
    assert.equal(refused.isError, true);
    assert.match(text(refused), /draft, approved/);
  });
});
