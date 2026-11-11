import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, test } from "node:test";
import type { TaskRow } from "../task-api.ts";
import { taskCommit } from "./task-commit.ts";
import { taskEditFile } from "./task-edit-file.ts";
import { taskListFiles } from "./task-list-files.ts";
import { taskReadFile } from "./task-read-file.ts";
import { taskWriteFile } from "./task-write-file.ts";

type Result = Awaited<ReturnType<typeof taskWriteFile.handler>>;
type Call = { method: string; url: URL; body: unknown };

const realFetch = globalThis.fetch;
const IDS = { projectId: 12, featureId: 21 };
const BRANCH = "feat/006-x/01-api-a";
let parent: string;
let remote: string;
let worktree: string;
let other: string;
let calls: Call[] = [];
let pushReply: { status: number; body: unknown } | undefined;
let rows: TaskRow[];

function git(dir: string, ...args: string[]): string {
  return execFileSync("git", ["-c", "user.name=Seed", "-c", "user.email=seed@example.com", ...args], { cwd: dir, encoding: "utf8" }).trim();
}

function row(number: number, extra: Partial<TaskRow> = {}): TaskRow {
  return {
    number,
    layer: number === 0 ? "docs" : "api",
    slug: number === 0 ? "docs" : `t${number}`,
    branch: number === 1 ? BRANCH : `feat/006-x/0${number}-api-t${number}`,
    baseBranch: "main",
    specPath: null,
    stepId: null,
    worktree: { id: 9 + number, path: number === 1 ? worktree : other, missing: false },
    pull: null,
    pullError: null,
    needsRestack: false,
    createdAt: "2026-11-10T10:00:00Z",
    updatedAt: "2026-11-10T10:00:00Z",
    ...extra,
  };
}

function pull(state: "open" | "closed" | "merged") {
  return { number: 11, title: "t", state, draft: false, htmlUrl: "x", baseRef: "main", headSha: "s", mergedAt: null, checks: { state: "none" as const, runs: [] } };
}

function stubApi() {
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(input instanceof Request ? input.url : input);
    const method = init?.method ?? "GET";
    calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (method === "POST" && url.pathname === "/api/projects/12/checkout/worktrees/10/push") {
      if (pushReply) return json(pushReply.status, pushReply.body);
      git(worktree, "push", "-u", "origin", `${BRANCH}:${BRANCH}`);
      return json(200, { branch: BRANCH, head: git(worktree, "rev-parse", "--short", "HEAD") });
    }
    return json(200, rows);
  }) as typeof fetch;
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" } });
}

const text = (r: Result) => (r.content[0] as { text: string }).text;
const ok = (r: Result) => {
  assert.notEqual(r.isError, true, text(r));
  return text(r);
};
const failed = (r: Result) => {
  assert.equal(r.isError, true, text(r));
  return text(r);
};
const pushes = () => calls.filter((c) => c.method === "POST");

beforeEach(async () => {
  calls = [];
  pushReply = undefined;
  process.env.API_URL = "http://api.test:8080";
  process.env.API_TOKEN = "test-token";
  process.env.GIT_USER_NAME = "Agent Test";
  process.env.GIT_USER_EMAIL = "agent@example.com";
  parent = await mkdtemp(path.join(os.tmpdir(), "task-files-"));
  remote = path.join(parent, "remote.git");
  worktree = path.join(parent, "wt");
  other = path.join(parent, "other");
  git(parent, "init", "--bare", "--initial-branch=main", remote);
  git(parent, "clone", remote, worktree);
  git(worktree, "checkout", "-b", "main");
  await mkdir(path.join(worktree, ".claude"), { recursive: true });
  await mkdir(path.join(worktree, "agent/src"), { recursive: true });
  await writeFile(path.join(worktree, ".claude/base-files"), "locked agent/src/chat.ts\nshared agent/src/agent.ts\n");
  await writeFile(path.join(worktree, "agent/src/chat.ts"), "// locked\n");
  await writeFile(path.join(worktree, "README.md"), "# Readme\n");
  git(worktree, "add", "-A");
  git(worktree, "commit", "-m", "Seed");
  git(worktree, "push", "origin", "main");
  git(worktree, "checkout", "-b", BRANCH);
  await mkdir(path.join(other, ".git"), { recursive: true });
  await mkdir(path.join(parent, "outside"));
  rows = [row(0), row(1)];
  stubApi();
});

