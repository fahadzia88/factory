import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, test } from "node:test";
import type { Feature } from "../feature-api.ts";
import { featureCommit } from "./feature-commit.ts";
import { featureEditFile } from "./feature-edit-file.ts";
import { featureWriteFile } from "./feature-write-file.ts";

type Result = Awaited<ReturnType<typeof featureWriteFile.handler>>;
type Call = { method: string; url: URL; body: unknown };

const realFetch = globalThis.fetch;
const IDS = { projectId: 12, featureId: 21 };
const FOLDER = "feature/004-features";
const BRANCH = "feat/004-features/00-docs";
let parent: string;
let remote: string;
let worktree: string;
let calls: Call[] = [];
let pushReply: { status: number; body: unknown } | undefined;
let worktreeValue: Feature["worktree"];

function git(dir: string, ...args: string[]): string {
  return execFileSync("git", ["-c", "user.name=Seed", "-c", "user.email=seed@example.com", ...args], { cwd: dir, encoding: "utf8" }).trim();
}

/** Serves the feature, and a push route that pushes the worktree's branch to the bare remote like the API does. */
function stubApi() {
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(input instanceof Request ? input.url : input);
    const method = init?.method ?? "GET";
    calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (method === "POST" && url.pathname === "/api/projects/12/checkout/worktrees/9/push") {
      if (pushReply) return json(pushReply.status, pushReply.body);
      git(worktree, "push", "-u", "origin", `${BRANCH}:${BRANCH}`);
      return json(200, { branch: BRANCH, head: git(worktree, "rev-parse", "--short", "HEAD") });
    }
    return json(200, feature());
  }) as typeof fetch;
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" },
  });
}

function feature(): Feature {
  return {
    id: 21,
    projectId: 12,
    number: 4,
    code: "004-features",
    name: "Features",
    slug: "features",
    status: "requirements",
    branch: BRANCH,
    folder: FOLDER,
    worktree: worktreeValue,
    workflow: { featureStates: ["requirements"], steps: [] },
    steps: [],
    createdAt: "2026-11-06T10:00:00Z",
    updatedAt: "2026-11-06T10:00:00Z",
  };
}

function text(result: Result): string {
  return (result.content[0] as { text: string }).text;
}

function ok(result: Result): string {
  assert.notEqual(result.isError, true, text(result));
  return text(result);
}

function failed(result: Result): string {
  assert.equal(result.isError, true, text(result));
  return text(result);
}

const pushCalls = () => calls.filter((c) => c.method === "POST");

beforeEach(async () => {
  calls = [];
  pushReply = undefined;
  process.env.API_URL = "http://api.test:8080";
  process.env.API_TOKEN = "test-token";
  process.env.GIT_USER_NAME = "Agent Test";
  process.env.GIT_USER_EMAIL = "agent@example.com";
  parent = await mkdtemp(path.join(os.tmpdir(), "feature-write-"));
  remote = path.join(parent, "remote.git");
  worktree = path.join(parent, "wt");
  git(parent, "init", "--bare", "--initial-branch=main", remote);
  git(parent, "clone", remote, worktree);
  git(worktree, "checkout", "-b", "main");
  await mkdir(path.join(worktree, FOLDER), { recursive: true });
  await mkdir(path.join(worktree, ".claude/skills/x"), { recursive: true });
  await writeFile(path.join(worktree, FOLDER, "requirements.md"), "# Features\n\nStatus: draft\n");
  await writeFile(path.join(worktree, ".claude/skills/x/SKILL.md"), "# X\n");
  await writeFile(path.join(worktree, "README.md"), "# Readme\n");
  git(worktree, "add", "-A");
  git(worktree, "commit", "-m", "Seed");
  git(worktree, "push", "origin", "main");
  git(worktree, "checkout", "-b", BRANCH);
  worktreeValue = { id: 9, path: worktree, missing: false };
  stubApi();
});

afterEach(async () => {
  globalThis.fetch = realFetch;
  await rm(parent, { recursive: true, force: true });
});

describe("feature_write_file", () => {
  test("writes a file in the folder, creating its parents", async () => {
    const result = JSON.parse(ok(await featureWriteFile.handler({ ...IDS, path: `${FOLDER}/tasks/01-x/spec.md`, content: "# Spec\n" }, undefined)));

    assert.deepEqual(result, { path: `${FOLDER}/tasks/01-x/spec.md`, bytes: 7 });
    assert.equal(await readFile(path.join(worktree, FOLDER, "tasks/01-x/spec.md"), "utf8"), "# Spec\n");
  });

  test("refuses anything outside the feature folder and changes nothing", async () => {
    await symlink(worktree, path.join(worktree, FOLDER, "root"));
    const before = git(worktree, "status", "--porcelain");
    for (const rel of ["README.md", "feature/004-other/x.md", "../x", "/tmp/x", ".git/hooks/pre-commit", `${FOLDER}/root/README.md`]) {
      failed(await featureWriteFile.handler({ ...IDS, path: rel, content: "pwned\n" }, undefined));
    }
    assert.equal(git(worktree, "status", "--porcelain"), before);
    assert.equal(await readFile(path.join(worktree, "README.md"), "utf8"), "# Readme\n");
    assert.equal(existsSync(path.join(worktree, "feature/004-other/x.md")), false);
    assert.equal(existsSync(path.join(parent, "x")), false);
    assert.equal(existsSync(path.join(worktree, ".git/hooks/pre-commit")), false);
  });
});

