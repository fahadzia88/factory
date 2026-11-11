import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, test } from "node:test";
import { featureComplete } from "./feature-complete.ts";
import { featureTasks } from "./feature-tasks.ts";
import { taskOpenPull } from "./task-open-pull.ts";
import { taskRestack } from "./task-restack.ts";
import { taskStart } from "./task-start.ts";

type Result = Awaited<ReturnType<typeof featureTasks.handler>>;
type Call = { method: string; url: URL; headers: Headers; body: string | undefined };

const realFetch = globalThis.fetch;
const IDS = { projectId: 12, featureId: 21 };
let calls: Call[] = [];

function stubFetch(status: number, body: unknown) {
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({
      method: init?.method ?? "GET",
      url: new URL(input instanceof Request ? input.url : input),
      headers: new Headers(init?.headers),
      body: init?.body === undefined ? undefined : String(init.body),
    });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" },
    });
  }) as typeof fetch;
}

const text = (r: Result) => (r.content[0] as { text: string }).text;
const failed = (r: Result) => {
  assert.equal(r.isError, true, text(r));
  return text(r);
};

beforeEach(() => {
  calls = [];
  process.env.API_URL = "http://api.test:8080";
  process.env.API_TOKEN = "test-token";
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

describe("the task flow tools call the API", () => {
  test("each tool's method, path, body and token", async () => {
    stubFetch(200, { number: 1 });

    await featureTasks.handler(IDS, undefined);
    await taskStart.handler({ ...IDS, dir: "03-api-task-records" }, undefined);
    await taskOpenPull.handler({ ...IDS, number: 3, task: "Task records", acs: ["AC-3", "AC-4"] }, undefined);
    await taskOpenPull.handler({ ...IDS, number: 0, task: undefined, acs: undefined }, undefined);
    await taskRestack.handler({ ...IDS, number: 3 }, undefined);
    await featureComplete.handler(IDS, undefined);

    const base = "/api/projects/12/features/21";
    assert.deepEqual(
      calls.map((c) => `${c.method} ${c.url.pathname} ${c.body ?? "-"}`),
      [
        `GET ${base}/tasks -`,
        `POST ${base}/tasks {"dir":"03-api-task-records"}`,
        `POST ${base}/tasks/3/pull {"task":"Task records","acs":["AC-3","AC-4"]}`,
        `POST ${base}/tasks/0/pull -`,
        `POST ${base}/tasks/3/restack -`,
        `POST ${base}/complete -`,
      ],
    );
    assert.ok(calls.every((c) => c.headers.get("authorization") === "Bearer test-token"));
  });

  test("a task pull request needs its name and ACs; nothing is sent without them", async () => {
    stubFetch(201, {});

    assert.match(failed(await taskOpenPull.handler({ ...IDS, number: 3, task: " ", acs: ["AC-3"] }, undefined)), /task must be/);
    assert.match(failed(await taskOpenPull.handler({ ...IDS, number: 3, task: "A", acs: ["ac-3"] }, undefined)), /acs must list/);
    assert.equal(calls.length, 0);
  });
});

describe("API refusals reach the model with their details", () => {
  test("specs_not_approved names the specs", async () => {
    stubFetch(409, { detail: "Every task spec must be approved.", code: "specs_not_approved", unapproved: ["feature/006-x/tasks/02-app-b/spec.md"] });
    const message = failed(await taskStart.handler({ ...IDS, dir: "01-api-a" }, undefined));
    assert.match(message, /\(specs_not_approved\)/);
    assert.match(message, /02-app-b\/spec\.md/);
  });

  test("order refusals show their detail", async () => {
    stubFetch(409, { detail: "Tasks start in order; the next one is 02.", code: "task_out_of_order", expected: 2 });
    assert.match(failed(await taskStart.handler({ ...IDS, dir: "03-agent-c" }, undefined)), /next one is 02.*"expected":2/);
    stubFetch(409, { detail: "Task 01 has no pull request yet.", code: "previous_task_unfinished" });
    assert.match(failed(await taskStart.handler({ ...IDS, dir: "02-app-b" }, undefined)), /Task 01 has no pull request yet\. \(previous_task_unfinished\)/);
  });

  test("restack conflicts list the files; unfinished features list the tasks", async () => {
    stubFetch(409, { detail: "The rebase conflicts.", code: "restack_conflict", files: ["api/src/Foo.kt"] });
    assert.match(failed(await taskRestack.handler({ ...IDS, number: 2 }, undefined)), /"files":\["api\/src\/Foo\.kt"\]/);
    stubFetch(409, { detail: "Not yet.", code: "tasks_unfinished", unfinished: [{ task: 2, reason: "pull request not merged (open)" }] });
    assert.match(failed(await featureComplete.handler(IDS, undefined)), /pull request not merged \(open\)/);
  });

  test("feature_tasks shows a failing check's name and summary; a GitHub error is a tool error, not retried", async () => {
    stubFetch(200, [
      { number: 1, pull: { checks: { state: "failure", runs: [{ name: "test", conclusion: "failure", summary: "FooTest failed" }] } } },
    ]);
    const listed = JSON.parse(text(await featureTasks.handler(IDS, undefined)));
    assert.equal(listed[0].pull.checks.runs[0].name, "test");
    assert.equal(listed[0].pull.checks.runs[0].summary, "FooTest failed");

    calls = [];
    stubFetch(502, { detail: "GitHub couldn't be reached. Try again.", code: "github_error" });
    assert.match(failed(await featureTasks.handler(IDS, undefined)), /GitHub couldn't be reached\. Try again\. \(github_error\)/);
    assert.equal(calls.length, 1);
  });
});

describe("instructions", () => {
  test("cover the implementation flow", () => {
    const instructions = readFileSync(new URL("../instructions.md", import.meta.url), "utf8").replace(/\s+/g, " ");
    for (const phrase of [
      'feature_read_skill` (`step: "implementation"`)',
      "One task per turn",
      "Never run or ask to run tests or builds",
      "`needsRestack`",
      "`stopped`",
      "`skipped`",
      'call `feature_complete` only on "Confirmed"',
    ]) {
      assert.ok(instructions.includes(phrase), phrase);
    }
    assert.ok(!instructions.includes("isn't available yet"));
  });
});
