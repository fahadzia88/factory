import { stat } from "node:fs/promises";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { listFiles, resolveTaskPath, toolError, toolJson } from "../feature-api.ts";
import { loadTicket, requireTicketWorktree } from "../ticket-api.ts";
import { ticketIds } from "./ticket-ids.ts";

const LIMIT = 500;

export const ticketListFiles = tool(
  "ticket_list_files",
  `List files in a ticket's worktree, as paths from its root (.git left out, at most ${LIMIT}).`,
  { ...ticketIds, dir: z.string().optional().describe("A folder in the worktree; the root when left out") },
  async ({ projectId, ticketId, dir }) => {
    try {
      const worktree = requireTicketWorktree(await loadTicket(projectId, ticketId));
      const root = await resolveTaskPath(worktree, ".");
      const start = await resolveTaskPath(worktree, dir || ".");
      if (!(await stat(start)).isDirectory()) throw new Error(`${dir} is a file, not a folder.`);
      return toolJson(await listFiles(root, start, LIMIT));
    } catch (error) {
      return toolError("Couldn't list files", error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
