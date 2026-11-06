/**
 * The factory project's checkout on the shared `repos` volume, found through
 * `GET /api/checkouts?repositoryUrl=https://github.com/<GITHUB_REPO>`:
 *
 *   /repos/<project-id>                      the clone; its working tree is the default branch
 *   /repos/<project-id>.worktrees/<branch>   one worktree per feature branch ("/" becomes "__")
 *
 * The API owns everything that touches the network or the set of worktrees: clone, fetch,
 * worktree add/remove and push, with its own token. This file only runs local git inside
 * feature-branch worktrees and read-only git in the clone. Each command gets the author and
 * committer from GIT_USER_NAME / GIT_USER_EMAIL in its env; there are no git credentials, and
 * nothing is written to the clone's config.
 */
import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { ApiError, apiGet, apiPost } from "../../src/api.ts";

const execGit = promisify(execFile);

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

// ── Local git ──────────────────────────────────────────────────────────────────────────────

type GitError = Error & { exitCode?: number };

/**
 * Runs a local git command in `cwd` and resolves to its stdout. The env is fixed and holds no
 * secrets, so an accidental network command fails instead of prompting or authenticating.
 */
async function git(cwd: string, args: string[], opts: { editor?: boolean } = {}): Promise<string> {
  const name = process.env.GIT_USER_NAME ?? "factory agent";
  const email = process.env.GIT_USER_EMAIL ?? "factory-agent@users.noreply.github.com";
  const env: Record<string, string> = {
    PATH: process.env.PATH ?? "/usr/local/bin:/usr/bin:/bin",
    HOME: process.env.HOME ?? "/root",
    GIT_TERMINAL_PROMPT: "0",
    GIT_AUTHOR_NAME: name,
    GIT_COMMITTER_NAME: name,
    GIT_AUTHOR_EMAIL: email,
    GIT_COMMITTER_EMAIL: email,
  };
  if (opts.editor) env.GIT_EDITOR = "true";
  try {
    const { stdout } = await execGit("git", args, { cwd, env, maxBuffer: 64 * 1024 * 1024 });
    return stdout;
  } catch (cause) {
    const failure = cause as { code?: unknown; stderr?: unknown; message?: string };
    const exitCode = typeof failure.code === "number" ? failure.code : undefined;
    const stderr = typeof failure.stderr === "string" ? failure.stderr.trim() : "";
    const error: GitError = new Error(
      stderr || (exitCode !== undefined ? `git ${args[0]} exited with code ${exitCode}` : (failure.message ?? "git failed")),
    );
    error.exitCode = exitCode;
    throw error;
  }
}

// One git operation at a time: worktrees share refs and objects with the clone.
let queue: Promise<unknown> = Promise.resolve();
export function serial<T>(work: () => Promise<T>): Promise<T> {
  const run = queue.then(work, work);
  queue = run.catch(() => undefined);
  return run;
}

// ── The checkout, through the API ──────────────────────────────────────────────────────────

type ApiWorktree = {
  id: number;
  branch: string;
  path: string;
  main: boolean;
  baseBranch: string | null;
  missing: boolean;
  head: { sha: string; subject: string } | null;
  createdAt: string;
};

type ApiCheckout = {
  id: number;
  projectId: number;
  state: "cloning" | "ready" | "failed";
  error: string | null;
  path: string;
  defaultBranch: string;
  createdAt: string;
  lastFetchedAt: string | null;
  worktrees: ApiWorktree[];
};

let current: ApiCheckout | undefined;
let lastFetch = 0;

function repoUrl(): string {
  const { owner, repo } = repoSlug();
  return `https://github.com/${owner}/${repo}`;
}

function notReady(): Error {
  const { owner, repo } = repoSlug();
  return new Error(
    `Register the factory repository (https://github.com/${owner}/${repo}) as a project and check it out on its project page first.`,
  );
}

