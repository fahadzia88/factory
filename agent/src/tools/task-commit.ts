import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiPost } from "../api.ts";
import { toolError, toolJson, toolText } from "../feature-api.ts";
import { commitAll, currentBranch, hasUnpushed, serialFor } from "../feature-git.ts";
import { assertWritable, findTask, loadTasks, requireTaskWorktree } from "../task-api.ts";
import { taskIds } from "./task-ids.ts";

export const taskCommit = tool(
  "task_commit",
  "Commit every change in a task's worktree and push its branch through the API. Also pushes an earlier commit whose push failed.",
  { ...taskIds, message: z.string().min(1).max(200).describe("e.g. 006 03-api: task records") },
  async ({ projectId, featureId, number, message }) => {
    try {
      const tasks = await loadTasks(projectId, featureId);
      const task = findTask(tasks, number);
      assertWritable(task, tasks);
      const root = requireTaskWorktree(task);
      const worktreeId = task.worktree?.id as number;
      return await serialFor(root, async () => {
        const branch = await currentBranch(root);
        if (branch !== task.branch) throw new Error(`The worktree has ${branch} checked out, not ${task.branch}; nothing was committed.`);
        const commit = await commitAll(root, message);
        if (!commit.committed && !(await hasUnpushed(root))) return toolText(`Nothing to commit in task ${number}.`);
        try {
          const pushed = await apiPost<{ branch: string; head: string }>(
            `/api/projects/${projectId}/checkout/worktrees/${worktreeId}/push`,
            { force: false },
            150_000,
          );
          return toolJson({ committed: commit.committed, sha: commit.committed ? commit.sha : null, pushed: pushed.head, branch: pushed.branch });
        } catch (error) {
          const detail = error instanceof Error ? error.message : String(error);
          const text = commit.committed ? `Committed ${commit.sha} locally, but the push failed: ${detail}` : `The push failed: ${detail}`;
          return { content: [{ type: "text" as const, text }], isError: true };
        }
      });
    } catch (error) {
      return toolError("Couldn't commit", error);
    }
  },
);
