import { readFile, stat } from "node:fs/promises";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { resolveTaskPath, toolError, toolText } from "../feature-api.ts";
import { loadTicket, requireTicketWorktree } from "../ticket-api.ts";
import { ticketIds } from "./ticket-ids.ts";

const MAX_BYTES = 200 * 1024;

export const ticketReadFile = tool(
  "ticket_read_file",
  "Read a text file in a ticket's worktree (code, CLAUDE.md, details.md), up to 200 KB.",
  { ...ticketIds, path: z.string().min(1).describe("A path from the worktree's root") },
  async ({ projectId, ticketId, path }) => {
    try {
      const worktree = requireTicketWorktree(await loadTicket(projectId, ticketId));
      const file = await resolveTaskPath(worktree, path);
      const info = await stat(file).catch(() => undefined);
      if (!info?.isFile()) throw new Error(`${path} isn't a file in the ticket's worktree.`);
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