/** The factory project's checkout; cached until `force` or a stale-checkout error. */
async function resolve(force = false): Promise<ApiCheckout> {
  if (current && !force) return current;
  let checkout: ApiCheckout;
  try {
    checkout = await apiGet<ApiCheckout>("/api/checkouts", { repositoryUrl: repoUrl() });
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      current = undefined;
      throw notReady();
    }
    throw error;
  }
  if (checkout.state !== "ready") {
    current = undefined;
    throw notReady();
  }
  current = checkout;
  return checkout;
}

const NOT_READY_CODES = new Set(["project_not_found", "checkout_not_found", "checkout_not_ready"]);

/** A stale cache: the project, checkout or worktree is gone, or the checkout isn't ready. */
function isStale(error: unknown): error is ApiError {
  return (
    error instanceof ApiError && (error.status === 404 || (error.status === 409 && error.code === "checkout_not_ready"))
  );
}

/**
 * Runs an API call against the resolved checkout. A stale-checkout error re-resolves and retries
 * once; any other API error becomes an `Error` with the API's `detail`, never retried.
 */
async function withCheckout<T>(fn: (checkout: ApiCheckout) => Promise<T>): Promise<T> {
  try {
    return await fn(await resolve());
  } catch (error) {
    if (!isStale(error)) throw plain(error);
  }
  try {
    return await fn(await resolve(true));
  } catch (error) {
    if (isStale(error) && (error.code === undefined || NOT_READY_CODES.has(error.code))) {
      current = undefined;
      throw notReady();
    }
    throw plain(error);
  }
}

function plain(error: unknown): unknown {
  return error instanceof ApiError ? new Error(error.message) : error;
}

/** Resolves the checkout and asks the API to fetch, at most every 15 s. */
async function ensureRepo(): Promise<ApiCheckout> {
  await resolve();
  if (Date.now() - lastFetch > 15_000) {
    current = await withCheckout((c) =>
      apiPost<ApiCheckout>(`/api/projects/${c.projectId}/checkout/fetch`, {}, 150_000),
    );
    lastFetch = Date.now();
  }
  return current!;
}

function findWorktree(branch: string): ApiWorktree | undefined {
  return current?.worktrees.find((w) => w.branch === branch);
}

