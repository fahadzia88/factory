import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { toolError, toolJson } from "../feature-api.ts";
import { taskIds } from "./task-ids.ts";
import { writablePath } from "./task-write.ts";

export const taskWriteFile = tool(
  "task_write_file",
  "Write a whole file in a task's worktree (create or replace). Locked skeleton files are refused. Commit with task_commit.",
  { ...taskIds, path: z.string().min(1).describe("A path from the worktree's root"), content: z.string().max(500_000) },
  async ({ projectId, featureId, number, path: rel, content }) => {
    try {
      const file = await writablePath(projectId, featureId, number, rel);
      await mkdir(path.dirname(file), { recursive: true });
      await writeFile(file, content, "utf8");
      return toolJson({ path: rel, bytes: Buffer.byteLength(content, "utf8") });
    } catch (error) {
      return toolError(`Couldn't write ${rel}`, error);
    }
  },
);
