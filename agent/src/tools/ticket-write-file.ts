import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { toolError, toolJson } from "../feature-api.ts";
import { ticketIds } from "./ticket-ids.ts";
import { ticketWritablePath } from "./ticket-write.ts";

export const ticketWriteFile = tool(
  "ticket_write_file",
  "Write a whole file in a ticket's worktree (create or replace). Only after the user approved the investigation; locked skeleton files are refused. The user commits and pushes from the page.",
  { ...ticketIds, path: z.string().min(1).describe("A path from the worktree's root"), content: z.string().max(500_000) },
  async ({ projectId, ticketId, path: rel, content }) => {
    try {
      const file = await ticketWritablePath(projectId, ticketId, rel);
      await mkdir(path.dirname(file), { recursive: true });
      await writeFile(file, content, "utf8");
      return toolJson({ path: rel, bytes: Buffer.byteLength(content, "utf8") });
    } catch (error) {
      return toolError(`Couldn't write ${rel}`, error);
    }
  },
);
