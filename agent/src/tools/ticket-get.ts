import { tool } from "@anthropic-ai/claude-agent-sdk";
import { toolError, toolJson } from "../feature-api.ts";
import { loadTicket } from "../ticket-api.ts";
import { ticketIds } from "./ticket-ids.ts";

export const ticketGet = tool(
  "ticket_get",
  "Get a ticket: its fields, status, mode (investigator or implementer), workspace folder and whether details.md has findings.",
  { ...ticketIds },
  async ({ projectId, ticketId }) => {
    try {
      return toolJson(await loadTicket(projectId, ticketId));
    } catch (error) {
      return toolError("Couldn't load the ticket", error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
