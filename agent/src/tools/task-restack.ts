import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiPost } from "../api.ts";
import { toolJson } from "../feature-api.ts";
import type { TaskRow } from "../task-api.ts";
import { apiToolError } from "../task-api.ts";
import { featureIds } from "./feature-ids.ts";

export const taskRestack = tool(
  "task_restack",
  "Rebuild a task's branch on its current base after the branch below moved or merged, push it with a lease and retarget its pull request. Only on the user's go-ahead, bottom-up, one task at a time.",
  { ...featureIds, number: z.number().int().min(1) },
  async ({ projectId, featureId, number }) => {
    try {
      return toolJson(await apiPost<TaskRow>(`/api/projects/${projectId}/features/${featureId}/tasks/${number}/restack`, undefined, 150_000));
    } catch (error) {
      return apiToolError(`Couldn't restack task ${number}`, error);
    }
  },
);
