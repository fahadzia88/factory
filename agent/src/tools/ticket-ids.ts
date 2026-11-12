import { z } from "zod";

/** The ids every `ticket_*` tool takes, from the chat's `[Ticket context: …]` line. */
export const ticketIds = {
  projectId: z.number().int().positive().describe("From the [Ticket context] line"),
  ticketId: z.number().int().positive().describe("From the [Ticket context] line"),
};
