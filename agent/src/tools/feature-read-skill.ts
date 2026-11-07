import { readFile } from "node:fs/promises";
import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { loadFeature, requireWorktree, resolveRead, toolError, toolText } from "../feature-api.ts";
import { featureIds } from "./feature-ids.ts";

export const featureReadSkill = tool(
  "feature_read_skill",
  "Read the skill that runs one step of the feature's workflow, from the project's own .claude/skills/ in the feature's worktree. Follow it to run the step.",
  { ...featureIds, step: z.string().min(1).describe("A step key from the feature's workflow") },
  async ({ projectId, featureId, step }) => {
    try {
      const feature = await loadFeature(projectId, featureId);
      const type = feature.workflow.steps.find((s) => s.key === step);
      if (!type) {
        throw new Error(`Unknown step ${step}. Steps: ${feature.workflow.steps.map((s) => s.key).join(", ")}.`);
      }
      const root = requireWorktree(feature);
      const rel = `.claude/skills/${type.skill}/SKILL.md`;
      const file = await resolveRead(root, rel);
      let text: string;
      try {
        text = await readFile(file, "utf8");
      } catch {
        throw new Error(`${rel} isn't in the feature's worktree.`);
      }
      return toolText(`# Skill ${type.skill} (from ${feature.branch})\n\n${text}`);
    } catch (error) {
      return toolError(`Couldn't read the skill for ${step}`, error);
    }
  },
  { annotations: { readOnlyHint: true } },
);
