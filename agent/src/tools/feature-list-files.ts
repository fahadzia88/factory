import { readdir, stat } from "node:fs/promises";
import path from "node:path";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { loadFeature, requireWorktree, resolveRead, toolError, toolJson } from "../feature-api.ts";
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
      const files: string[] = [];
      let truncated = false;
      const queue = [start];
      // Breadth-first; Dirent.isDirectory() is false for a symlink, so symlinked folders aren't followed
      while (queue.length > 0 && !truncated) {
        const folder = queue.shift() as string;
        for (const entry of await readdir(folder, { withFileTypes: true })) {
          if (entry.name === ".git") continue;
          const full = path.join(folder, entry.name);
          if (entry.isDirectory()) {
            queue.push(full);
          } else if (files.length < LIMIT) {
            files.push(path.relative(root, full));
          } else {
            truncated = true;
            break;
          }
        }
      }
      return toolJson({ files: files.sort(), truncated });
    } catch (error) {
      return toolError("Couldn't list files", error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
