import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiGet } from "../api.ts";

type ProjectPage = { items: unknown[]; page: number; size: number; totalItems: number; totalPages: number };

const PAGE_SIZE = 100;
const MAX_PAGES = 20;

export const listProjects = tool(
  "list_projects",
  "List the factory's software projects, archived ones included unless includeArchived is false. " +
    "Returns name, repository URL, status, description, archived and repositoryVerified for each.",
  {
    q: z.string().optional().describe("Part of a project name"),
    status: z.enum(["planning", "active", "maintenance", "retired"]).optional(),
    includeArchived: z.boolean().optional().describe("Defaults to true"),
    sort: z.enum(["updatedAt", "name", "status"]).optional(),
    direction: z.enum(["asc", "desc"]).optional(),
  },
  async ({ q, status, includeArchived, sort, direction }) => {
    try {
      const projects: unknown[] = [];
      let totalItems = 0;
      let truncated = false;
      for (let page = 1; ; page++) {
        const result = await apiGet<ProjectPage>("/api/projects", {
          q,
          status,
          includeArchived: includeArchived ?? true,
          sort,
          direction,
          page,
          size: PAGE_SIZE,
        });
        projects.push(...result.items);
        totalItems = result.totalItems;
        if (page >= result.totalPages) break;
        if (page >= MAX_PAGES) {
          truncated = true;
          break;
        }
      }
      return { content: [{ type: "text", text: JSON.stringify({ totalItems, truncated, projects }) }] };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return { content: [{ type: "text", text: `Couldn't load projects: ${message}` }], isError: true };
    }
  },
  { annotations: { readOnlyHint: true } },
);
