import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiPut } from "../api.ts";
import { type Feature, toolError, toolJson } from "../feature-api.ts";
import { featureIds } from "./feature-ids.ts";

export const featureSetStatus = tool(
  "feature_set_status",
  "Set the feature's status to one of its workflow's featureStates (see feature_get). Set an approval state only after the user's explicit yes in this chat.",
  { ...featureIds, status: z.string().min(1).max(40) },
  async ({ projectId, featureId, status }) => {
    try {
      const feature = await apiPut<Feature>(`/api/projects/${projectId}/features/${featureId}/status`, { status });
      return toolJson({ status: feature.status, updatedAt: feature.updatedAt });
    } catch (error) {
      return toolError("Couldn't set the status", error);
    }
  },
);
