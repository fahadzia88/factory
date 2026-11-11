import { stat } from "node:fs/promises";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { listFiles, loadFeature, requireWorktree, resolveRead, toolError, toolJson } from "../feature-api.ts";
import { featureIds } from "./feature-ids.ts";

const LIMIT = 500;

export const featureListFiles = tool(
  "feature_list_files",
  `List files in the feature's worktree, as paths from its root (.git left out, at most ${LIMIT}).`,
  { ...featureIds, dir: z.string().optional().describe("A folder in the worktree; the root when left out") },
  async ({ projectId, featureId, dir }) => {
    try {
      const feature = await loadFeature(projectId, featureId);
      const worktree = requireWorktree(feature);
      const root = await resolveRead(worktree, ".");
      const start = await resolveRead(worktree, dir || ".");
      if (!(await stat(start)).isDirectory()) throw new Error(`${dir} is a file, not a folder.`);
      return toolJson(await listFiles(root, start, LIMIT));
    } catch (error) {
      return toolError("Couldn't list files", error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
