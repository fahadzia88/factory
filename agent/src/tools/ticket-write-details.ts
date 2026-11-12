import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { resolveTaskPath, toolError, toolJson } from "../feature-api.ts";
import { detailsPath, loadTicket, requireTicketWorktree } from "../ticket-api.ts";
import { ticketIds } from "./ticket-ids.ts";

export const ticketWriteDetails = tool(
  "ticket_write_details",
  "Write the whole details.md of a ticket: your findings and plan. Allowed in both modes. Keep the headings (Description, Findings, Plan).",
  { ...ticketIds, content: z.string().min(1).max(200_000).describe("The new content of details.md") },
  async ({ projectId, ticketId, content }) => {
    try {
      const ticket = await loadTicket(projectId, ticketId);
      const worktree = requireTicketWorktree(ticket);
      const rel = detailsPath(ticket);
      const file = await resolveTaskPath(worktree, rel);
      await mkdir(path.dirname(file), { recursive: true });
      await writeFile(file, content, "utf8");
      return toolJson({ path: rel, bytes: Buffer.byteLength(content, "utf8") });
    } catch (error) {
      return toolError("Couldn't write details.md", error);
    }
  },
);
