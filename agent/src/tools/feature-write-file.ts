import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { loadFeature, requireWorktree, resolveWrite, toolError, toolJson } from "../feature-api.ts";
import { featureIds } from "./feature-ids.ts";

export const featureWriteFile = tool(
  "feature_write_file",
  "Write a whole file in the feature's folder (create or replace). Only paths under the feature's folder are allowed. Commit with feature_commit after the document change.",
  {
    ...featureIds,
    path: z.string().min(1).describe("A path from the worktree's root, inside the feature folder, e.g. feature/004-x/requirements.md"),
    content: z.string().max(500_000),
  },
  async ({ projectId, featureId, path: rel, content }) => {
    try {
      const feature = await loadFeature(projectId, featureId);
      const file = await resolveWrite(requireWorktree(feature), feature.folder, rel);
      await mkdir(path.dirname(file), { recursive: true });
      await writeFile(file, content, "utf8");
      return toolJson({ path: rel, bytes: Buffer.byteLength(content, "utf8") });
    } catch (error) {
      return toolError(`Couldn't write ${rel}`, error);
    }
  },
);
