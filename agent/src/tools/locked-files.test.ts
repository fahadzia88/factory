import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, test } from "node:test";
import { globToRegExp, isLocked, readLockedPatterns } from "../locked-files.ts";

let worktree: string;

beforeEach(async () => {
  worktree = await mkdtemp(path.join(os.tmpdir(), "locked-"));
  await mkdir(path.join(worktree, ".claude"));
});

afterEach(async () => {
  await rm(worktree, { recursive: true, force: true });
});

describe("globToRegExp", () => {
  test("* matches anything, slashes included; ? one character; the rest literally", () => {
    assert.ok(globToRegExp("api/gradle/*").test("api/gradle/wrapper/x.jar"));
    assert.ok(globToRegExp("*.ts").test("agent/src/chat.ts"));
    assert.ok(globToRegExp("a?c").test("abc"));
    assert.ok(!globToRegExp("a?c").test("ac"));
    assert.ok(!globToRegExp("agent/src/chat.ts").test("agent/src/chatXts"));
  });
});

describe("locked patterns", () => {
  test("only locked lines count, comments and blanks are skipped, and overrides unlock", async () => {
    await writeFile(
      path.join(worktree, ".claude/base-files"),
      "# comment\n\nlocked agent/src/chat.ts\nlocked app/app/components/ui/*\nshared agent/src/agent.ts\nlocked agent/README.md\n",
    );
    await writeFile(path.join(worktree, ".claude/base-overrides"), "agent/README.md");

    assert.equal((await readLockedPatterns(worktree)).length, 2);
    assert.ok(await isLocked(worktree, "agent/src/chat.ts"));
    assert.ok(await isLocked(worktree, "app/app/components/ui/button.tsx"));
    assert.ok(!(await isLocked(worktree, "agent/src/agent.ts")));
    assert.ok(!(await isLocked(worktree, "agent/README.md")));
    assert.ok(!(await isLocked(worktree, "api/src/Foo.kt")));
  });

  test("no base-files means nothing is locked", async () => {
    assert.ok(!(await isLocked(worktree, "agent/src/chat.ts")));
  });
});
