import { defineAgent } from "eve";
import { anthropic } from "eve/models/anthropic";

export default defineAgent({
  description:
    "Gate 3: write a detailed implementation spec for each task in an approved breakdown (tasks/<NN>-<layer>-<slug>/spec.md). Send the feature folder, and a task number to write or revise only that one.",
  model: anthropic("claude-sonnet-5-5"),
  reasoning: "high",
  // No sandbox shell or file tools: this agent works on the repo only through its own tools/.
  defaultTools: false,
});
