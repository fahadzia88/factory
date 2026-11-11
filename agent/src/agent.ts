import { readFileSync } from "node:fs";
import { type Options, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { askQuestions } from "./tools/ask-questions.ts";
import { featureAddStep } from "./tools/feature-add-step.ts";
import { featureCommit } from "./tools/feature-commit.ts";
import { featureEditFile } from "./tools/feature-edit-file.ts";
import { featureGet } from "./tools/feature-get.ts";
import { featureListFiles } from "./tools/feature-list-files.ts";
import { featureReadFile } from "./tools/feature-read-file.ts";
import { featureReadSkill } from "./tools/feature-read-skill.ts";
import { featureSetStatus } from "./tools/feature-set-status.ts";
import { featureUpdateStep } from "./tools/feature-update-step.ts";
import { featureWriteFile } from "./tools/feature-write-file.ts";
import { getProject } from "./tools/get-project.ts";
import { listProjects } from "./tools/list-projects.ts";
import { taskCommit } from "./tools/task-commit.ts";
import { taskEditFile } from "./tools/task-edit-file.ts";
import { taskListFiles } from "./tools/task-list-files.ts";
import { taskReadFile } from "./tools/task-read-file.ts";
import { taskWriteFile } from "./tools/task-write-file.ts";

/**
 * The project's tools, built with `tool()` from the SDK (see README.md, "Tools").
 * They are served to the model as the in-process MCP server `app`, so the model sees them as `mcp__app__<name>`.
 */
const tools: Parameters<typeof createSdkMcpServer>[0]["tools"] = [
  listProjects,
  getProject,
  featureGet,
  featureReadSkill,
  featureListFiles,
  featureReadFile,
  featureWriteFile,
  featureEditFile,
  featureCommit,
  featureSetStatus,
  featureAddStep,
  featureUpdateStep,
  askQuestions,
  taskListFiles,
  taskReadFile,
  taskWriteFile,
  taskEditFile,
  taskCommit,
];

const instructions = readFileSync(new URL("./instructions.md", import.meta.url), "utf8");

/** Per-project agent settings, merged into every `query()` call by chat.ts. */
export const agentOptions: Options = {
  // Direct Anthropic access; reads ANTHROPIC_API_KEY (see docker-compose.yml)
  model: "claude-sonnet-5-5",
  effort: "medium",
  systemPrompt: instructions,
  mcpServers: tools.length > 0 ? { app: createSdkMcpServer({ name: "app", tools }) } : {},
  // Only the project's own tools run, without asking
  allowedTools: ["mcp__app"],
};
