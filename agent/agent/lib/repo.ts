/**
 * The agent's own copy of the GitHub repo, kept on the `agent_git` volume at /git:
 *
 *   /git/repo.git            bare repo; `origin` is https://github.com/<GITHUB_REPO>.git
 *   /git/worktrees/<branch>  one worktree per feature branch ("/" in the name becomes "__")
 *
 * Nothing here touches the host checkout or the running agent's source. Network calls send
 * GITHUB_TOKEN as a per-command header (GIT_CONFIG_* env), so the token is never written to disk.
 */
import { existsSync } from "node:fs";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { simpleGit, type SimpleGit } from "simple-git";

export const GIT_ROOT = process.env.AGENT_GIT_ROOT ?? "/git";
const BARE = path.join(GIT_ROOT, "repo.git");
const TREES = path.join(GIT_ROOT, "worktrees");

export function repoSlug(): { owner: string; repo: string } {
  const [owner, repo] = (process.env.GITHUB_REPO ?? "fahadzia88/factory").split("/");
  if (!owner || !repo) throw new Error("GITHUB_REPO must look like owner/repo.");
  return { owner, repo };
}

export function githubToken(): string {
  const token = process.env.GITHUB_TOKEN;
  if (!token) {
    throw new Error(
      "GITHUB_TOKEN is not set. Add a fine-grained token for this repo (Contents and Pull requests: read and write) to the root .env.",
    );
  }
  return token;
}

/** Env for git child processes: no app secrets, plus the GitHub auth header for github.com. */
function gitEnv(extra: Record<string, string> = {}): Record<string, string> {
  const basic = Buffer.from(`x-access-token:${githubToken()}`).toString("base64");
  return {
    PATH: process.env.PATH ?? "/usr/local/bin:/usr/bin:/bin",
    HOME: process.env.HOME ?? "/root",
    GIT_TERMINAL_PROMPT: "0",
    GIT_CONFIG_COUNT: "1",
    GIT_CONFIG_KEY_0: "http.https://github.com/.extraheader",
    GIT_CONFIG_VALUE_0: `Authorization: Basic ${basic}`,
    ...extra,
  };
}

function git(cwd: string = BARE, extraEnv?: Record<string, string>): SimpleGit {
  return simpleGit({
    baseDir: cwd,
    // Only our fixed env is allowed: the GIT_CONFIG_* auth header, and GIT_EDITOR=true for rebase.
    unsafe: { allowUnsafeConfigEnvCount: true, allowUnsafeEditor: Boolean(extraEnv?.GIT_EDITOR) },
  }).env(gitEnv(extraEnv));
}

// One git operation at a time: worktrees share refs and objects with the bare repo.
let queue: Promise<unknown> = Promise.resolve();
export function serial<T>(work: () => Promise<T>): Promise<T> {
  const run = queue.then(work, work);
  queue = run.catch(() => undefined);
  return run;
}

let lastFetch = 0;
async function ensureRepo(): Promise<void> {
  if (!existsSync(path.join(BARE, "HEAD"))) {
    const { owner, repo } = repoSlug();
    await mkdir(BARE, { recursive: true });
    await mkdir(TREES, { recursive: true });
    const g = git(BARE);
    await g.raw(["init", "--bare", "--initial-branch=main"]);
    // GIT_REMOTE_URL is for tests against a local repo; normally it's the GitHub HTTPS URL.
    const url = process.env.GIT_REMOTE_URL ?? `https://github.com/${owner}/${repo}.git`;
    await g.raw(["remote", "add", "origin", url]);
    await g.raw(["config", "user.name", process.env.GIT_USER_NAME ?? "factory agent"]);
    await g.raw([
      "config",
      "user.email",
      process.env.GIT_USER_EMAIL ?? "factory-agent@users.noreply.github.com",
    ]);
    lastFetch = 0;
  }
  if (Date.now() - lastFetch > 15_000) {
    await git(BARE).raw(["fetch", "--prune", "origin"]);
    lastFetch = Date.now();
  }
}

// ── Branch policy ──────────────────────────────────────────────────────────────────────────

export type FeatureBranch = {
  branch: string;
  feature: string; // NNN-slug
  number: string; // NN
  layer: "docs" | "api" | "app" | "agent" | "infra";
  task: string; // NN-layer-slug, or 00-docs
};

const BRANCH_RE =
  /^feat\/(\d{3}-[a-z0-9]+(?:-[a-z0-9]+)*)\/((\d{2})-(?:(docs)|(api|app|agent|infra)-[a-z0-9]+(?:-[a-z0-9]+)*))$/;

