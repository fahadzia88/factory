import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, test } from "node:test";
import { askQuestions, questionsSchema } from "./ask-questions.ts";

type Call = { method: string; url: URL; headers: Headers; body: unknown };

const realFetch = globalThis.fetch;
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

const option = (label: string, description = "Why it fits.") => ({ label, description });
const question = (extra: Record<string, unknown> = {}) => ({
  header: "Token",
  question: "Which token does the API use?",
  options: [option("Keep GIT_TOKEN"), option("New token")],
  ...extra,
});

beforeEach(() => {
  calls = [];
  process.env.API_URL = "http://api.test:8080";
  process.env.API_TOKEN = "test-token";
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

describe("questionsSchema", () => {
  test("accepts a minimal set and defaults multiSelect", () => {
    const parsed = questionsSchema.parse([question()]);
    assert.equal(parsed[0].multiSelect, false);
  });

  test("rejects sets that break the rules", () => {
    const bad = [
      [],
      Array.from({ length: 5 }, () => question()),
      [question({ header: "x".repeat(13) })],
      [question({ options: [option("A")] })],
      [question({ options: ["A", "B", "C", "D", "E"].map((l) => option(l)) })],
      [question({ options: [option("A"), option("a")] })],
      [question({ options: [option("A"), option("Other")] })],
      [question({ options: [option("A"), option("B", "x".repeat(201))] })],
    ];
    for (const set of bad) assert.equal(questionsSchema.safeParse(set).success, false, JSON.stringify(set).slice(0, 80));
  });
});

describe("ask_questions", () => {
  test("posts the questions and tells the agent to wait", async () => {
    stubFetch(201, { id: 7 });
    const questions = questionsSchema.parse([question()]);

    const result = await askQuestions.handler({ projectId: 12, featureId: 21, questions }, undefined);

    assert.notEqual(result.isError, true);
    assert.match((result.content[0] as { text: string }).text, /^Shown to the user\. End your turn now/);
    assert.equal(calls[0].method, "POST");
    assert.equal(calls[0].url.pathname, "/api/projects/12/features/21/questions");
    assert.equal(calls[0].headers.get("authorization"), "Bearer test-token");
    assert.deepEqual(calls[0].body, { questions });
  });

  test("an API refusal is a tool error with its detail", async () => {
    stubFetch(400, { detail: "Question 1: give 2 to 4 options.", code: "invalid_questions" });

    const result = await askQuestions.handler({ projectId: 12, featureId: 21, questions: questionsSchema.parse([question()]) }, undefined);

    assert.equal(result.isError, true);
    assert.equal((result.content[0] as { text: string }).text, "Couldn't show the questions: Question 1: give 2 to 4 options.");
  });
});

describe("instructions", () => {
  test("say that only the Approve answer approves a document", () => {
    const instructions = readFileSync(new URL("../instructions.md", import.meta.url), "utf8").replace(/\s+/g, " ");
    assert.ok(instructions.includes('labelled exactly "Approve"'));
    assert.ok(instructions.includes('Choosing "Approve" is the user\'s explicit yes for that document only.'));
  });
});
