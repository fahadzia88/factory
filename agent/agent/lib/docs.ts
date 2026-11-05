/** Feature docs: frontmatter status rules from feature/rules.md, and listing features. */
import { docsBranch, featureBranches, listFiles, tryReadText } from "./repo";

export const STATUSES = ["draft", "approved", "in-progress", "done"] as const;
export type DocStatus = (typeof STATUSES)[number];

const FRONTMATTER = /^---\n([\s\S]*?)\n---\n?/;

export function frontmatter(content: string): Record<string, string> {
  const m = FRONTMATTER.exec(content);
  if (!m) return {};
  const fields: Record<string, string> = {};
  for (const line of m[1].split("\n")) {
    const kv = /^([A-Za-z_][\w-]*):\s*(.*)$/.exec(line);
    if (kv) fields[kv[1]] = kv[2].trim();
  }
  return fields;
}

function setField(content: string, key: string, value: string): string {
  const m = FRONTMATTER.exec(content);
  if (!m) throw new Error("The document has no frontmatter. Start it from the template in feature/_templates/.");
  const lines = m[1].split("\n");
  const i = lines.findIndex((l) => l.startsWith(`${key}:`));
  if (i >= 0) lines[i] = `${key}: ${value}`.trimEnd();
  else lines.push(`${key}: ${value}`.trimEnd());
  return content.replace(FRONTMATTER, `---\n${lines.join("\n")}\n---\n`);
}

/** Appends a line to the "## Changelog" section, creating it at the end if missing. */
function addChangelog(content: string, line: string): string {
  const entry = `- ${line}`;
  const heading = /^## Changelog[ \t]*$/m.exec(content);
  if (!heading) return `${content.trimEnd()}\n\n## Changelog\n\n${entry}\n`;
  const after = content.slice(heading.index + heading[0].length);
  const next = /^## /m.exec(after);
  const end = next ? heading.index + heading[0].length + next.index : content.length;
  const section = content.slice(0, end).trimEnd();
  return `${section}\n${entry}\n${next ? "\n" : ""}${content.slice(end)}`;
}

export function today(): string {
  return new Date().toISOString().slice(0, 10);
}

export function withStatus(content: string, status: DocStatus, changelog: string): string {
  let out = setField(content, "status", status);
  if (status === "approved") out = setField(out, "approved", today());
  if (status === "draft") out = setField(out, "approved", "");
  return addChangelog(out, `${today()} — ${changelog}`);
}

/**
 * The approval rule, enforced on every doc write: status only changes through doc_set_status,
 * except going back to draft; and an approved doc can't change without going back to draft.
 */
export function checkDocWrite(path: string, before: string | null, after: string): void {
  if (!path.endsWith(".md")) return;
  const next = frontmatter(after).status;
  const prev = before === null ? undefined : frontmatter(before).status;
  if (prev === undefined) {
    if (next && next !== "draft") {
      throw new Error(`New documents start as status: draft. Use doc_set_status to change it.`);
    }
    return;
  }
  if (next === prev) {
    if (prev === "approved" && before !== after) {
      throw new Error(
        `${path} is approved. Set it back to status: draft and add a changelog line in the same write before changing it; the user then approves it again.`,
      );
    }
    return;
  }
  if (next !== "draft") {
    throw new Error(`Status changes go through doc_set_status (${prev} → ${next} in ${path}).`);
  }
}

// ── Listing features ────────────────────────────────────────────────────────────────────────

export type FeatureSummary = {
  feature: string;
  source: string; // branch the docs were read from
  requirements?: string;
  breakdown?: string;
  specs: { task: string; status?: string }[];
};

const FEATURE_DIR = /^feature\/(\d{3}-[a-z0-9-]+)\//;

async function featuresOn(ref: string): Promise<Map<string, Set<string>>> {
  const files = await listFiles(ref, "feature");
  const map = new Map<string, Set<string>>();
  for (const f of files) {
    const m = FEATURE_DIR.exec(f);
    if (!m) continue;
    if (!map.has(m[1])) map.set(m[1], new Set());
    map.get(m[1])!.add(f);
  }
  return map;
}

/**
 * Every feature on main or on a feat/<NNN>-<slug>/00-docs branch. A docs branch wins over
 * main, because it holds the in-flight version.
 */
export async function listFeatures(): Promise<FeatureSummary[]> {
  const sources = new Map<string, { ref: string; files: Set<string> }>();
  for (const [feature, files] of await featuresOn("main")) sources.set(feature, { ref: "main", files });
  const docsBranches = (await featureBranches()).filter((b) => b.endsWith("/00-docs"));
  for (const branch of docsBranches) {
    const feature = branch.split("/")[1];
    const files = (await featuresOn(branch)).get(feature) ?? new Set<string>();
    sources.set(feature, { ref: branch, files });
  }

  const out: FeatureSummary[] = [];
  for (const [feature, { ref, files }] of [...sources].sort(([a], [b]) => a.localeCompare(b))) {
    const status = async (p: string) =>
      files.has(p) ? frontmatter((await tryReadText(ref, p)) ?? "").status : undefined;
    const specs = [...files]
      .map((f) => new RegExp(`^feature/${feature}/tasks/([^/]+)/spec\\.md$`).exec(f)?.[1])
      .filter((t): t is string => Boolean(t))
      .sort();
    out.push({
      feature,
      source: ref,
      requirements: await status(`feature/${feature}/requirements.md`),
      breakdown: await status(`feature/${feature}/task-breakdown.md`),
      specs: await Promise.all(
        specs.map(async (task) => ({ task, status: await status(`feature/${feature}/tasks/${task}/spec.md`) })),
      ),
    });
  }
  return out;
}

export async function nextFeatureNumber(): Promise<string> {
  const numbers = (await listFeatures()).map((f) => Number(f.feature.slice(0, 3)));
  return String(Math.max(0, ...numbers) + 1).padStart(3, "0");
}

/** The approved spec for a task branch, read from the feature's docs branch (or main). */
export async function approvedSpec(feature: string, task: string) {
  const p = `feature/${feature}/tasks/${task}/spec.md`;
  const content = (await tryReadText(docsBranch(feature), p)) ?? (await tryReadText("main", p));
  if (content === null) throw new Error(`No spec at ${p} on ${docsBranch(feature)} or main.`);
  const fm = frontmatter(content);
  if (fm.status !== "approved") {
    throw new Error(`${p} is ${fm.status ?? "missing a status"}, not approved. Specs must be approved before implementation.`);
  }
  return { path: p, frontmatter: fm };
}
