import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, test } from "node:test";
import { type Feature, requireWorktree, resolveRead, resolveWrite } from "../feature-api.ts";

let parent: string;
let worktree: string;
const FOLDER = "feature/004-features";

beforeEach(async () => {
  parent = await mkdtemp(path.join(os.tmpdir(), "feature-api-"));
  worktree = path.join(parent, "worktree");
  await mkdir(path.join(worktree, FOLDER), { recursive: true });
  await writeFile(path.join(worktree, ".git"), "gitdir: /repos/1/.git/worktrees/x\n");
  await writeFile(path.join(worktree, FOLDER, "requirements.md"), "# Features\n");
  await mkdir(path.join(parent, "outside"));
});

afterEach(async () => {
  await rm(parent, { recursive: true, force: true });
});

describe("resolveRead", () => {
  test("accepts files in the worktree, existing or not", async () => {
    const real = await resolveRead(worktree, `${FOLDER}/requirements.md`);
    assert.ok(real.endsWith(path.join(FOLDER, "requirements.md")));
    assert.ok((await resolveRead(worktree, "new/dir/file.md")).endsWith(path.join("new", "dir", "file.md")));
  });

  test("refuses paths outside the worktree or in .git", async () => {
    await symlink(path.join(parent, "outside"), path.join(worktree, "escape"));
    for (const rel of ["../outside/x", "/etc/passwd", ".git/config", "a/.git/b", "escape", "escape/x", ""]) {
      await assert.rejects(resolveRead(worktree, rel), Error, rel);
    }
  });
});

describe("resolveWrite", () => {
  test("accepts paths in the feature folder", async () => {
    const real = await resolveWrite(worktree, FOLDER, `${FOLDER}/new/spec.md`);
    assert.ok(real.endsWith(path.join(FOLDER, "new", "spec.md")));
  });

  test("refuses anything outside the folder, symlinks out included", async () => {
    await symlink(path.join(parent, "outside"), path.join(worktree, FOLDER, "out"));
    await symlink(path.join(worktree, ".git"), path.join(worktree, FOLDER, "git"));
    for (const rel of ["feature/004-other/x.md", "README.md", `${FOLDER}/out/x.md`, `${FOLDER}/../x.md`, `${FOLDER}/git`]) {
      await assert.rejects(resolveWrite(worktree, FOLDER, rel), Error, rel);
    }
    await assert.rejects(resolveWrite(worktree, FOLDER, "README.md"), {
      message: "README.md is outside feature/004-features/; only the feature folder can be written.",
    });
  });
});

describe("requireWorktree", () => {
  const missing = "The worktree for feat/004-features/00-docs is missing. Check it out again on the project page.";
  const feature = (worktree: Feature["worktree"]) => ({ branch: "feat/004-features/00-docs", worktree }) as Feature;

  test("returns the path of a worktree on disk", () => {
    assert.equal(requireWorktree(feature({ id: 9, path: worktree, missing: false })), worktree);
  });

  test("refuses a worktree that's gone, missing, or has no .git", async () => {
    assert.throws(() => requireWorktree(feature(null)), { message: missing });
    assert.throws(() => requireWorktree(feature({ id: 9, path: worktree, missing: true })), { message: missing });
    await rm(path.join(worktree, ".git"));
    assert.throws(() => requireWorktree(feature({ id: 9, path: worktree, missing: false })), { message: missing });
  });
});
