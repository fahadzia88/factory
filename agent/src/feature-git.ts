import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execGit = promisify(execFile);

type GitError = Error & { exitCode?: number };

/**
 * Runs a local git command in a feature's worktree and resolves to its stdout. The env is fixed and holds
 * no secrets: author and committer come from GIT_USER_NAME / GIT_USER_EMAIL, nothing is written to the
 * repository's config, and a network command fails instead of authenticating. Pushes go through the API.
 */
export async function gitIn(worktree: string, args: string[]): Promise<string> {
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
  try {
    const { stdout } = await execGit("git", args, { cwd: worktree, env, maxBuffer: 64 * 1024 * 1024 });
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

const queues = new Map<string, Promise<unknown>>();

/** Runs `work` after any earlier work on the same worktree, so two tool calls never run git there at once. */
export function serialFor<T>(worktree: string, work: () => Promise<T>): Promise<T> {
  const run = (queues.get(worktree) ?? Promise.resolve()).then(work, work);
  queues.set(worktree, run.catch(() => undefined));
  return run;
}

export type CommitResult = { committed: false } | { committed: true; sha: string };

/** Commits the changes in `folder` only (by pathspec, so anything else staged by hand stays out). */
export async function commitFolder(worktree: string, folder: string, message: string): Promise<CommitResult> {
  await gitIn(worktree, ["add", "-A", "--", folder]);
  try {
    await gitIn(worktree, ["diff", "--cached", "--quiet", "--", folder]);
    return { committed: false };
  } catch (error) {
    if ((error as GitError).exitCode !== 1) throw error;
  }
  await gitIn(worktree, ["commit", "-m", message, "--", folder]);
  return { committed: true, sha: (await gitIn(worktree, ["rev-parse", "--short", "HEAD"])).trim() };
}

/** True when HEAD has commits its upstream doesn't, or the branch has no upstream yet. */
export async function hasUnpushed(worktree: string): Promise<boolean> {
  try {
    await gitIn(worktree, ["rev-parse", "--abbrev-ref", "@{u}"]);
  } catch {
    return true;
  }
  return Number((await gitIn(worktree, ["rev-list", "--count", "@{u}..HEAD"])).trim()) > 0;
}

/** Commits every change in the worktree; nothing staged → not committed. */
export async function commitAll(worktree: string, message: string): Promise<CommitResult> {
  await gitIn(worktree, ["add", "-A"]);
  try {
    await gitIn(worktree, ["diff", "--cached", "--quiet"]);
    return { committed: false };
  } catch (error) {
    if ((error as GitError).exitCode !== 1) throw error;
  }
  await gitIn(worktree, ["commit", "-m", message]);
  return { committed: true, sha: (await gitIn(worktree, ["rev-parse", "--short", "HEAD"])).trim() };
}

/** The branch checked out in the worktree, or `HEAD` when detached. */
export async function currentBranch(worktree: string): Promise<string> {
  return (await gitIn(worktree, ["rev-parse", "--abbrev-ref", "HEAD"])).trim();
}
