import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiPost } from "../api.ts";
import { toolJson } from "../feature-api.ts";
import type { TaskRow } from "../task-api.ts";
import { apiToolError } from "../task-api.ts";
import { featureIds } from "./feature-ids.ts";

const AC = /^AC-\d+$/;

export const taskOpenPull = tool(
  "task_open_pull",
  "Open a task's pull request into its base after task_commit pushed it. For task 0 (the docs pull request) give no task or acs.",
  {
    ...featureIds,
    number: z.number().int().min(0),
    task: z.string().optional().describe("The task's name, 1 to 100 characters (tasks 1 and up)"),
    acs: z.array(z.string()).optional().describe("The ACs the spec covers, like AC-3 (tasks 1 and up)"),
  },
  async ({ projectId, featureId, number, task, acs }) => {
    try {
      let body: { task: string; acs: string[] } | undefined;
      if (number > 0) {
        const name = task?.trim() ?? "";
        if (!name || name.length > 100) throw new Error("task must be 1 to 100 characters.");
        if (!acs?.length || !acs.every((ac) => AC.test(ac))) throw new Error("acs must list the spec's ACs, like AC-3.");
        body = { task: name, acs };
      }
      return toolJson(await apiPost<TaskRow>(`/api/projects/${projectId}/features/${featureId}/tasks/${number}/pull`, body, 150_000));
    } catch (error) {
      return apiToolError(`Couldn't open task ${number}'s pull request`, error);
    }
  },
);
