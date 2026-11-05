/** Pull requests through the GitHub REST API (no gh CLI). Uses GITHUB_TOKEN and GITHUB_REPO. */
import { Octokit } from "@octokit/rest";
import { githubToken, parseFeatureBranch, repoSlug } from "./repo";

let client: Octokit | undefined;
function gh(): Octokit {
  client ??= new Octokit({ auth: githubToken(), userAgent: "factory-agent" });
  return client;
}

export type PrSummary = {
  number: number;
  url: string;
  title: string;
  state: "open" | "closed" | "merged";
  draft: boolean;
  head: string;
  base: string;
  reviews: Record<string, string>; // reviewer → latest review state
};

type PullLike = {
  number: number;
  html_url: string;
  title: string;
  state: string;
  draft?: boolean;
  merged_at: string | null;
  head: { ref: string };
  base: { ref: string };
};

async function summarize(pr: PullLike): Promise<PrSummary> {
  const { owner, repo } = repoSlug();
  const { data: reviews } = await gh().pulls.listReviews({ owner, repo, pull_number: pr.number, per_page: 100 });
  const latest: Record<string, string> = {};
  for (const r of reviews) if (r.user && r.state !== "COMMENTED") latest[r.user.login] = r.state;
  return {
    number: pr.number,
    url: pr.html_url,
    title: pr.title,
    state: pr.merged_at ? "merged" : (pr.state as "open" | "closed"),
    draft: Boolean(pr.draft),
    head: pr.head.ref,
    base: pr.base.ref,
    reviews: latest,
  };
}

export async function findPr(branch: string): Promise<PrSummary | null> {
  const { owner, repo } = repoSlug();
  const { data } = await gh().pulls.list({ owner, repo, head: `${owner}:${branch}`, state: "all", per_page: 10 });
  const pr = data.find((p) => p.state === "open") ?? data[0];
  return pr ? summarize(pr) : null;
}

export async function getPr(number: number): Promise<PrSummary> {
  const { owner, repo } = repoSlug();
  const { data } = await gh().pulls.get({ owner, repo, pull_number: number });
  return summarize(data);
}

/** PRs whose head branch belongs to the feature (feat/<NNN>-<slug>/...). */
export async function featurePrs(feature: string): Promise<PrSummary[]> {
  const { owner, repo } = repoSlug();
  const prefix = `feat/${feature}/`;
  const all = await gh().paginate(gh().pulls.list, { owner, repo, state: "all", per_page: 100 });
  return Promise.all(all.filter((p) => p.head.ref.startsWith(prefix)).map(summarize));
}

export async function createPr(input: { head: string; base: string; title: string; body: string; draft?: boolean }) {
  parseFeatureBranch(input.head);
  if (input.base !== "main") parseFeatureBranch(input.base);
  const existing = await findPr(input.head);
  if (existing && existing.state === "open") return { created: false, pr: existing };
  const { owner, repo } = repoSlug();
  const { data } = await gh().pulls.create({ owner, repo, ...input });
  return { created: true, pr: await summarize(data) };
}

export async function comment(number: number, body: string) {
  const { owner, repo } = repoSlug();
  const { data } = await gh().issues.createComment({ owner, repo, issue_number: number, body });
  return { url: data.html_url };
}

export async function setBase(number: number, base: string) {
  if (base !== "main") parseFeatureBranch(base);
  const pr = await getPr(number);
  parseFeatureBranch(pr.head);
  const { owner, repo } = repoSlug();
  const { data } = await gh().pulls.update({ owner, repo, pull_number: number, base });
  return summarize(data);
}
