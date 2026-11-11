import { readFile } from "node:fs/promises";
import path from "node:path";

/** A `.claude/base-files` glob as a regular expression: `*` matches anything (`/` included), `?` one character. */
export function globToRegExp(glob: string): RegExp {
  const pattern = glob
    .split("")
    .map((c) => (c === "*" ? ".*" : c === "?" ? "." : c.replace(/[.+^${}()|[\]\\]/g, "\\$&")))
    .join("");
  return new RegExp(`^${pattern}$`);
}

async function lines(file: string): Promise<string[]> {
  try {
    return (await readFile(file, "utf8")).split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
  } catch {
    return [];
  }
}

/** The worktree's `locked` globs from `.claude/base-files`, minus those the user listed in `.claude/base-overrides`. */
export async function readLockedPatterns(worktree: string): Promise<RegExp[]> {
  const overrides = (await lines(path.join(worktree, ".claude/base-overrides"))).map(globToRegExp);
  const locked = (await lines(path.join(worktree, ".claude/base-files")))
    .map((l) => l.split(/\s+/))
    .filter(([kind, glob]) => kind === "locked" && glob)
    .map(([, glob]) => glob);
  return locked.filter((glob) => !overrides.some((o) => o.test(glob))).map(globToRegExp);
}

/** True when [rel] (posix, from the worktree root) matches a locked pattern and no override. */
export async function isLocked(worktree: string, rel: string): Promise<boolean> {
  const overrides = (await lines(path.join(worktree, ".claude/base-overrides"))).map(globToRegExp);
  if (overrides.some((o) => o.test(rel))) return false;
  return (await readLockedPatterns(worktree)).some((p) => p.test(rel));
}
