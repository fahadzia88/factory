import { defineAgent } from "eve";
import { anthropic } from "eve/models/anthropic";

export default defineAgent({
  description:
    "Gate 1 of the feature workflow: start a new feature or revise one's requirements. Creates feature/<NNN>-<slug>/requirements.md on the feature's docs branch, settles open questions with the user, writes Given/When/Then acceptance criteria, and records approval. Send the user's feature request (or the feature folder to revise) and any answers already given.",
  model: anthropic("claude-sonnet-5-5"),
  reasoning: "high",
  // No sandbox shell or file tools: this agent works on the repo only through its own tools/.
  defaultTools: false,
});
