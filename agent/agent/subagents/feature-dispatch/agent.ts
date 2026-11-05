import { defineAgent } from "eve";
import { anthropic } from "eve/models/anthropic";

export default defineAgent({
  description:
    "Step 4 coordinator for a feature whose specs are approved: opens the docs PR, sends app and agent tasks one at a time to its stacked-pr subagent, hands api and infra tasks to the user for Claude Code, tracks progress in task-breakdown.md, and restacks after merges. Send the feature folder and what happened (start, a PR merged, a task finished in Claude Code with its PR link).",
  model: anthropic("claude-sonnet-5-5"),
  reasoning: "high",
  // No sandbox shell or file tools: this agent works on the repo only through its own tools/.
  defaultTools: false,
});