/** The clone (the main worktree); read-only git runs here. */
function clone(): string {
  if (!current) throw notReady();
  return current.path;
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

/** The worktree folder for `branch`: the API's path when known, else where the API puts it. */
function treeDir(checkout: ApiCheckout, branch: string): string {
  return findWorktree(branch)?.path ?? `${checkout.path}.worktrees/${branch.replaceAll("/", "__")}`;
}

export const hasWorktree = (branch: string) =>
  current !== undefined && existsSync(path.join(treeDir(current, branch), ".git"));

function worktreePath(branch: string): string {
  const dir = current && treeDir(current, branch);
  if (!dir || !existsSync(path.join(dir, ".git"))) {
    throw new Error(`No worktree for ${branch}. Call git_checkout first.`);
  }
  return dir;
}

async function refExists(ref: string): Promise<boolean> {
  try {
    const sha = await git(clone(), ["rev-parse", "--verify", "--quiet", `${ref}^{commit}`]);
    return sha.trim().length > 0;
  } catch {
    return false;
  }
}

const shortHead = async (dir: string) => (await git(dir, ["rev-parse", "--short", "HEAD"])).trim();

// ── Checkout ───────────────────────────────────────────────────────────────────────────────

export type Checkout = { branch: string; created: boolean; base?: string; head: string };

async function existing(wt: ApiWorktree): Promise<Checkout> {
  if (wt.missing) {
    throw new Error(
      `The worktree for ${wt.branch} is missing on disk. Remove it on the factory project's page, then check it out again.`,
    );
  }
  return { branch: wt.branch, created: false, head: await shortHead(wt.path) };
}

/**
 * Opens the worktree for `branch` through the API: reuses it, or uses the local branch, or
 * tracks origin/<branch>, or (with `base`) creates the branch from the base.
 */
export function checkout(branch: string, base?: string): Promise<Checkout> {
  parseFeatureBranch(branch);
  return serial(async () => {
    await ensureRepo();
    if (!findWorktree(branch)) await resolve(true); // one made from the project page
    const known = findWorktree(branch);
    if (known) return existing(known);

    let body: { branch: string; base?: string };
    if ((await refExists(`refs/heads/${branch}`)) || (await refExists(`refs/remotes/origin/${branch}`))) {
      body = { branch };
    } else if (base) {
      if (!(await refExists(`refs/heads/${base}`)) && !(await refExists(`refs/remotes/origin/${base}`))) {
        throw new Error(`Base branch ${base} doesn't exist locally or on origin.`);
      }
      body = { branch, base };
    } else {
      throw new Error(`${branch} doesn't exist yet. Pass a base to create it.`);
    }

    let conflict: ApiError | undefined;
    const created = await withCheckout(async (c) => {
      try {
        return await apiPost<ApiWorktree>(`/api/projects/${c.projectId}/checkout/worktrees`, body, 150_000);
      } catch (error) {
        if (error instanceof ApiError && error.code === "branch_checked_out") {
          conflict = error;
          return undefined;
        }
        throw error;
      }
    });
    if (!created) {
      // A race with the project page: someone else just added it.
      await resolve(true);
      const raced = findWorktree(branch);
      if (raced) return existing(raced);
      throw new Error(conflict?.message ?? `${branch} is already checked out.`);
    }
    current!.worktrees.push(created);
    return { branch, created: true, base, head: await shortHead(created.path) };
  });
}

/** Brings an existing worktree up to date with its upstream (fast-forward only). */
export function pull(branch: string): Promise<string> {
  return serial(async () => {
    await ensureRepo();
    const dir = worktreePath(branch);
    if (!(await refExists(`refs/remotes/origin/${branch}`))) return "no upstream yet";
    return (await git(dir, ["merge", "--ff-only", `origin/${branch}`])).trim();
  });
}

// ── Reading (worktree files if one exists, else the committed tree) ─────────────────────────

/** `ref` is a branch name ("main" or a feature branch). */
async function source(ref: string): Promise<{ dir: string } | { rev: string }> {
  await resolve();
  if (ref !== "main" && hasWorktree(ref)) return { dir: worktreePath(ref) };
  await ensureRepo();
  for (const rev of [`refs/remotes/origin/${ref}`, `refs/heads/${ref}`]) {
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
        ? await git(src.dir, ["ls-files", "--cached", "--others", "--exclude-standard", "--", ...(p ? [p] : [])])
        : await git(clone(), ["ls-tree", "-r", "--name-only", src.rev, "--", ...(p ? [p] : [])]);
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
    return git(clone(), ["show", `${src.rev}:${file}`]);
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
        return await git(src.dir, [...args, "--untracked", "-e", pattern, "--", ...(p ? [p] : [])]);
      }
      const out = await git(clone(), [...args, "-e", pattern, src.rev, "--", ...(p ? [p] : [])]);
      return out.replaceAll(`${src.rev}:`, "");
    } catch (error) {
      // git grep exits 1 with no output when nothing matches
      if ((error as GitError).exitCode === 1) return "";
      throw error;
    }
  });
}

