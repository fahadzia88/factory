import { readFileSync } from "node:fs";
import { type Options, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";

/**
 * The project's tools, built with `tool()` from the SDK (see README.md, "Tools").
 * They are served to the model as the in-process MCP server `app`, so the model sees them as `mcp__app__<name>`.
 */
const tools: Parameters<typeof createSdkMcpServer>[0]["tools"] = [];

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
