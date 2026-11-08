import { readFile, stat } from "node:fs/promises";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { loadFeature, requireWorktree, resolveRead, toolError, toolText } from "../feature-api.ts";
import { featureIds } from "./feature-ids.ts";

const MAX_BYTES = 200 * 1024;

export const featureReadFile = tool(
  "feature_read_file",
  "Read a text file in the feature's worktree (rules, templates, code, the feature's documents), up to 200 KB.",
  { ...featureIds, path: z.string().min(1).describe("A path from the worktree's root, as feature_list_files prints it") },
  async ({ projectId, featureId, path }) => {
    try {
      const feature = await loadFeature(projectId, featureId);
      const file = await resolveRead(requireWorktree(feature), path);
      const info = await stat(file).catch(() => undefined);
      if (!info?.isFile()) throw new Error(`${path} isn't a file in the feature's worktree.`);
      if (info.size > MAX_BYTES) {
        throw new Error(`${path} is ${Math.ceil(info.size / 1024)} KB; only files up to 200 KB can be read.`);
      }
      return toolText(await readFile(file, "utf8"));
    } catch (error) {
      return toolError(`Couldn't read ${path}`, error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
