import { tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { apiPost } from "../api.ts";
import { toolError, toolText } from "../feature-api.ts";
import { featureIds } from "./feature-ids.ts";

const option = z.object({
  label: z.string().trim().min(1).max(60).describe("A short choice; don't add \"(Recommended)\" to it"),
  description: z.string().trim().min(1).max(200).describe("What choosing it means"),
});

const question = z.object({
  header: z.string().trim().min(1).max(12).describe("A short tag, e.g. \"Token\""),
  question: z.string().trim().min(1).max(300),
  multiSelect: z.boolean().default(false).describe("True only when several options can be true at once"),
  options: z
    .array(option)
    .min(2)
    .max(4)
    .describe("2 to 4 options, the recommended one first. Never an \"Other\" option: the app adds it"),
});

/** The question set the API accepts: 1 to 4 questions, labels unique per question, no "Other". */
export const questionsSchema = z
  .array(question)
  .min(1)
  .max(4)
  .superRefine((questions, context) => {
    questions.forEach((q, i) => {
      const labels = q.options.map((o) => o.label.toLowerCase());
      if (new Set(labels).size !== labels.length) {
        context.addIssue({ code: "custom", path: [i, "options"], message: "Option labels must differ" });
      }
      if (labels.includes("other")) {
        context.addIssue({ code: "custom", path: [i, "options"], message: "Don't offer \"Other\"; the app adds it" });
      }
    });
  });

export const askQuestions = tool(
  "ask_questions",
  "Ask the user 1 to 4 multiple-choice questions, shown as cards in the feature chat. Put the recommended option first; the user can always pick \"Other\" or type a reply. After calling it, end your turn: the answers arrive as the next message.",
  { ...featureIds, questions: questionsSchema },
  async ({ projectId, featureId, questions }) => {
    try {
      await apiPost(`/api/projects/${projectId}/features/${featureId}/questions`, { questions });
      return toolText("Shown to the user. End your turn now and wait for their answers; they arrive as your next message.");
    } catch (error) {
      return toolError("Couldn't show the questions", error);
    }
  },
);
