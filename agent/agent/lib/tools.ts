/**
 * Tools shared by the feature-workflow subagents. Each subagent's tools/ folder re-exports the
 * ones it gets, so its tool list is visible from its directory.
 *
 * Two write scopes:
 * - "docs": the feature's 00-docs branch, files under feature/<NNN>-<slug>/ only.
 * - "code": app and agent task branches, files under that layer's folder only, and only once
 *   the task's spec is approved.
 */
import { defineTool } from "eve/tools";
import { z } from "zod";
import { runChecks } from "./checks";
import { approvedSpec, checkDocWrite, listFeatures, nextFeatureNumber, STATUSES, withStatus } from "./docs";
import * as gh from "./github";
import * as repo from "./repo";

type Scope = "docs" | "code";

const branchArg = z.string().describe("Feature branch, e.g. feat/001-patient-search/00-docs");
const refArg = z
  .string()
  .default("main")
  .describe('Branch to read: "main" or a feature branch. Reads uncommitted work when this agent has a worktree for it.');

/** Parses the branch and checks it belongs to the scope; returns the folder it may write. */
function scoped(scope: Scope, branch: string): { fb: repo.FeatureBranch; prefix: string } {
  const fb = repo.parseFeatureBranch(branch);
  if (scope === "docs" && fb.layer !== "docs") {
    throw new Error(`Docs are written on the 00-docs branch, not ${branch}.`);
  }
  if (scope === "code" && fb.layer === "docs") {
    throw new Error(`Code is written on a task branch, not the docs branch ${branch}.`);
  }
  return { fb, prefix: repo.writablePrefix(fb) };
}

// ── Reading ─────────────────────────────────────────────────────────────────────────────────

export const repoList = defineTool({
  description: "List files in the repository on a branch, optionally under a folder.",
  inputSchema: z.object({ ref: refArg, path: z.string().default("").describe("Folder, e.g. app/app/routes") }),
  label: { start: ({ path, ref }) => `List ${path || "/"} on ${ref}` },
  async execute({ ref, path }) {
    const files = await repo.listFiles(ref, path);
    return { count: files.length, files: files.slice(0, 600), truncated: files.length > 600 };
  },
});

export const repoRead = defineTool({
  description: "Read a text file from the repository on a branch, with line numbers.",
  inputSchema: z.object({
    ref: refArg,
    path: z.string().describe("Repo-relative path, e.g. api/CLAUDE.md"),
    offset: z.number().int().min(1).default(1).describe("First line to return"),
    limit: z.number().int().min(1).max(2000).default(800),
  }),
  label: { start: ({ path, ref }) => `Read ${path} on ${ref}` },
  async execute({ ref, path, offset, limit }) {
    const lines = (await repo.readText(ref, path)).split("\n");
    const slice = lines.slice(offset - 1, offset - 1 + limit);
    return {
      path,
      totalLines: lines.length,
      content: slice.map((l, i) => `${offset + i}\t${l}`).join("\n"),
      more: offset - 1 + limit < lines.length,
    };
  },
});

export const repoSearch = defineTool({
  description: "Search file contents on a branch with an extended regular expression (git grep). Returns path:line:text.",
  inputSchema: z.object({
    ref: refArg,
    pattern: z.string().min(1),
    path: z.string().default("").describe("Limit to a folder or file"),
  }),
  label: { start: ({ pattern, ref }) => `Search ${ref} for ${pattern}` },
  async execute({ ref, pattern, path }) {
    const out = await repo.search(ref, pattern, path);
    return { matches: repo.cap(out, 20_000) };
  },
});

export const featureList = defineTool({
  description:
    "List every feature (on main and on feat/<NNN>-<slug>/00-docs branches) with the status of its requirements, breakdown and task specs, plus the next free feature number.",
  inputSchema: z.object({}),
  label: { start: () => "List features" },
  async execute() {
    return { features: await listFeatures(), nextNumber: await nextFeatureNumber() };
  },
});

// ── Git ─────────────────────────────────────────────────────────────────────────────────────

export function gitCheckout(scope: Scope) {
  return defineTool({
    description:
      scope === "docs"
        ? "Open the worktree for a feature's docs branch feat/<NNN>-<slug>/00-docs, creating the branch from main if it doesn't exist. Call before writing docs."
        : "Open the worktree for an app or agent task branch. Creates the branch from `base` (the spec's base) if it doesn't exist. Refuses unless the task's spec is approved.",
    inputSchema: z.object({
      branch: branchArg,
      base: z.string().optional().describe(scope === "docs" ? "Defaults to main" : "The spec's base branch"),
    }),
    label: { start: ({ branch }) => `Check out ${branch}` },
    async execute({ branch, base }) {
      const { fb } = scoped(scope, branch);
      if (scope === "code") {
        const spec = await approvedSpec(fb.feature, fb.task);
        if (spec.frontmatter.branch && spec.frontmatter.branch !== branch) {
          throw new Error(`The spec's branch is ${spec.frontmatter.branch}, not ${branch}.`);
        }
        if (base && spec.frontmatter.base && spec.frontmatter.base !== base) {
          throw new Error(`The spec's base is ${spec.frontmatter.base}, not ${base}.`);
        }
      }
      const result = await repo.checkout(branch, base ?? (scope === "docs" ? "main" : undefined));
      const pulled = result.created
        ? undefined
        : await repo.pull(branch).catch((e: Error) => `not fast-forwarded: ${e.message}`);
      return { ...result, pulled, writes: repo.writablePrefix(fb) };
    },
  });
}

