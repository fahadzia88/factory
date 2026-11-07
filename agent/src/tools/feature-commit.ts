import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiPost } from "../api.ts";
import { loadFeature, requireWorktree, toolError, toolJson, toolText } from "../feature-api.ts";
import { commitFolder, hasUnpushed, serialFor } from "../feature-git.ts";
import { featureIds } from "./feature-ids.ts";

export const featureCommit = tool(
  "feature_commit",
  "Commit the changes in the feature's folder (nothing else) and push the branch through the API. Also pushes an earlier commit whose push failed.",
  { ...featureIds, message: z.string().min(1).max(200).describe("A short commit message, e.g. 004-x: requirements draft") },
  async ({ projectId, featureId, message }) => {
    try {
      const feature = await loadFeature(projectId, featureId);
      const root = requireWorktree(feature);
      const worktreeId = feature.worktree?.id as number;
      return await serialFor(root, async () => {
        const commit = await commitFolder(root, feature.folder, message);
        if (!commit.committed && !(await hasUnpushed(root))) return toolText(`Nothing to commit in ${feature.folder}.`);
        try {
          const pushed = await apiPost<{ branch: string; head: string }>(
            `/api/projects/${projectId}/checkout/worktrees/${worktreeId}/push`,
            { force: false },
            150_000,
          );
          return toolJson({
            committed: commit.committed,
            sha: commit.committed ? commit.sha : null,
            pushed: pushed.head,
            branch: pushed.branch,
          });
        } catch (error) {
          const detail = error instanceof Error ? error.message : String(error);
          const text = commit.committed
            ? `Committed ${commit.sha} locally, but the push failed: ${detail}`
            : `The push failed: ${detail}`;
          return { content: [{ type: "text" as const, text }], isError: true };
        }
      });
    } catch (error) {
      return toolError("Couldn't commit", error);
    }
  },
);
