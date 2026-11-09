import { z } from "zod";

/** The ids every `feature_*` tool takes, from the chat's `[Feature context: …]` line. */
export const featureIds = {
  projectId: z.number().int().positive().describe("From the [Feature context] line"),
  featureId: z.number().int().positive().describe("From the [Feature context] line"),
};
