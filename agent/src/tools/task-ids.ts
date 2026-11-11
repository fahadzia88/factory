import { z } from "zod";
import { featureIds } from "./feature-ids.ts";

/** The ids every `task_*` file tool takes: the feature's, and the task number (1 or more). */
export const taskIds = {
  ...featureIds,
  number: z.number().int().min(1).describe("The task number, 1 or more (task 0 is the docs branch)"),
};
