import { existsSync } from "node:fs";
import { lstat, readdir, realpath } from "node:fs/promises";
import path from "node:path";
import { apiGet } from "./api.ts";

/** One step type of a feature's workflow (`feature/workflow.json`); `states` start with the initial one. */
export type WorkflowStep = { key: string; title: string; skill: string; states: string[] };

/** The workflow a feature was created under. */
export type Workflow = { featureStates: string[]; steps: WorkflowStep[] };

/** A step the agent recorded on a feature. */
export type FeatureStep = {
  id: number;
  position: number;
  type: string;
  typeTitle: string;
  title: string;
  documentPath: string | null;
  status: string;
  states: string[];
  createdAt: string;
  updatedAt: string;
};

/** The worktree of a feature's branch on `/repos`; `missing` when its folder isn't on disk. */
export type FeatureWorktree = { id: number; path: string; missing: boolean };

/** A feature as `GET /api/projects/{id}/features/{featureId}` returns it. */
export type Feature = {
  id: number;
  projectId: number;
  number: number;
  code: string;
  name: string;
  slug: string;
  status: string;
  branch: string;
  folder: string;
  worktree: FeatureWorktree | null;
  workflow: Workflow;
  steps: FeatureStep[];
  createdAt: string;
  updatedAt: string;
};

/** Loads a feature from the API; errors are `ApiError`s whose message is the problem's `detail`. */
export function loadFeature(projectId: number, featureId: number): Promise<Feature> {
  return apiGet<Feature>(`/api/projects/${projectId}/features/${featureId}`);
}

/** The feature's worktree folder, or an error saying it's missing. */
export function requireWorktree(feature: Feature): string {
  const worktree = feature.worktree;
  if (!worktree || worktree.missing || !existsSync(path.join(worktree.path, ".git"))) {
    throw new Error(`The worktree for ${feature.branch} is missing. Check it out again on the project page.`);
  }
  return worktree.path;
}

/** The absolute path of `rel` for reading: inside the worktree, never in `.git`, symlinks followed. */
export function resolveRead(worktree: string, rel: string): Promise<string> {
  return resolveInside(worktree, rel, "", `${rel} is outside the feature's worktree.`);
}

/** The absolute path of `rel` (relative to the worktree root) for writing: inside `<worktree>/<folder>/` only. */
export function resolveWrite(worktree: string, folder: string, rel: string): Promise<string> {
  return resolveInside(worktree, rel, folder, `${rel} is outside ${folder}/; only the feature folder can be written.`);
}

/** The absolute path of `rel` in a task's worktree, for reading or writing: inside it, never in `.git`, symlinks followed. */
export function resolveTaskPath(worktree: string, rel: string): Promise<string> {
  return resolveInside(worktree, rel, "", `${rel} is outside the task's worktree.`);
}

/**
 * Files under `dir` of the worktree whose real root is `root`, as paths from the root: breadth-first, `.git` left out,
 * symlinked folders not followed, at most `limit` (`truncated` when there were more).
 */
export async function listFiles(root: string, dir: string, limit: number): Promise<{ files: string[]; truncated: boolean }> {
  const files: string[] = [];
  let truncated = false;
  const queue = [dir];
  // Dirent.isDirectory() is false for a symlink, so symlinked folders aren't followed
  while (queue.length > 0 && !truncated) {
    const folder = queue.shift() as string;
    for (const entry of await readdir(folder, { withFileTypes: true })) {
      if (entry.name === ".git") continue;
      const full = path.join(folder, entry.name);
      if (entry.isDirectory()) {
        queue.push(full);
      } else if (files.length < limit) {
        files.push(path.relative(root, full));
      } else {
        truncated = true;
        break;
      }
    }
  }
  return { files: files.sort(), truncated };
}

async function resolveInside(worktree: string, rel: string, under: string, outside: string): Promise<string> {
  if (!rel || rel.includes("\0") || path.isAbsolute(rel)) throw new Error(`${rel || "An empty path"} must be a relative path in the worktree.`);
  if (hasGitSegment(path.normalize(rel))) throw new Error(`${rel} is inside .git, which can't be used.`);
  const root = await realpath(worktree);
  const base = under ? path.join(root, under) : root;
  const real = await realOf(path.resolve(root, rel));
  if (real !== base && !real.startsWith(base + path.sep)) throw new Error(outside);
  if (hasGitSegment(path.relative(root, real))) throw new Error(`${rel} is inside .git, which can't be used.`);
  return real;
}

/** The real path of `target`, or of its deepest existing ancestor with the missing tail rejoined. */
async function realOf(target: string): Promise<string> {
  let existing = target;
  const tail: string[] = [];
  while (!(await exists(existing))) {
    tail.unshift(path.basename(existing));
    const parent = path.dirname(existing);
    if (parent === existing) break;
    existing = parent;
  }
  return path.join(await realpath(existing), ...tail);
}

async function exists(p: string): Promise<boolean> {
  try {
    await lstat(p);
    return true;
  } catch {
    return false;
  }
}

function hasGitSegment(p: string): boolean {
  return p.split(/[\\/]/).includes(".git");
}

type ToolResult = { content: { type: "text"; text: string }[]; isError?: boolean };

/** A tool result carrying `value` as JSON. */
export function toolJson(value: unknown): ToolResult {
  return { content: [{ type: "text", text: JSON.stringify(value) }] };
}

/** A tool result carrying `text`. */
export function toolText(text: string): ToolResult {
  return { content: [{ type: "text", text }] };
}

/** A failed tool result: `<prefix>: <message>`. */
export function toolError(prefix: string, error: unknown): ToolResult {
  const message = error instanceof Error ? error.message : String(error);
  return { content: [{ type: "text", text: `${prefix}: ${message}` }], isError: true };
}
