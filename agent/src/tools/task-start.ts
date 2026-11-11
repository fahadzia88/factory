import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiPost } from "../api.ts";
import { toolJson } from "../feature-api.ts";
import type { TaskRow } from "../task-api.ts";
import { apiToolError } from "../task-api.ts";
import { featureIds } from "./feature-ids.ts";

export const taskStart = tool(
  "task_start",
  "Start the next task: the API checks every spec is approved and the order, then creates the task's branch and worktree from its base.",
  { ...featureIds, dir: z.string().min(1).describe("The task's folder in the feature's tasks/, e.g. 03-api-task-records") },
  async ({ projectId, featureId, dir }) => {
    try {
      return toolJson(await apiPost<TaskRow>(`/api/projects/${projectId}/features/${featureId}/tasks`, { dir }, 150_000));
    } catch (error) {
      return apiToolError(`Couldn't start ${dir}`, error);
    }
  },
);