/** Local and remote feature branches, deduped, e.g. ["feat/001-x/00-docs", ...]. */
export function featureBranches(): Promise<string[]> {
  return serial(async () => {
    await ensureRepo();
    const out = await git(clone(), [
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
    await resolve();
    const full = writableFile(branch, p, prefix);
    await mkdir(path.dirname(full), { recursive: true });
    await writeFile(full, content, "utf8");
  });
}

export function deleteFile(branch: string, p: string, prefix: string) {
  return serial(async () => {
    await resolve();
    await rm(writableFile(branch, p, prefix));
  });
}

// ── Commit, push, status, rebase ────────────────────────────────────────────────────────────

export function status(branch: string, base?: string, patch = false) {
  return serial(async () => {
    await resolve();
    const dir = worktreePath(branch);
    const porcelain = (await git(dir, ["status", "--porcelain=v1", "--branch"])).trim();
    let log = "";
    let diff = "";
    if (base) {
      const baseRef = (await refExists(`refs/heads/${base}`)) ? base : `origin/${base}`;
      log = (await git(dir, ["log", "--oneline", `${baseRef}..HEAD`])).trim();
      diff = (await git(dir, ["diff", patch ? "--patch" : "--stat", `${baseRef}...HEAD`])).trim();
    }
    const working = patch ? (await git(dir, ["diff", "HEAD"])).trim() : "";
    return { status: porcelain, commits: log, diff: cap(diff, 30_000), uncommitted: cap(working, 20_000) };
  });
}

export function commit(branch: string, message: string, prefix: string) {
  return serial(async () => {
    await resolve();
    const dir = worktreePath(branch);
    // Stage only the folder this branch owns, so nothing outside it is ever committed.
    await git(dir, ["add", "--all", "--", prefix]);
    const staged = (await git(dir, ["diff", "--cached", "--name-only"])).trim();
    if (!staged) return { committed: false, files: [] as string[] };
    await git(dir, ["commit", "-m", message]);
    return { committed: true, sha: await shortHead(dir), files: staged.split("\n") };
  });
}

/** Pushes the branch through the API (which holds the token); returns the pushed head. */
export function push(branch: string, forceWithLease: boolean) {
  parseFeatureBranch(branch);
  return serial(async () => {
    await resolve();
    worktreePath(branch);
    if (!findWorktree(branch)) await resolve(true);
    const known = findWorktree(branch);
    if (!known) throw new Error(`No worktree for ${branch}. Call git_checkout first.`);
    const result = await withCheckout((c) => {
      const id = c.worktrees.find((w) => w.branch === branch)?.id ?? known.id;
      return apiPost<{ branch: string; head: string }>(
        `/api/projects/${c.projectId}/checkout/worktrees/${id}/push`,
        { force: forceWithLease },
        150_000,
      );
    });
    lastFetch = Date.now(); // the API fetches after pushing
    return result.head;
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
    const upstreamRef = (await refExists(`refs/heads/${upstream}`)) ? upstream : `origin/${upstream}`;
    const args = onto
      ? ["rebase", "--onto", `origin/${onto}`, upstreamRef, branch]
      : ["rebase", `origin/${upstream}`];
    try {
      await git(dir, args, { editor: true });
      return { ok: true as const, head: await shortHead(dir) };
    } catch {
      return { ok: false as const, conflicts: await conflicts(dir) };
    }
  });
}

export function rebaseControl(branch: string, action: "continue" | "abort", prefix: string) {
  return serial(async () => {
    await resolve();
    const dir = worktreePath(branch);
    if (action === "abort") {
      await git(dir, ["rebase", "--abort"], { editor: true });
      return { ok: true as const, aborted: true };
    }
    const remaining = await conflicts(dir);
    const outside = remaining.filter((f) => !f.startsWith(prefix));
    if (outside.length) {
      return {
        ok: false as const,
        conflicts: remaining,
        error: `Conflicts outside ${prefix} can't be resolved here: ${outside.join(", ")}. Abort and report back.`,
      };
    }
    await git(dir, ["add", "--all", "--", prefix]);
    try {
      await git(dir, ["rebase", "--continue"], { editor: true });
      return { ok: true as const, head: await shortHead(dir) };
    } catch {
      return { ok: false as const, conflicts: await conflicts(dir) };
    }
  });
}

async function conflicts(dir: string): Promise<string[]> {
  const out = await git(dir, ["diff", "--name-only", "--diff-filter=U"]);
  return out.split("\n").filter(Boolean);
}

/** Files changed on the branch since `base`, plus uncommitted ones, under `prefix`. */
export function changedFiles(branch: string, base: string, prefix: string): Promise<string[]> {
  return serial(async () => {
    await resolve();
    const dir = worktreePath(branch);
    const baseRef = (await refExists(`refs/heads/${base}`)) ? base : `origin/${base}`;
    const committed = await git(dir, ["diff", "--name-only", "--diff-filter=d", `${baseRef}...HEAD`, "--", prefix]);
    const working = await git(dir, ["ls-files", "--modified", "--others", "--exclude-standard", "--", prefix]);
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