export function parseFeatureBranch(branch: string): FeatureBranch {
  const m = BRANCH_RE.exec(branch);
  if (!m) {
    throw new Error(
      `"${branch}" isn't a feature branch. Expected feat/<NNN>-<slug>/00-docs or feat/<NNN>-<slug>/<NN>-<layer>-<slug>. main is never written.`,
    );
  }
  const layer = (m[4] ?? m[5]) as FeatureBranch["layer"];
  if (layer === "docs" && m[3] !== "00") throw new Error("Only task 00 can be the docs branch.");
  return { branch, feature: m[1], task: m[2], number: m[3], layer };
}

export const docsBranch = (feature: string) => `feat/${feature}/00-docs`;

/** The folder a branch may write to: the feature folder on 00-docs, the layer's folder on a task. */
export function writablePrefix(fb: FeatureBranch): string {
  switch (fb.layer) {
    case "docs":
      return `feature/${fb.feature}/`;
    case "app":
    case "agent":
      return `${fb.layer}/`;
    default:
      throw new Error(
        `${fb.layer} tasks are done in Claude Code (the ${fb.layer === "api" ? "api/ session" : "coordinator session"}), not by this agent.`,
      );
  }
}

// ── Paths ──────────────────────────────────────────────────────────────────────────────────

/** A clean repo-relative POSIX path; rejects absolute paths, "..", and anything under .git. */
export function cleanPath(p: string): string {
  const normalized = path.posix.normalize(p.replaceAll("\\", "/").replace(/^\.\/+/, ""));
  if (
    normalized === "." ||
    normalized.startsWith("/") ||
    normalized.split("/").some((s) => s === ".." || s === ".git")
  ) {
    throw new Error(`Invalid path "${p}". Use a path relative to the repo root.`);
  }
  return normalized.replace(/\/$/, "");
}

const treeDir = (branch: string) => path.join(TREES, branch.replaceAll("/", "__"));
export const hasWorktree = (branch: string) => existsSync(path.join(treeDir(branch), ".git"));

function worktreePath(branch: string): string {
  const dir = treeDir(branch);
  if (!existsSync(path.join(dir, ".git"))) {
    throw new Error(`No worktree for ${branch}. Call git_checkout first.`);
  }
  return dir;
}

async function refExists(ref: string): Promise<boolean> {
  try {
    // --quiet fails with no stderr, which simple-git doesn't treat as an error: check the output.
    const sha = await git(BARE).raw(["rev-parse", "--verify", "--quiet", `${ref}^{commit}`]);
    return sha.trim().length > 0;
  } catch {
    return false;
  }
}

// ── Checkout ───────────────────────────────────────────────────────────────────────────────

export type Checkout = { branch: string; created: boolean; base?: string; head: string };

/**
 * Opens the worktree for `branch`: reuses it, or tracks origin/<branch>, or (with `base`)
 * creates the branch from the base (a local branch if there is one, else origin/<base>).
 */
export function checkout(branch: string, base?: string): Promise<Checkout> {
  parseFeatureBranch(branch);
  return serial(async () => {
    await ensureRepo();
    const dir = treeDir(branch);
    if (existsSync(path.join(dir, ".git"))) {
      const head = (await git(dir).raw(["rev-parse", "--short", "HEAD"])).trim();
      return { branch, created: false, head };
    }
    await mkdir(TREES, { recursive: true });
    await git(BARE).raw(["worktree", "prune"]);
    if (await refExists(`refs/heads/${branch}`)) {
      await git(BARE).raw(["worktree", "add", dir, branch]);
    } else if (await refExists(`refs/remotes/origin/${branch}`)) {
      await git(BARE).raw(["worktree", "add", "--track", "-b", branch, dir, `origin/${branch}`]);
    } else if (base) {
      const baseRef = (await refExists(`refs/heads/${base}`))
        ? base
        : (await refExists(`refs/remotes/origin/${base}`))
          ? `origin/${base}`
          : null;
      if (!baseRef) throw new Error(`Base branch ${base} doesn't exist locally or on origin.`);
      await git(BARE).raw(["worktree", "add", "--no-track", "-b", branch, dir, baseRef]);
    } else {
      throw new Error(`${branch} doesn't exist yet. Pass a base to create it.`);
    }
    const head = (await git(dir).raw(["rev-parse", "--short", "HEAD"])).trim();
    return { branch, created: true, base, head };
  });
}

