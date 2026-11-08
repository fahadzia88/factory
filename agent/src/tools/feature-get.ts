import { tool } from "@anthropic-ai/claude-agent-sdk";
import { loadFeature, toolError, toolJson } from "../feature-api.ts";
import { featureIds } from "./feature-ids.ts";

export const featureGet = tool(
  "feature_get",
  "Get the feature: status, steps, workflow (its states and step types), branch, folder and worktree.",
  featureIds,
  async ({ projectId, featureId }) => {
    try {
      return toolJson(await loadFeature(projectId, featureId));
    } catch (error) {
      return toolError(`Couldn't load feature ${featureId}`, error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
