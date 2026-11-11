import { stat } from "node:fs/promises";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { listFiles, resolveTaskPath, toolError, toolJson } from "../feature-api.ts";
import { findTask, loadTasks, requireTaskWorktree } from "../task-api.ts";
import { taskIds } from "./task-ids.ts";

const LIMIT = 500;

export const taskListFiles = tool(
  "task_list_files",
  `List files in a task's worktree, as paths from its root (.git left out, at most ${LIMIT}).`,
  { ...taskIds, dir: z.string().optional().describe("A folder in the worktree; the root when left out") },
  async ({ projectId, featureId, number, dir }) => {
    try {
      const task = findTask(await loadTasks(projectId, featureId), number);
      const worktree = requireTaskWorktree(task);
      const root = await resolveTaskPath(worktree, ".");
      const start = await resolveTaskPath(worktree, dir || ".");
      if (!(await stat(start)).isDirectory()) throw new Error(`${dir} is a file, not a folder.`);
      return toolJson(await listFiles(root, start, LIMIT));
    } catch (error) {
      return toolError("Couldn't list files", error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
