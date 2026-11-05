import { defineAgent } from "eve";
import { anthropic } from "eve/models/anthropic";

export default defineAgent({
  description:
    "Read-only report of where features are in the workflow: which gate, task and PR states, uncovered ACs, anything out of order, and the next action. Send a feature folder, or nothing to list every feature.",
  model: anthropic("claude-sonnet-5-5"),
  reasoning: "high",
  // No sandbox shell or file tools: this agent works on the repo only through its own tools/.
  defaultTools: false,
});
