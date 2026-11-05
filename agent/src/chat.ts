import { type SDKMessage, query } from "@anthropic-ai/claude-agent-sdk";
import { agentOptions } from "./agent.ts";

/** One line of a chat response stream (NDJSON). See README.md, "HTTP API". */
export type ChatEvent =
  | { type: "session"; sessionId: string }
  | { type: "text"; delta: string }
  | { type: "tool"; id: string; name: string; status: "running" | "completed" | "failed" }
  | { type: "done"; sessionId: string; text: string }
  | { type: "cancelled"; sessionId: string }
  | { type: "error"; sessionId: string; message: string };

export type Turn = {
  message: string;
  sessionId: string;
  /** True to continue a stored session, false to start one with this id. */
  resume: boolean;
  /** Working directory sessions are stored under; it must stay the same for `resume` to find them. */
  cwd: string;
  abortController: AbortController;
};

/** Runs one turn of the conversation and reports it through [emit]; never throws. */
export async function runTurn(turn: Turn, emit: (event: ChatEvent) => void): Promise<void> {
  const { sessionId, abortController } = turn;
  emit({ type: "session", sessionId });
  try {
    const messages = query({
      prompt: turn.message,
      options: {
        ...agentOptions,
        cwd: turn.cwd,
        ...(turn.resume ? { resume: sessionId } : { sessionId }),
        abortController,
        includePartialMessages: true,
        // Isolated from the host: no settings files, no built-in tools (Bash, Read, Write, …), no prompts
        settingSources: [],
        tools: [],
        permissionMode: "dontAsk",
        permissionPrompts: "none",
        // User text is passed as written: no @file expansion or slash commands
        verbatimPrompts: true,
      },
    });
    const toolNames = new Map<string, string>();
    for await (const message of messages) {
      const done = handle(message, sessionId, toolNames, emit);
      if (done) return;
    }
    emit({ type: "error", sessionId, message: "The agent stopped without answering" });
  } catch (error) {
    if (abortController.signal.aborted) {
      emit({ type: "cancelled", sessionId });
      return;
    }
    emit({ type: "error", sessionId, message: error instanceof Error ? error.message : String(error) });
  }
}

/** Maps one SDK message to stream events; true once the turn's result is in. Subagent messages are skipped. */
function handle(
  message: SDKMessage,
  sessionId: string,
  toolNames: Map<string, string>,
  emit: (event: ChatEvent) => void,
): boolean {
  switch (message.type) {
    case "stream_event": {
      const { event } = message;
      if (message.parent_tool_use_id === null && event.type === "content_block_delta" && event.delta.type === "text_delta") {
        emit({ type: "text", delta: event.delta.text });
      }
      return false;
    }
    case "assistant":
      if (message.parent_tool_use_id !== null) return false;
      for (const block of message.message.content) {
        if (block.type !== "tool_use") continue;
        toolNames.set(block.id, block.name);
        emit({ type: "tool", id: block.id, name: block.name, status: "running" });
      }
      return false;
    case "user": {
      const { content } = message.message;
      if (message.parent_tool_use_id !== null || typeof content === "string") return false;
      for (const block of content) {
        if (block.type === "tool_result") {
          const name = toolNames.get(block.tool_use_id) ?? "";
          emit({ type: "tool", id: block.tool_use_id, name, status: block.is_error ? "failed" : "completed" });
        }
      }
      return false;
    }
    case "result":
      if (message.subtype === "success" && !message.is_error) {
        emit({ type: "done", sessionId, text: message.result });
      } else {
        const detail = message.subtype === "success" ? message.result : message.errors.join("; ");
        emit({ type: "error", sessionId, message: detail || `The agent's turn failed (${message.subtype})` });
      }
      return true;
    default:
      return false;
  }
}
