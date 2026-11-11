import { readFile, stat } from "node:fs/promises";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { resolveTaskPath, toolError, toolText } from "../feature-api.ts";
import { findTask, loadTasks, requireTaskWorktree } from "../task-api.ts";
import { taskIds } from "./task-ids.ts";

const MAX_BYTES = 200 * 1024;

export const taskReadFile = tool(
  "task_read_file",
  "Read a text file in a task's worktree (code, CLAUDE.md, the spec), up to 200 KB.",
  { ...taskIds, path: z.string().min(1).describe("A path from the worktree's root") },
  async ({ projectId, featureId, number, path }) => {
    try {
      const task = findTask(await loadTasks(projectId, featureId), number);
      const file = await resolveTaskPath(requireTaskWorktree(task), path);
      const info = await stat(file).catch(() => undefined);
      if (!info?.isFile()) throw new Error(`${path} isn't a file in the task's worktree.`);
      if (info.size > MAX_BYTES) {
        throw new Error(`${path} is ${Math.ceil(info.size / 1024)} KB; only files up to 200 KB can be read.`);
      }
      return toolText(await readFile(file, "utf8"));
    } catch (error) {
      return toolError(`Couldn't read ${path}`, error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