export const gitStatus = defineTool({
  description: "Show a worktree's status, its commits since `base`, and the diff stat (or full patch).",
  inputSchema: z.object({
    branch: branchArg,
    base: z.string().optional().describe("Compare against this branch"),
    patch: z.boolean().default(false).describe("Include the full diff instead of the stat"),
  }),
  label: { start: ({ branch }) => `Status of ${branch}` },
  async execute({ branch, base, patch }) {
    return repo.status(branch, base, patch);
  },
});

export function gitCommit(scope: Scope) {
  return defineTool({
    description: "Stage every change under the branch's own folder and commit it.",
    inputSchema: z.object({
      branch: branchArg,
      message: z.string().min(1).describe('e.g. "001 02-app: patient search page"'),
    }),
    label: { start: ({ branch }) => `Commit on ${branch}` },
    async execute({ branch, message }) {
      const { prefix } = scoped(scope, branch);
      return repo.commit(branch, message, prefix);
    },
  });
}

export function gitPush(scope: Scope) {
  return defineTool({
    description:
      "Push a feature branch to origin and set its upstream. forceWithLease is only for a restacked task branch.",
    inputSchema: z.object({ branch: branchArg, forceWithLease: z.boolean().default(false) }),
    label: { start: ({ branch }) => `Push ${branch}` },
    async execute({ branch, forceWithLease }) {
      const { fb } = scoped(scope, branch);
      if (forceWithLease && fb.layer === "docs") throw new Error("Never force-push the docs branch.");
      return { pushed: branch, head: await repo.push(branch, forceWithLease) };
    },
  });
}

export const gitRebase = defineTool({
  description:
    "Restack a task branch. Base PR still open: pass only `upstream` (the base branch) to rebase onto origin/<upstream>. Base PR merged: pass `upstream` (the old base branch) and onto: \"main\". Returns conflicts if any.",
  inputSchema: z.object({ branch: branchArg, upstream: z.string(), onto: z.literal("main").optional() }),
  label: { start: ({ branch }) => `Rebase ${branch}` },
  async execute({ branch, upstream, onto }) {
    scoped("code", branch);
    return repo.rebase(branch, upstream, onto);
  },
});

export const gitRebaseControl = defineTool({
  description: "After fixing rebase conflicts with code_write/code_edit: `continue`. To give up: `abort`.",
  inputSchema: z.object({ branch: branchArg, action: z.enum(["continue", "abort"]) }),
  label: { start: ({ branch, action }) => `Rebase ${action} on ${branch}` },
  async execute({ branch, action }) {
    const { prefix } = scoped("code", branch);
    return repo.rebaseControl(branch, action, prefix);
  },
});

// ── Writing ─────────────────────────────────────────────────────────────────────────────────

async function guardedWrite(scope: Scope, branch: string, path: string, next: string) {
  const { prefix } = scoped(scope, branch);
  if (scope === "docs") checkDocWrite(path, await repo.tryReadText(branch, path), next);
  await repo.writeText(branch, path, next, prefix);
}

export function fileWrite(scope: Scope) {
  return defineTool({
    description:
      scope === "docs"
        ? "Write a whole file under feature/<NNN>-<slug>/ on the feature's docs branch. Status changes (other than back to draft) go through doc_set_status."
        : "Write a whole file under the task's layer folder (app/ or agent/) on its task branch.",
    inputSchema: z.object({ branch: branchArg, path: z.string(), content: z.string() }),
    label: { start: ({ path }) => `Write ${path}` },
    async execute({ branch, path, content }) {
      await guardedWrite(scope, branch, path, content);
      return { written: path, bytes: Buffer.byteLength(content) };
    },
  });
}

export function fileEdit(scope: Scope) {
  return defineTool({
    description:
      "Replace an exact string in a file on the branch. `old` must match exactly once unless replaceAll is set.",
    inputSchema: z.object({
      branch: branchArg,
      path: z.string(),
      old: z.string().min(1),
      new: z.string(),
      replaceAll: z.boolean().default(false),
    }),
    label: { start: ({ path }) => `Edit ${path}` },
    async execute({ branch, path, old, new: replacement, replaceAll }) {
      const current = await repo.readText(branch, path);
      const count = current.split(old).length - 1;
      if (count === 0) throw new Error(`The text to replace isn't in ${path}. Read the file again and copy it exactly.`);
      if (count > 1 && !replaceAll) throw new Error(`The text appears ${count} times in ${path}. Add context or set replaceAll.`);
      const next = replaceAll ? current.split(old).join(replacement) : current.replace(old, () => replacement);
      await guardedWrite(scope, branch, path, next);
      return { edited: path, replacements: replaceAll ? count : 1 };
    },
  });
}

