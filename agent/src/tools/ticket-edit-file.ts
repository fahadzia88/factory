import { readFile, writeFile } from "node:fs/promises";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { toolError, toolJson } from "../feature-api.ts";
import { ticketIds } from "./ticket-ids.ts";
import { ticketWritablePath } from "./ticket-write.ts";

export const ticketEditFile = tool(
  "ticket_edit_file",
  "Replace one exact, unique piece of text in a file in a ticket's worktree. Only after the user approved the investigation.",
  {
    ...ticketIds,
    path: z.string().min(1).describe("A path from the worktree's root"),
    oldText: z.string().min(1).describe("Text that appears exactly once in the file"),
    newText: z.string(),
  },
  async ({ projectId, ticketId, path: rel, oldText, newText }) => {
    try {
      const file = await ticketWritablePath(projectId, ticketId, rel);
      let text: string;
      try {
        text = await readFile(file, "utf8");
      } catch {
        throw new Error(`${rel} doesn't exist; use ticket_write_file.`);
      }
      const at = text.indexOf(oldText);
      if (at < 0) throw new Error(`oldText isn't in ${rel}.`);
      let count = 0;
      for (let i = at; i >= 0; i = text.indexOf(oldText, i + oldText.length)) count++;
      if (count > 1) throw new Error(`oldText appears ${count} times in ${rel}; include more surrounding text.`);
      await writeFile(file, text.slice(0, at) + newText + text.slice(at + oldText.length), "utf8");
      return toolJson({ path: rel, replaced: 1 });
    } catch (error) {
      return toolError(`Couldn't edit ${rel}`, error);
    }
  },
);
