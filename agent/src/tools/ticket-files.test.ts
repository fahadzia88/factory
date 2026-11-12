import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, test } from "node:test";
import type { Ticket } from "../ticket-api.ts";
import { ticketEditFile } from "./ticket-edit-file.ts";
import { ticketGet } from "./ticket-get.ts";
import { ticketListFiles } from "./ticket-list-files.ts";
import { ticketReadFile } from "./ticket-read-file.ts";
import { ticketWriteDetails } from "./ticket-write-details.ts";
import { ticketWriteFile } from "./ticket-write-file.ts";

type Result = Awaited<ReturnType<typeof ticketWriteFile.handler>>;

const realFetch = globalThis.fetch;
const IDS = { projectId: 12, ticketId: 31 };
const FOLDER = "feature/bugs/3-login-fails";
const TEMPLATE = "# #3 Login fails\n\n## Findings\n\nNot investigated yet.\n";
let parent: string;
let worktree: string;
let outside: string;
let ticket: Ticket;
let requests: string[] = [];

function makeTicket(extra: Partial<Ticket> = {}): Ticket {
  return {
    id: 31,
    projectId: 12,
    number: 3,
    type: "bug",
    title: "Login fails",
    description: null,
    priority: "medium",
    status: "in_progress",
    statusDescription: "Someone is working on it.",
    allowedStatuses: ["in_review", "closed"],
    pullRequestUrl: null,
    pullNumber: null,
    workspace: {
      state: "ready",
      error: null,
      branch: "bugs/3-login-fails",
      folder: FOLDER,
      worktree: { id: 9, path: worktree, missing: false },
    },
    mode: "investigator",
    approvedAt: null,
    hasFindings: false,
    createdAt: "2026-11-12T10:00:00Z",
    updatedAt: "2026-11-12T10:00:00Z",
    ...extra,
  };
}