/** Brings an existing worktree up to date with its upstream (fast-forward only). */
export function pull(branch: string): Promise<string> {
  return serial(async () => {
    await ensureRepo();
    const dir = worktreePath(branch);
    if (!(await refExists(`refs/remotes/origin/${branch}`))) return "no upstream yet";
    return (await git(dir).raw(["merge", "--ff-only", `origin/${branch}`])).trim();
  });
}

// ── Reading (worktree files if one exists, else the committed tree) ─────────────────────────

/** `ref` is a branch name ("main" or a feature branch). */
async function source(ref: string): Promise<{ dir: string } | { rev: string }> {
  if (ref !== "main" && hasWorktree(ref)) return { dir: worktreePath(ref) };
  await ensureRepo();
  for (const rev of [`refs/heads/${ref}`, `refs/remotes/origin/${ref}`]) {
    if (await refExists(rev)) return { rev };
  }
  throw new Error(`Branch ${ref} not found locally or on origin.`);
}

export function listFiles(ref: string, prefix = ""): Promise<string[]> {
  const p = prefix ? cleanPath(prefix) : "";
  return serial(async () => {
    const src = await source(ref);
    const out =
      "dir" in src
        ? await git(src.dir).raw([
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "--",
            ...(p ? [p] : []),
          ])
        : await git(BARE).raw(["ls-tree", "-r", "--name-only", src.rev, "--", ...(p ? [p] : [])]);
    const files = [...new Set(out.split("\n").filter(Boolean))];
    if ("dir" in src) return files.filter((f) => existsSync(path.join(src.dir, f)));
    return files;
  });
}

export function readText(ref: string, p: string): Promise<string> {
  const file = cleanPath(p);
  return serial(async () => {
    const src = await source(ref);
    if ("dir" in src) return readFile(path.join(src.dir, file), "utf8");
    return git(BARE).raw(["show", `${src.rev}:${file}`]);
  });
}

export async function tryReadText(ref: string, p: string): Promise<string | null> {
  try {
    return await readText(ref, p);
  } catch {
    return null;
  }
}

export function search(ref: string, pattern: string, prefix = ""): Promise<string> {
  const p = prefix ? cleanPath(prefix) : "";
  return serial(async () => {
    const src = await source(ref);
    const args = ["grep", "-n", "-I", "-E", "--max-count=50"];
    try {
      if ("dir" in src) {
        return await git(src.dir).raw([...args, "--untracked", "-e", pattern, "--", ...(p ? [p] : [])]);
      }
      const out = await git(BARE).raw([...args, "-e", pattern, src.rev, "--", ...(p ? [p] : [])]);
      return out.replaceAll(`${src.rev}:`, "");
    } catch (error) {
      // git grep exits 1 with no output when nothing matches
      if (!String((error as Error).message ?? error).trim()) return "";
      throw error;
    }
  });
}

