import { tool } from "@anthropic-ai/claude-agent-sdk";
import { apiPost } from "../api.ts";
import { type Feature, toolJson } from "../feature-api.ts";
import { apiToolError } from "../task-api.ts";
import { featureIds } from "./feature-ids.ts";

export const featureComplete = tool(
  "feature_complete",
  "Finish the feature once every pull request merged and the user confirmed the acceptance criteria: sets it done and removes the task worktrees.",
  featureIds,
  async ({ projectId, featureId }) => {
    try {
      return toolJson(await apiPost<Feature>(`/api/projects/${projectId}/features/${featureId}/complete`, undefined, 150_000));
    } catch (error) {
      return apiToolError("Couldn't complete the feature", error);
    }
  },
);
