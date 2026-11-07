import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiPatch } from "../api.ts";
import { type FeatureStep, toolError, toolJson } from "../feature-api.ts";
import { featureIds } from "./feature-ids.ts";

export const featureUpdateStep = tool(
  "feature_update_step",
  "Change a recorded step's status (one of its states, see feature_get), title or document path. Set an approval state only after the user's explicit yes in this chat.",
  {
    ...featureIds,
    stepId: z.number().int().positive(),
    status: z.string().min(1).max(40).optional(),
    title: z.string().min(1).max(200).optional(),
    documentPath: z.string().max(300).optional().describe("An empty string clears it"),
  },
  async ({ projectId, featureId, stepId, status, title, documentPath }) => {
    try {
      const changes = Object.fromEntries(
        Object.entries({ status, title, documentPath }).filter(([, value]) => value !== undefined),
      );
      if (Object.keys(changes).length === 0) throw new Error("Give a status, title or documentPath to change.");
      return toolJson(await apiPatch<FeatureStep>(`/api/projects/${projectId}/features/${featureId}/steps/${stepId}`, changes));
    } catch (error) {
      return toolError(`Couldn't update step ${stepId}`, error);
    }
  },
);
