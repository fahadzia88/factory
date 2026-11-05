import { defineAgent } from "eve";
import { anthropic } from "eve/models/anthropic";

export default defineAgent({
  description:
    "Implement one approved app or agent task on its own branch stacked on the previous task, run the folder's checks, push, and open a PR against the previous branch; or restack a task branch after the PR below it changed or merged. Send the spec path, branch and base, the contract, and notes from earlier tasks; or \"restack <spec path>\".",
  model: anthropic("claude-sonnet-5-5"),
  reasoning: "high",
  // No sandbox shell or file tools: this agent works on the repo only through its own tools/.
  defaultTools: false,
});