afterEach(async () => {
  globalThis.fetch = realFetch;
  await rm(parent, { recursive: true, force: true });
});

describe("task file tools", () => {
  test("write, edit, read and list inside the task's worktree", async () => {
    ok(await taskWriteFile.handler({ ...IDS, number: 1, path: "api/src/New.kt", content: "class New\n" }, undefined));
    ok(await taskEditFile.handler({ ...IDS, number: 1, path: "README.md", oldText: "# Readme", newText: "# Readme $&" }, undefined));

    assert.equal(ok(await taskReadFile.handler({ ...IDS, number: 1, path: "api/src/New.kt" }, undefined)), "class New\n");
    assert.equal(await readFile(path.join(worktree, "README.md"), "utf8"), "# Readme $&\n");
    const listed = JSON.parse(ok(await taskListFiles.handler({ ...IDS, number: 1, dir: undefined }, undefined)));
    assert.deepEqual(listed.files, [".claude/base-files", "README.md", "agent/src/chat.ts", "api/src/New.kt"]);
  });

  test("paths outside the worktree, in .git, task 0 and unstarted tasks are refused and nothing is written", async () => {
    await symlink(path.join(parent, "outside"), path.join(worktree, "escape"));
    const before = git(worktree, "status", "--porcelain");
    for (const rel of ["../x", "/tmp/x", ".git/hooks/pre-commit", "escape/x", "../other/x"]) {
      failed(await taskWriteFile.handler({ ...IDS, number: 1, path: rel, content: "pwned\n" }, undefined));
    }
    assert.match(failed(await taskWriteFile.handler({ ...IDS, number: 0 as never, path: "x", content: "x" }, undefined)), /docs branch/);
    assert.match(failed(await taskWriteFile.handler({ ...IDS, number: 3, path: "x", content: "x" }, undefined)), /hasn't been started/);
    assert.equal(git(worktree, "status", "--porcelain"), before);
    assert.equal(existsSync(path.join(parent, "outside/x")), false);
    assert.equal(existsSync(path.join(worktree, ".git/hooks/pre-commit")), false);
  });

  test("locked skeleton files are refused unless overridden; shared ones are allowed", async () => {
    assert.match(
      failed(await taskWriteFile.handler({ ...IDS, number: 1, path: "agent/src/chat.ts", content: "x" }, undefined)),
      /agent\/src\/chat\.ts is a locked skeleton file/,
    );
    ok(await taskWriteFile.handler({ ...IDS, number: 1, path: "agent/src/agent.ts", content: "x" }, undefined));
    await writeFile(path.join(worktree, ".claude/base-overrides"), "agent/src/chat.ts\n");
    ok(await taskWriteFile.handler({ ...IDS, number: 1, path: "agent/src/chat.ts", content: "x" }, undefined));
  });

  test("merged, closed and inactive tasks can't be changed; a lower task with an open PR can", async () => {
    rows = [row(0), row(1, { pull: pull("merged") })];
    assert.match(failed(await taskWriteFile.handler({ ...IDS, number: 1, path: "x", content: "x" }, undefined)), /merged/);
    rows = [row(0), row(1, { pull: pull("closed") })];
    assert.match(failed(await taskWriteFile.handler({ ...IDS, number: 1, path: "x", content: "x" }, undefined)), /closed/);
    rows = [row(0), row(1), row(2)];
    assert.match(failed(await taskWriteFile.handler({ ...IDS, number: 1, path: "x", content: "x" }, undefined)), /task 2 is/);
    rows = [row(0), row(1, { pull: pull("open") }), row(2)];
    ok(await taskWriteFile.handler({ ...IDS, number: 1, path: "x", content: "x" }, undefined));
  });

  test("a missing worktree stops every tool", async () => {
    rows = [row(0), row(1, { worktree: null })];
    const missing = /The worktree for feat\/006-x\/01-api-a is missing/;
    assert.match(failed(await taskListFiles.handler({ ...IDS, number: 1, dir: undefined }, undefined)), missing);
    assert.match(failed(await taskReadFile.handler({ ...IDS, number: 1, path: "README.md" }, undefined)), missing);
    assert.match(failed(await taskWriteFile.handler({ ...IDS, number: 1, path: "x", content: "x" }, undefined)), missing);
    assert.match(failed(await taskEditFile.handler({ ...IDS, number: 1, path: "README.md", oldText: "a", newText: "b" }, undefined)), missing);
    assert.match(failed(await taskCommit.handler({ ...IDS, number: 1, message: "x" }, undefined)), missing);
  });
});

describe("task_commit", () => {
  test("commits everything as the agent and pushes once through the API", async () => {
    await writeFile(path.join(worktree, "README.md"), "# Changed\n");
    await mkdir(path.join(worktree, "api"), { recursive: true });
    await writeFile(path.join(worktree, "api/New.kt"), "class New\n");

    const result = JSON.parse(ok(await taskCommit.handler({ ...IDS, number: 1, message: "006 01-api: a" }, undefined)));

    assert.equal(result.committed, true);
    assert.equal(git(worktree, "show", "--name-only", "--format=", "HEAD").split("\n").sort().join(","), "README.md,api/New.kt");
    assert.equal(git(worktree, "log", "-1", "--format=%an <%ae>|%cn <%ce>"), "Agent Test <agent@example.com>|Agent Test <agent@example.com>");
    assert.equal(pushes().length, 1);
    assert.deepEqual(pushes()[0].body, { force: false });
    assert.equal(git(remote, "rev-parse", "--short", BRANCH), result.sha);
    assert.throws(() => git(worktree, "config", "--local", "--get-regexp", "^user\\."));
  });

  test("nothing to commit says so; the wrong branch is refused", async () => {
    await writeFile(path.join(worktree, "README.md"), "# Changed\n");
    ok(await taskCommit.handler({ ...IDS, number: 1, message: "first" }, undefined));
    calls = [];
    assert.equal(ok(await taskCommit.handler({ ...IDS, number: 1, message: "again" }, undefined)), "Nothing to commit in task 1.");
    assert.equal(pushes().length, 0);

    git(worktree, "checkout", "-b", "something-else");
    await writeFile(path.join(worktree, "README.md"), "# Other\n");
    assert.match(failed(await taskCommit.handler({ ...IDS, number: 1, message: "x" }, undefined)), /something-else checked out, not feat\/006-x\/01-api-a; nothing was committed/);
    assert.equal(git(worktree, "status", "--porcelain"), "M README.md");
  });

  test("a rejected push keeps the commit and a retry pushes it", async () => {
    await writeFile(path.join(worktree, "README.md"), "# Changed\n");
    pushReply = { status: 409, body: { detail: "GitHub rejected the push.", code: "push_rejected" } };

    const sha = (await (async () => {
      const message = failed(await taskCommit.handler({ ...IDS, number: 1, message: "x" }, undefined));
      return message.match(/Committed (\w+) locally/)?.[1];
    })()) as string;
    assert.equal(git(worktree, "rev-parse", "--short", "HEAD"), sha);

    pushReply = undefined;
    const retry = JSON.parse(ok(await taskCommit.handler({ ...IDS, number: 1, message: "retry" }, undefined)));
    assert.equal(retry.committed, false);
    assert.equal(retry.pushed, sha);
  });

  test("no tool runs commands", async () => {
    const { agentOptions } = await import("../agent.ts");
    const names = Object.keys(agentOptions.mcpServers ?? {});
    assert.deepEqual(names, ["app"]);
    for (const tool of [taskListFiles, taskReadFile, taskWriteFile, taskEditFile, taskCommit]) {
      assert.ok(!/bash|exec|run|shell/i.test(tool.name), tool.name);
    }
  });
});
