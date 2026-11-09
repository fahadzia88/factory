import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiPost } from "../api.ts";
import { type FeatureStep, toolError, toolJson } from "../feature-api.ts";
import { featureIds } from "./feature-ids.ts";

export const featureAddStep = tool(
  "feature_add_step",
  "Record a step you're starting: a step type from the feature's workflow (see feature_get), a title, and the document's path in the feature folder. A type can be added more than once (one spec per task).",
  {
    ...featureIds,
    type: z.string().min(1).describe("A step key from the workflow"),
    title: z.string().min(1).max(200),
    documentPath: z.string().max(300).optional().describe("The step's document inside the feature folder"),
  },
  async ({ projectId, featureId, type, title, documentPath }) => {
    try {
      const body = documentPath === undefined ? { type, title } : { type, title, documentPath };
      return toolJson(await apiPost<FeatureStep>(`/api/projects/${projectId}/features/${featureId}/steps`, body));
    } catch (error) {
      return toolError("Couldn't add the step", error);
    }
  },
);
