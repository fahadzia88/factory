import { tool } from "@anthropic-ai/claude-agent-sdk";
import { toolJson } from "../feature-api.ts";
import { apiToolError, loadTasks } from "../task-api.ts";
import { featureIds } from "./feature-ids.ts";

export const featureTasks = tool(
  "feature_tasks",
  "List the feature's tasks (row 0 is the docs branch) with their branches, pull requests, checks (failing runs named with their summary) and needsRestack.",
  featureIds,
  async ({ projectId, featureId }) => {
    try {
      return toolJson(await loadTasks(projectId, featureId));
    } catch (error) {
      return apiToolError("Couldn't load the tasks", error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