/** Local and remote feature branches, deduped, e.g. ["feat/001-x/00-docs", ...]. */
export function featureBranches(): Promise<string[]> {
  return serial(async () => {
    await ensureRepo();
    const out = await git(BARE).raw([
      "for-each-ref",
      "--format=%(refname)",
      "refs/heads/feat",
      "refs/remotes/origin/feat",
    ]);
    const names = out
      .split("\n")
      .filter(Boolean)
      .map((r) => r.replace(/^refs\/heads\//, "").replace(/^refs\/remotes\/origin\//, ""));
    return [...new Set(names)].sort();
  });
}

// ── Writing (worktrees of feature branches only) ────────────────────────────────────────────

function writableFile(branch: string, p: string, prefix: string): string {
  const file = cleanPath(p);
  if (!file.startsWith(prefix)) {
    throw new Error(`On ${branch} you can only change files under ${prefix}. "${file}" is outside it.`);
  }
  return path.join(worktreePath(branch), file);
}

export function writeText(branch: string, p: string, content: string, prefix: string) {
  return serial(async () => {
    const full = writableFile(branch, p, prefix);
    await mkdir(path.dirname(full), { recursive: true });
    await writeFile(full, content, "utf8");
  });
}

export function deleteFile(branch: string, p: string, prefix: string) {
  return serial(async () => {
    await rm(writableFile(branch, p, prefix));
  });
}

// ── Commit, push, status, rebase ────────────────────────────────────────────────────────────

export function status(branch: string, base?: string, patch = false) {
  return serial(async () => {
    const dir = worktreePath(branch);
    const g = git(dir);
    const porcelain = (await g.raw(["status", "--porcelain=v1", "--branch"])).trim();
    let log = "";
    let diff = "";
    if (base) {
      const baseRef = (await refExists(`refs/heads/${base}`)) ? base : `origin/${base}`;
      log = (await g.raw(["log", "--oneline", `${baseRef}..HEAD`])).trim();
      diff = (await g.raw(["diff", patch ? "--patch" : "--stat", `${baseRef}...HEAD`])).trim();
    }
    const working = patch ? (await g.raw(["diff", "HEAD"])).trim() : "";
    return { status: porcelain, commits: log, diff: cap(diff, 30_000), uncommitted: cap(working, 20_000) };
  });
}

export function commit(branch: string, message: string, prefix: string) {
  return serial(async () => {
    const dir = worktreePath(branch);
    const g = git(dir);
    // Stage only the folder this branch owns, so nothing outside it is ever committed.
    await g.raw(["add", "--all", "--", prefix]);
    const staged = (await g.raw(["diff", "--cached", "--name-only"])).trim();
    if (!staged) return { committed: false, files: [] as string[] };
    await g.raw(["commit", "-m", message]);
    const sha = (await g.raw(["rev-parse", "--short", "HEAD"])).trim();
    return { committed: true, sha, files: staged.split("\n") };
  });
}

export function push(branch: string, forceWithLease: boolean) {
  parseFeatureBranch(branch);
  return serial(async () => {
    const dir = worktreePath(branch);
    const args = ["push", "-u", "origin", `${branch}:${branch}`];
    if (forceWithLease) args.splice(1, 0, `--force-with-lease=${branch}`);
    await git(dir).raw(args);
    lastFetch = 0;
    return (await git(dir).raw(["rev-parse", "--short", "HEAD"])).trim();
  });
}

/**
 * Rebases a task branch. With `onto`: `git rebase --onto origin/<onto> <upstream> <branch>`
 * (after the base PR merged). Without: `git rebase origin/<upstream>`.
 */
export function rebase(branch: string, upstream: string, onto?: string) {
  parseFeatureBranch(branch);
  return serial(async () => {
    await ensureRepo();
    const dir = worktreePath(branch);
    const g = git(dir, { GIT_EDITOR: "true" });
    const upstreamRef = (await refExists(`refs/heads/${upstream}`)) ? upstream : `origin/${upstream}`;
    const args = onto
      ? ["rebase", "--onto", `origin/${onto}`, upstreamRef, branch]
      : ["rebase", `origin/${upstream}`];
    try {
      await g.raw(args);
      return { ok: true as const, head: (await g.raw(["rev-parse", "--short", "HEAD"])).trim() };
    } catch {
      return { ok: false as const, conflicts: await conflicts(g) };
    }
  });
}

export function rebaseControl(branch: string, action: "continue" | "abort", prefix: string) {
  return serial(async () => {
    const dir = worktreePath(branch);
    const g = git(dir, { GIT_EDITOR: "true" });
    if (action === "abort") {
      await g.raw(["rebase", "--abort"]);
      return { ok: true as const, aborted: true };
    }
    const remaining = await conflicts(g);
    const outside = remaining.filter((f) => !f.startsWith(prefix));
    if (outside.length) {
      return {
        ok: false as const,
        conflicts: remaining,
        error: `Conflicts outside ${prefix} can't be resolved here: ${outside.join(", ")}. Abort and report back.`,
      };
    }
    await g.raw(["add", "--all", "--", prefix]);
    try {
      await g.raw(["rebase", "--continue"]);
      return { ok: true as const, head: (await g.raw(["rev-parse", "--short", "HEAD"])).trim() };
    } catch {
      return { ok: false as const, conflicts: await conflicts(g) };
    }
  });
}

async function conflicts(g: SimpleGit): Promise<string[]> {
  const out = await g.raw(["diff", "--name-only", "--diff-filter=U"]);
  return out.split("\n").filter(Boolean);
}

/** Files changed on the branch since `base`, plus uncommitted ones, under `prefix`. */
export function changedFiles(branch: string, base: string, prefix: string): Promise<string[]> {
  return serial(async () => {
    const dir = worktreePath(branch);
    const g = git(dir);
    const baseRef = (await refExists(`refs/heads/${base}`)) ? base : `origin/${base}`;
    const committed = await g.raw(["diff", "--name-only", "--diff-filter=d", `${baseRef}...HEAD`, "--", prefix]);
    const working = await g.raw(["ls-files", "--modified", "--others", "--exclude-standard", "--", prefix]);
    return [...new Set([...committed.split("\n"), ...working.split("\n")].filter(Boolean))].filter(
      (f) => existsSync(path.join(dir, f)),
    );
  });
}

export function worktreeDir(branch: string): string {
  return worktreePath(branch);
}

export function cap(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max)}\n… [${text.length - max} more characters]`;
}
