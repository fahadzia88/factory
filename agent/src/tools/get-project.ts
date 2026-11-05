import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { ApiError, apiGet } from "../api.ts";

export const getProject = tool(
  "get_project",
  "Get one project by id.",
  { id: z.number().int().positive() },
  async ({ id }) => {
    try {
      const project = await apiGet<unknown>(`/api/projects/${id}`);
      return { content: [{ type: "text", text: JSON.stringify(project) }] };
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return { content: [{ type: "text", text: JSON.stringify({ found: false, id }) }] };
      }
      const message = error instanceof Error ? error.message : String(error);
      return { content: [{ type: "text", text: `Couldn't load project ${id}: ${message}` }], isError: true };
    }
  },
  { annotations: { readOnlyHint: true } },
);
