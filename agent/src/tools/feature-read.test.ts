import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, test } from "node:test";
import type { Feature } from "../feature-api.ts";
import { featureGet } from "./feature-get.ts";
import { featureListFiles } from "./feature-list-files.ts";
import { featureReadFile } from "./feature-read-file.ts";
import { featureReadSkill } from "./feature-read-skill.ts";

type Call = { url: URL; headers: Headers };
type Result = Awaited<ReturnType<typeof featureGet.handler>>;

const realFetch = globalThis.fetch;
const IDS = { projectId: 12, featureId: 21 };
let calls: Call[] = [];
let parent: string;
let worktree: string;

/** Answers every API call with [body] and [status]. */
function stubFetch(status: number, body: unknown) {
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: new URL(input instanceof Request ? input.url : input), headers: new Headers(init?.headers) });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" },
    });
  }) as typeof fetch;
}

function feature(extra: Partial<Feature> = {}): Feature {
  return {
    id: 21,
    projectId: 12,
    number: 4,
    code: "004-features",
    name: "Features",
    slug: "features",
    status: "requirements",
    branch: "feat/004-features/00-docs",
    folder: "feature/004-features",
    worktree: { id: 9, path: worktree, missing: false },
    workflow: {
      featureStates: ["requirements", "done"],
      steps: [
        { key: "requirements", title: "Requirements", skill: "feature-requirements", states: ["draft", "approved"] },
        { key: "spec", title: "Task spec", skill: "feature-task-spec", states: ["draft", "approved"] },
      ],
    },
    steps: [],
    createdAt: "2026-11-06T10:00:00Z",
    updatedAt: "2026-11-06T10:00:00Z",
    ...extra,
  };
}

function text(result: Result): string {
  const [block] = result.content;
  assert.equal(block.type, "text");
  return (block as { text: string }).text;
}

function ok(result: Result): string {
  assert.notEqual(result.isError, true, text(result));
  return text(result);
}

function failed(result: Result): string {
  assert.equal(result.isError, true, text(result));
  return text(result);
}

beforeEach(async () => {
  calls = [];
  process.env.API_URL = "http://api.test:8080";
  process.env.API_TOKEN = "test-token";
  parent = await mkdtemp(path.join(os.tmpdir(), "feature-read-"));
  worktree = path.join(parent, "worktree");
  await mkdir(path.join(worktree, ".claude/skills/feature-requirements"), { recursive: true });
  await mkdir(path.join(worktree, "feature/004-features"), { recursive: true });
  await writeFile(path.join(worktree, ".git"), "gitdir: x\n");
  await writeFile(path.join(worktree, ".claude/skills/feature-requirements/SKILL.md"), "# This project's requirements skill\n");
  await writeFile(path.join(worktree, "feature/004-features/requirements.md"), "# Features\n");
  await mkdir(path.join(parent, "outside"));
  await writeFile(path.join(parent, "outside/x"), "secret\n");
});

afterEach(async () => {
  globalThis.fetch = realFetch;
  await rm(parent, { recursive: true, force: true });
});

describe("feature_get", () => {
  test("loads the feature with the bearer token", async () => {
    stubFetch(200, feature());

    const body = JSON.parse(ok(await featureGet.handler(IDS, undefined)));

    assert.equal(body.code, "004-features");
    assert.equal(calls[0].url.pathname, "/api/projects/12/features/21");
    assert.equal(calls[0].headers.get("authorization"), "Bearer test-token");
  });

  test("an unknown feature is an error with the problem's detail", async () => {
    stubFetch(404, { status: 404, detail: "Feature not found.", code: "feature_not_found" });

    assert.equal(failed(await featureGet.handler(IDS, undefined)), "Couldn't load feature 21: Feature not found.");
  });
});

describe("feature_read_skill", () => {
  test("reads the step's skill from the feature's worktree", async () => {
    stubFetch(200, feature());

    const skill = ok(await featureReadSkill.handler({ ...IDS, step: "requirements" }, undefined));

    assert.equal(skill, "# Skill feature-requirements (from feat/004-features/00-docs)\n\n# This project's requirements skill\n");
  });

  test("an unknown step lists the keys, and a missing skill file says so", async () => {
    stubFetch(200, feature());

    assert.match(failed(await featureReadSkill.handler({ ...IDS, step: "plan" }, undefined)), /Unknown step plan\. Steps: requirements, spec\./);
    assert.match(
      failed(await featureReadSkill.handler({ ...IDS, step: "spec" }, undefined)),
      /\.claude\/skills\/feature-task-spec\/SKILL\.md isn't in the feature's worktree\./,
    );
  });

  test("a feature without a worktree says it's missing", async () => {
    stubFetch(200, feature({ worktree: null }));

    assert.match(
      failed(await featureReadSkill.handler({ ...IDS, step: "requirements" }, undefined)),
      /The worktree for feat\/004-features\/00-docs is missing\. Check it out again on the project page\./,
    );
  });
});

describe("feature_list_files", () => {
  test("lists files from the root, never .git", async () => {
    stubFetch(200, feature());

    const body = JSON.parse(ok(await featureListFiles.handler({ ...IDS, dir: undefined }, undefined)));

    assert.deepEqual(body, {
      files: [".claude/skills/feature-requirements/SKILL.md", "feature/004-features/requirements.md"],
      truncated: false,
    });
  });

  test("caps at 500 files", async () => {
    stubFetch(200, feature());
    const many = path.join(worktree, "many");
    await mkdir(many);
    await Promise.all(Array.from({ length: 501 }, (_, i) => writeFile(path.join(many, `f${i}.txt`), "")));

    const body = JSON.parse(ok(await featureListFiles.handler({ ...IDS, dir: "many" }, undefined)));

    assert.equal(body.files.length, 500);
    assert.equal(body.truncated, true);
  });
});

describe("feature_read_file", () => {
  test("reads a file in the worktree", async () => {
    stubFetch(200, feature());

    assert.equal(ok(await featureReadFile.handler({ ...IDS, path: "feature/004-features/requirements.md" }, undefined)), "# Features\n");
  });

  test("refuses paths outside the worktree and files over 200 KB", async () => {
    stubFetch(200, feature());
    await writeFile(path.join(worktree, "big.txt"), "x".repeat(201 * 1024));

    assert.match(failed(await featureReadFile.handler({ ...IDS, path: "../outside/x" }, undefined)), /outside the feature's worktree/);
    assert.match(failed(await featureReadFile.handler({ ...IDS, path: "big.txt" }, undefined)), /big\.txt is 201 KB; only files up to 200 KB can be read\./);
  });
});