function stubApi() {
  globalThis.fetch = (async (input: string | URL | Request) => {
    const url = new URL(input instanceof Request ? input.url : input);
    requests.push(url.pathname);
    return new Response(JSON.stringify(ticket), { status: 200, headers: { "content-type": "application/json" } });
  }) as typeof fetch;
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
const write = (rel: string, content = "x\n") => ticketWriteFile.handler({ ...IDS, path: rel, content }, undefined);

beforeEach(async () => {
  requests = [];
  process.env.API_URL = "http://api.test:8080";
  process.env.API_TOKEN = "test-token";
  parent = await mkdtemp(path.join(os.tmpdir(), "ticket-files-"));
  worktree = path.join(parent, "wt");
  outside = path.join(parent, "outside");
  await mkdir(path.join(worktree, ".git"), { recursive: true });
  await mkdir(path.join(worktree, ".claude"), { recursive: true });
  await mkdir(path.join(worktree, "agent/src"), { recursive: true });
  await mkdir(path.join(worktree, "src"), { recursive: true });
  await mkdir(path.join(worktree, FOLDER), { recursive: true });
  await mkdir(outside);
  await writeFile(path.join(worktree, ".claude/base-files"), "locked agent/src/chat.ts\nshared agent/src/agent.ts\n");
  await writeFile(path.join(worktree, "agent/src/chat.ts"), "// locked\n");
  await writeFile(path.join(worktree, "src/Main.kt"), "fun main() {}\n");
  await writeFile(path.join(worktree, FOLDER, "details.md"), TEMPLATE);
  ticket = makeTicket();
  stubApi();
});

afterEach(async () => {
  globalThis.fetch = realFetch;
  await rm(parent, { recursive: true, force: true });
});

describe("investigator mode", () => {
  test("reads and lists the worktree, and writes only details.md", async () => {
    const listed = JSON.parse(ok(await ticketListFiles.handler({ ...IDS, dir: undefined }, undefined)));
    assert.deepEqual(listed.files, [".claude/base-files", "agent/src/chat.ts", `${FOLDER}/details.md`, "src/Main.kt"]);
    assert.equal(ok(await ticketReadFile.handler({ ...IDS, path: "src/Main.kt" }, undefined)), "fun main() {}\n");

    const written = JSON.parse(ok(await ticketWriteDetails.handler({ ...IDS, content: "# #3 Login fails\n\nThe cookie is dropped.\n" }, undefined)));

    assert.deepEqual(written, { path: `${FOLDER}/details.md`, bytes: 41 });
    assert.equal(await readFile(path.join(worktree, FOLDER, "details.md"), "utf8"), "# #3 Login fails\n\nThe cookie is dropped.\n");
  });

  test("code writes and edits are refused with a message that points at Approve", async () => {
    assert.match(failed(await write("src/New.kt")), /investigator mode: only details\.md can be written/);
    assert.match(
      failed(await ticketEditFile.handler({ ...IDS, path: "src/Main.kt", oldText: "main", newText: "start" }, undefined)),
      /Approve/,
    );
    assert.match(failed(await write(`${FOLDER}/details.md`)), /investigator mode/);

    assert.equal(existsSync(path.join(worktree, "src/New.kt")), false);
    assert.equal(await readFile(path.join(worktree, "src/Main.kt"), "utf8"), "fun main() {}\n");
    assert.equal(await readFile(path.join(worktree, FOLDER, "details.md"), "utf8"), TEMPLATE);
  });

  test("the mode is read from the API on every write", async () => {
    failed(await write("src/New.kt"));

    ticket = makeTicket({ mode: "implementer" });
    ok(await write("src/New.kt", "class New\n"));
    ticket = makeTicket({ mode: "investigator" });
    failed(await write("src/Other.kt"));

    assert.equal(await readFile(path.join(worktree, "src/New.kt"), "utf8"), "class New\n");
    assert.equal(existsSync(path.join(worktree, "src/Other.kt")), false);
    assert.ok(requests.length >= 3, "each write asks the API for the ticket");
    assert.ok(requests.every((p) => p === "/api/projects/12/tickets/31"));
  });
});

describe("implementer mode", () => {
  beforeEach(() => {
    ticket = makeTicket({ mode: "implementer", approvedAt: "2026-11-12T11:00:00Z" });
  });

  test("writes and edits files in the worktree", async () => {
    ok(await write("src/New.kt", "class New\n"));
    ok(await ticketEditFile.handler({ ...IDS, path: "src/Main.kt", oldText: "main()", newText: "start() $&" }, undefined));

    assert.equal(await readFile(path.join(worktree, "src/New.kt"), "utf8"), "class New\n");
    assert.equal(await readFile(path.join(worktree, "src/Main.kt"), "utf8"), "fun start() $& {}\n");
  });

  test("an edit needs exactly one match in an existing file", async () => {
    await writeFile(path.join(worktree, "src/Twice.kt"), "a a\n");

    assert.match(failed(await ticketEditFile.handler({ ...IDS, path: "src/Twice.kt", oldText: "a", newText: "b" }, undefined)), /appears 2 times/);
    assert.match(failed(await ticketEditFile.handler({ ...IDS, path: "src/Main.kt", oldText: "zzz", newText: "b" }, undefined)), /isn't in/);
    assert.match(failed(await ticketEditFile.handler({ ...IDS, path: "src/Nope.kt", oldText: "a", newText: "b" }, undefined)), /doesn't exist/);
  });

  test("locked skeleton files are refused unless overridden; shared ones are allowed", async () => {
    assert.match(failed(await write("agent/src/chat.ts")), /agent\/src\/chat\.ts is a locked skeleton file/);
    ok(await write("agent/src/agent.ts"));
    await writeFile(path.join(worktree, ".claude/base-overrides"), "agent/src/chat.ts\n");
    ok(await write("agent/src/chat.ts"));
  });
});

describe("path guards", () => {
  for (const mode of ["investigator", "implementer"] as const) {
    test(`${mode}: paths outside the worktree and in .git are refused and nothing is written`, async () => {
      ticket = makeTicket({ mode });
      await symlink(outside, path.join(worktree, "escape"));
      for (const rel of ["../x", "/tmp/ticket-x", ".git/hooks/pre-commit", "escape/x", "../outside/x"]) {
        failed(await write(rel, "pwned\n"));
        failed(await ticketReadFile.handler({ ...IDS, path: rel }, undefined));
      }

      assert.equal(existsSync(path.join(outside, "x")), false);
      assert.equal(existsSync(path.join(worktree, ".git/hooks/pre-commit")), false);
    });
  }

  test("a path that is a file can't be listed, and a folder can't be read", async () => {
    assert.match(failed(await ticketListFiles.handler({ ...IDS, dir: "src/Main.kt" }, undefined)), /is a file/);
    assert.match(failed(await ticketReadFile.handler({ ...IDS, path: "src" }, undefined)), /isn't a file/);
  });
});

describe("a ticket without a usable workspace", () => {
  test("every file tool says the ticket has no workspace yet, and ticket_get still reports it", async () => {
    ticket = makeTicket({ workspace: { state: "none", error: null, branch: null, folder: null, worktree: null } });

    const refusals = [
      await ticketListFiles.handler({ ...IDS, dir: undefined }, undefined),
      await ticketReadFile.handler({ ...IDS, path: "README.md" }, undefined),
      await ticketWriteDetails.handler({ ...IDS, content: "x" }, undefined),
      await write("src/New.kt"),
      await ticketEditFile.handler({ ...IDS, path: "src/Main.kt", oldText: "a", newText: "b" }, undefined),
    ];
    for (const refusal of refusals) assert.match(failed(refusal), /no workspace yet; it gets one when it first moves to in progress/);

    assert.equal(JSON.parse(ok(await ticketGet.handler({ ...IDS }, undefined))).workspace.state, "none");
  });

  test("a failed workspace is treated the same way", async () => {
    ticket = makeTicket({ workspace: { ...makeTicket().workspace, state: "failed", error: "The push was rejected." } });

    assert.match(failed(await ticketReadFile.handler({ ...IDS, path: "src/Main.kt" }, undefined)), /no workspace yet/);
  });

  test("a missing worktree stops every file tool", async () => {
    const base = makeTicket();
    ticket = makeTicket({ workspace: { ...base.workspace, worktree: { id: 9, path: path.join(parent, "gone"), missing: true } } });
    const missing = /The worktree for bugs\/3-login-fails is missing/;

    assert.match(failed(await ticketListFiles.handler({ ...IDS, dir: undefined }, undefined)), missing);
    assert.match(failed(await ticketReadFile.handler({ ...IDS, path: "src/Main.kt" }, undefined)), missing);
    assert.match(failed(await ticketWriteDetails.handler({ ...IDS, content: "x" }, undefined)), missing);
    assert.match(failed(await write("src/New.kt")), missing);
  });

  test("an API failure is reported as the tool error", async () => {
    globalThis.fetch = (async () =>
      new Response(JSON.stringify({ detail: "Ticket not found.", code: "ticket_not_found" }), {
        status: 404,
        headers: { "content-type": "application/problem+json" },
      })) as typeof fetch;

    assert.match(failed(await ticketGet.handler({ ...IDS }, undefined)), /Ticket not found/);
    assert.match(failed(await write("src/New.kt")), /Ticket not found/);
  });
});

describe("the tool set", () => {
  const tools = [ticketGet, ticketListFiles, ticketReadFile, ticketWriteDetails, ticketWriteFile, ticketEditFile];

  test("has the six ticket tools and nothing that runs commands, commits, pushes or opens pull requests", () => {
    assert.deepEqual(tools.map((t) => t.name), [
      "ticket_get",
      "ticket_list_files",
      "ticket_read_file",
      "ticket_write_details",
      "ticket_write_file",
      "ticket_edit_file",
    ]);
    for (const tool of tools) assert.ok(!/bash|exec|run|shell|commit|push|pull/i.test(tool.name), tool.name);
  });

  test("they are registered in agent.ts, and chat.ts still turns every built-in tool off", () => {
    const agent = readFileSync(new URL("../agent.ts", import.meta.url), "utf8");
    for (const name of ["ticketGet", "ticketListFiles", "ticketReadFile", "ticketWriteDetails", "ticketWriteFile", "ticketEditFile"]) {
      assert.match(agent, new RegExp(`\\b${name},\\n`), name);
    }
    assert.match(readFileSync(new URL("../chat.ts", import.meta.url), "utf8"), /tools:\s*\[\]/);
  });
});
