import { defineAgent } from "eve";
import { anthropic } from "eve/models/anthropic";

export default defineAgent({
  description:
    "Gate 2: break a feature with approved requirements into stacked, layer-owned tasks (api, app, agent, infra), the contracts between layers, and AC coverage, in task-breakdown.md. Send the feature folder (e.g. 001-patient-search) and any change the user asked for.",
  model: anthropic("claude-sonnet-5-5"),
  reasoning: "high",
  // No sandbox shell or file tools: this agent works on the repo only through its own tools/.
  defaultTools: false,
});
