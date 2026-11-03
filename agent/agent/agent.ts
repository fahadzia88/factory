import { defineAgent } from "eve";
import { anthropic } from "eve/models/anthropic";

export default defineAgent({
  // Direct Anthropic access; reads ANTHROPIC_API_KEY (see docker-compose.yml)
  model: anthropic("claude-sonnet-5-5"),
  reasoning: "medium",
});
