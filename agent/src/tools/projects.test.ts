import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, test } from "node:test";
import { getProject } from "./get-project.ts";
import { listProjects } from "./list-projects.ts";

type Call = { url: URL; headers: Headers };
type Reply = { status: number; body: unknown };

const realFetch = globalThis.fetch;
/** The SDK types every input key as present, possibly undefined. */
const NO_FILTERS = { q: undefined, status: undefined, includeArchived: undefined, sort: undefined, direction: undefined };
let calls: Call[] = [];

/** Replaces fetch with one that records requests and answers from [reply]. */
function stubFetch(reply: (url: URL) => Reply) {
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(input instanceof Request ? input.url : input);
    calls.push({ url, headers: new Headers(init?.headers) });
    const { status, body } = reply(url);
    return new Response(typeof body === "string" ? body : JSON.stringify(body), {
      status,
      headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" },
    });
  }) as typeof fetch;
}

const project = (id: number, extra: Record<string, unknown> = {}) => ({
  id,
  name: `Project ${id}`,
  repositoryUrl: `https://github.com/acme/p${id}`,
  description: null,
  status: "active",
  archived: false,
  repositoryVerified: true,
  createdAt: "2026-11-04T10:15:30Z",
  updatedAt: "2026-11-04T10:15:30Z",
  ...extra,
});

/** The JSON a successful tool call returned. */
function json(result: Awaited<ReturnType<typeof listProjects.handler>>) {
  assert.notEqual(result.isError, true);
  const [block] = result.content;
  assert.equal(block.type, "text");
  return JSON.parse((block as { text: string }).text);
}

beforeEach(() => {
  calls = [];
  process.env.API_URL = "http://api.test:8080";
  process.env.API_TOKEN = "test-token";
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

describe("list_projects", () => {
  test("includes archived projects by default and reads every page", async () => {
    stubFetch((url) => {
      const page = Number(url.searchParams.get("page"));
      const items = page === 3 ? [project(5, { archived: true })] : [project(page * 2 - 1), project(page * 2)];
      return { status: 200, body: { items, page, size: 100, totalItems: 5, totalPages: 3 } };
    });

    const result = json(await listProjects.handler(NO_FILTERS, undefined));

    assert.equal(calls.length, 3);
    const first = calls[0];
    assert.equal(first.url.origin + first.url.pathname, "http://api.test:8080/api/projects");
    assert.equal(first.url.searchParams.get("includeArchived"), "true");
    assert.equal(first.url.searchParams.get("page"), "1");
    assert.equal(first.url.searchParams.get("size"), "100");
    assert.equal(first.headers.get("authorization"), "Bearer test-token");
    assert.deepEqual(
      result.projects.map((p: { id: number }) => p.id),
      [1, 2, 3, 4, 5],
    );
    assert.equal(result.projects[4].archived, true);
    assert.equal(result.totalItems, 5);
    assert.equal(result.truncated, false);
  });

  test("passes filters and sort through", async () => {
    stubFetch(() => ({ status: 200, body: { items: [], page: 1, size: 100, totalItems: 0, totalPages: 0 } }));

    await listProjects.handler(
      { q: "fac", status: "maintenance", includeArchived: false, sort: "name", direction: "desc" },
      undefined,
    );

    const params = calls[0].url.searchParams;
    assert.equal(params.get("q"), "fac");
    assert.equal(params.get("status"), "maintenance");
    assert.equal(params.get("includeArchived"), "false");
    assert.equal(params.get("sort"), "name");
    assert.equal(params.get("direction"), "desc");
    assert.equal(calls.length, 1);
  });

  test("stops after 20 pages", async () => {
    stubFetch((url) => ({
      status: 200,
      body: { items: [project(Number(url.searchParams.get("page")))], page: 1, size: 100, totalItems: 5000, totalPages: 50 },
    }));

    const result = json(await listProjects.handler(NO_FILTERS, undefined));

    assert.equal(calls.length, 20);
    assert.equal(result.projects.length, 20);
    assert.equal(result.truncated, true);
  });

  test("reports an API failure as a tool error instead of throwing", async () => {
    stubFetch(() => ({ status: 502, body: { status: 502, detail: "The server is unavailable." } }));

    const result = await listProjects.handler(NO_FILTERS, undefined);

    assert.equal(result.isError, true);
    assert.match((result.content[0] as { text: string }).text, /The server is unavailable\./);
    assert.doesNotMatch((result.content[0] as { text: string }).text, /test-token/);
  });
});

describe("get_project", () => {
  test("returns the stored fields unchanged", async () => {
    const stored = project(42, { name: "Factory", status: "maintenance", repositoryVerified: false });
    stubFetch(() => ({ status: 200, body: stored }));

    const result = json(await getProject.handler({ id: 42 }, undefined));

    assert.equal(calls[0].url.pathname, "/api/projects/42");
    assert.deepEqual(result, stored);
  });

  test("answers found: false for an unknown id, not as an error", async () => {
    stubFetch(() => ({ status: 404, body: { status: 404, detail: "Project not found." } }));

    const result = json(await getProject.handler({ id: 7 }, undefined));

    assert.deepEqual(result, { found: false, id: 7 });
  });
});
