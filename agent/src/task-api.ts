import { existsSync } from "node:fs";
import path from "node:path";
import { ApiError, apiGet } from "./api.ts";
import type { FeatureWorktree } from "./feature-api.ts";

/** A task's pull request as the API reports it. */
export type TaskPull = {
  number: number;
  title: string;
  state: "open" | "closed" | "merged";
  draft: boolean;
  htmlUrl: string;
  baseRef: string;
  headSha: string | null;
  mergedAt: string | null;
  checks: {
    state: "none" | "pending" | "success" | "failure";
    runs: { name: string; status: string; conclusion: string | null; htmlUrl: string | null; title: string | null; summary: string | null }[];
  };
};

/** One branch of a feature's stack, as `GET …/tasks` returns it; row 0 is the docs branch. */
export type TaskRow = {
  number: number;
  layer: string;
  slug: string;
  branch: string;
  baseBranch: string;
  specPath: string | null;
  stepId: number | null;
  worktree: FeatureWorktree | null;
  pull: TaskPull | null;
  pullError: string | null;
  needsRestack: boolean;
  createdAt: string;
  updatedAt: string;
};

/** The feature's tasks, row 0 first. */
export function loadTasks(projectId: number, featureId: number): Promise<TaskRow[]> {
  return apiGet<TaskRow[]>(`/api/projects/${projectId}/features/${featureId}/tasks`);
}

/** Task [number] from [tasks]; row 0 and unstarted tasks are refused with what to do instead. */
export function findTask(tasks: TaskRow[], number: number): TaskRow {
  if (number === 0) throw new Error("Task 0 is the docs branch; use the feature_* file tools.");
  const task = tasks.find((t) => t.number === number);
  if (!task) throw new Error(`Task ${number} hasn't been started. Start it with task_start.`);
  return task;
}

/** The task's worktree folder, or an error saying it's missing. */
export function requireTaskWorktree(task: TaskRow): string {
  const worktree = task.worktree;
  if (!worktree || worktree.missing || !existsSync(path.join(worktree.path, ".git"))) {
    throw new Error(`The worktree for ${task.branch} is missing. Check it out again on the project page.`);
  }
  return worktree.path;
}

/**
 * Refuses changes to a task whose pull request is merged or closed, and to a lower task unless its pull request is
 * open (to fix a failed check); only the highest task is otherwise being worked on.
 */
export function assertWritable(task: TaskRow, tasks: TaskRow[]): void {
  const state = task.pull?.state;
  if (state === "merged" || state === "closed") {
    throw new Error(`Task ${task.number}'s pull request is ${state}; it can't be changed.`);
  }
  const active = Math.max(...tasks.map((t) => t.number));
  if (task.number !== active && state !== "open") {
    throw new Error(`Task ${task.number} isn't the active task; task ${active} is.`);
  }
}

/** An API failure for the model: `<detail> (<code>)` plus the problem's lists (unapproved specs, unfinished tasks, conflicting files). */
export function describeApiError(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : String(error);
  const code = error.code ? ` (${error.code})` : "";
  const extras = error.extras ? ` ${JSON.stringify(error.extras)}` : "";
  return `${error.message}${code}${extras}`;
}

/** A failed tool result whose text is [prefix] and [describeApiError]. */
export function apiToolError(prefix: string, error: unknown) {
  return { content: [{ type: "text" as const, text: `${prefix}: ${describeApiError(error)}` }], isError: true };
}