describe("feature_edit_file", () => {
  const file = `${FOLDER}/requirements.md`;

  test("replaces one unique occurrence, keeping $ literally", async () => {
    ok(await featureEditFile.handler({ ...IDS, path: file, oldText: "Status: draft", newText: "Status: $& done" }, undefined));

    assert.equal(await readFile(path.join(worktree, file), "utf8"), "# Features\n\nStatus: $& done\n");
  });

  test("refuses missing, repeated and outside text", async () => {
    await writeFile(path.join(worktree, file), "a a\n");

    assert.match(failed(await featureEditFile.handler({ ...IDS, path: file, oldText: "zzz", newText: "b" }, undefined)), /oldText isn't in/);
    assert.match(failed(await featureEditFile.handler({ ...IDS, path: file, oldText: "a", newText: "b" }, undefined)), /appears 2 times/);
    assert.match(
      failed(await featureEditFile.handler({ ...IDS, path: "README.md", oldText: "Readme", newText: "x" }, undefined)),
      /only the feature folder can be written/,
    );
  });
});

describe("feature_commit", () => {
  test("commits only the folder as the agent and pushes through the API", async () => {
    await writeFile(path.join(worktree, FOLDER, "requirements.md"), "# Features v2\n");
    await writeFile(path.join(worktree, "README.md"), "# Stray change\n");

    const result = JSON.parse(ok(await featureCommit.handler({ ...IDS, message: "004-features: requirements draft" }, undefined)));

    assert.equal(result.committed, true);
    assert.equal(result.pushed, result.sha);
    assert.equal(git(worktree, "show", "--name-only", "--format=", "HEAD"), `${FOLDER}/requirements.md`);
    assert.equal(git(worktree, "log", "-1", "--format=%an <%ae>|%cn <%ce>"), "Agent Test <agent@example.com>|Agent Test <agent@example.com>");
    assert.equal(git(worktree, "status", "--porcelain"), "M README.md");
    assert.equal(pushCalls().length, 1);
    assert.deepEqual(pushCalls()[0].body, { force: false });
    assert.equal(git(remote, "rev-parse", "--short", BRANCH), result.sha);
    assert.throws(() => git(worktree, "config", "--local", "--get-regexp", "^user\\."), "no user.* config is written");
  });

  test("with nothing to commit or push, it says so and doesn't push", async () => {
    await writeFile(path.join(worktree, FOLDER, "requirements.md"), "# Features v2\n");
    ok(await featureCommit.handler({ ...IDS, message: "first" }, undefined));
    calls = [];

    assert.equal(ok(await featureCommit.handler({ ...IDS, message: "again" }, undefined)), `Nothing to commit in ${FOLDER}.`);
    assert.equal(pushCalls().length, 0);
  });

  test("a rejected push keeps the commit and a retry pushes it", async () => {
    await writeFile(path.join(worktree, FOLDER, "requirements.md"), "# Features v2\n");
    pushReply = { status: 409, body: { detail: "GitHub rejected the push.", code: "push_rejected" } };

    const message = failed(await featureCommit.handler({ ...IDS, message: "draft" }, undefined));
    const sha = git(worktree, "rev-parse", "--short", "HEAD");
    assert.equal(message, `Committed ${sha} locally, but the push failed: GitHub rejected the push.`);

    pushReply = undefined;
    const retry = JSON.parse(ok(await featureCommit.handler({ ...IDS, message: "retry" }, undefined)));
    assert.equal(retry.committed, false);
    assert.equal(retry.pushed, sha);
    assert.equal(git(remote, "rev-parse", "--short", BRANCH), sha);
  });
});

describe("a missing worktree", () => {
  test("stops every file and commit tool", async () => {
    worktreeValue = null;
    const missing = /The worktree for feat\/004-features\/00-docs is missing\. Check it out again on the project page\./;

    assert.match(failed(await featureWriteFile.handler({ ...IDS, path: `${FOLDER}/x.md`, content: "x" }, undefined)), missing);
    assert.match(failed(await featureEditFile.handler({ ...IDS, path: `${FOLDER}/requirements.md`, oldText: "a", newText: "b" }, undefined)), missing);
    assert.match(failed(await featureCommit.handler({ ...IDS, message: "x" }, undefined)), missing);
  });
});