export const codeDelete = defineTool({
  description: "Delete a file under the task's layer folder on its task branch.",
  inputSchema: z.object({ branch: branchArg, path: z.string() }),
  label: { start: ({ path }) => `Delete ${path}` },
  async execute({ branch, path }) {
    const { prefix } = scoped("code", branch);
    await repo.deleteFile(branch, path, prefix);
    return { deleted: path };
  },
});

export const docSetStatus = defineTool({
  description:
    "Set the status of one or more feature docs (requirements.md, task-breakdown.md, spec.md) on the docs branch and add a changelog line. Setting `approved` asks the user to confirm; call it only after the user has said yes.",
  inputSchema: z.object({
    branch: branchArg,
    paths: z.array(z.string()).min(1),
    status: z.enum(STATUSES),
    changelog: z.string().min(1).describe('e.g. "Approved." or "Back to draft: AC-3 changed."'),
  }),
  approval: {
    request: ({ toolInput }) => (toolInput?.status === "approved" ? "user-approval" : "not-applicable"),
    prompt: ({ input }) => `Mark ${input.paths.join(", ")} as approved?`,
  },
  label: { start: ({ paths, status }) => `Set ${paths.length} doc(s) to ${status}` },
  async execute({ branch, paths, status, changelog }) {
    const { prefix } = scoped("docs", branch);
    for (const p of paths) {
      const content = await repo.readText(branch, p);
      await repo.writeText(branch, p, withStatus(content, status, changelog), prefix);
    }
    return { updated: paths, status };
  },
});

// ── Checks ──────────────────────────────────────────────────────────────────────────────────

export const runFolderChecks = defineTool({
  description:
    "Run the layer's checks in the task worktree: app = npm ci, typecheck, build, prettier --check on changed files (format: true runs prettier --write first); agent = npm ci, typecheck.",
  inputSchema: z.object({
    branch: branchArg,
    base: z.string().describe("The spec's base branch, used to find changed files"),
    format: z.boolean().default(true),
  }),
  label: { start: ({ branch }) => `Run checks on ${branch}` },
  async execute({ branch, base, format }, ctx) {
    const { fb } = scoped("code", branch);
    return runChecks(branch, fb.layer as "app" | "agent", base, { format }, ctx.abortSignal);
  },
});

// ── Pull requests ───────────────────────────────────────────────────────────────────────────

export const prCreate = defineTool({
  description:
    "Open a pull request from a pushed feature branch. Task PRs target the previous task's branch, never main; only the 00-docs PR targets main. Returns the existing PR if one is open.",
  inputSchema: z.object({
    head: branchArg,
    base: z.string(),
    title: z.string().min(1),
    body: z.string().min(1),
    draft: z.boolean().default(false),
  }),
  label: { start: ({ head, base }) => `Open PR ${head} → ${base}` },
  async execute(input) {
    const fb = repo.parseFeatureBranch(input.head);
    if (input.base === "main" && fb.layer !== "docs") {
      throw new Error("A task PR targets the branch below it in the stack. Only 00-docs targets main (until restacking).");
    }
    return gh.createPr(input);
  },
});

export const prView = defineTool({
  description: "Look up pull requests: by number, by head branch, or every PR of a feature (NNN-slug).",
  inputSchema: z.object({
    number: z.number().int().optional(),
    branch: z.string().optional(),
    feature: z.string().optional().describe("e.g. 001-patient-search"),
  }),
  label: { start: () => "Look up PRs" },
  async execute({ number, branch, feature }) {
    if (number) return { prs: [await gh.getPr(number)] };
    if (branch) {
      const pr = await gh.findPr(branch);
      return { prs: pr ? [pr] : [] };
    }
    if (feature) return { prs: await gh.featurePrs(feature) };
    throw new Error("Pass number, branch or feature.");
  },
});

export const prComment = defineTool({
  description: "Post a comment on a feature pull request.",
  inputSchema: z.object({ number: z.number().int(), body: z.string().min(1) }),
  label: { start: ({ number }) => `Comment on PR #${number}` },
  async execute({ number, body }) {
    const pr = await gh.getPr(number);
    repo.parseFeatureBranch(pr.head);
    return gh.comment(number, body);
  },
});

export const prSetBase = defineTool({
  description: "Change a task PR's base branch, e.g. to main after the PR below it merged.",
  inputSchema: z.object({ number: z.number().int(), base: z.string() }),
  label: { start: ({ number, base }) => `Retarget PR #${number} to ${base}` },
  async execute({ number, base }) {
    return gh.setBase(number, base);
  },
});
