/**
 * The folder checks from app/CLAUDE.md and agent/AGENTS.md, run in a task's worktree.
 * Only these fixed commands run, with no app secrets in their environment.
 */
import { spawn } from "node:child_process";
import path from "node:path";
import { cap, changedFiles, worktreeDir } from "./repo";

type Step = { name: string; cmd: string; args: string[] };
export type StepResult = { name: string; ok: boolean; output: string };

function run(step: Step, cwd: string, signal?: AbortSignal): Promise<StepResult> {
  return new Promise((resolve) => {
    const child = spawn(step.cmd, step.args, {
      cwd,
      signal,
      timeout: 15 * 60_000,
      env: {
        PATH: process.env.PATH ?? "/usr/local/bin:/usr/bin:/bin",
        HOME: process.env.HOME ?? "/root",
        CI: "1",
        NODE_ENV: "development",
      },
    });
    let output = "";
    child.stdout.on("data", (d) => (output += d));
    child.stderr.on("data", (d) => (output += d));
    child.on("error", (e) => resolve({ name: step.name, ok: false, output: `${output}\n${e.message}` }));
    child.on("close", (code) => {
      const ok = code === 0;
      // Keep the end of the log, where failures are reported.
      const text = output.length > 6000 ? `… ${output.slice(-6000)}` : output;
      resolve({ name: step.name, ok, output: ok ? cap(text, 800) : text });
    });
  });
}

export async function runChecks(
  branch: string,
  layer: "app" | "agent",
  base: string,
  options: { format: boolean },
  signal?: AbortSignal,
) {
  const root = worktreeDir(branch);
  const cwd = path.join(root, layer);
  const npm = (name: string, ...args: string[]): Step => ({ name, cmd: "npm", args });

  const steps: Step[] = [npm("install", "ci", "--no-audit", "--no-fund")];
  if (layer === "app") {
    const changed = (await changedFiles(branch, base, "app/"))
      .filter((f) => /\.(tsx?|jsx?|css|json|md)$/.test(f))
      .map((f) => f.slice("app/".length));
    if (changed.length && options.format) {
      steps.push({ name: "prettier --write", cmd: "npx", args: ["prettier", "--write", ...changed] });
    }
    steps.push(npm("typecheck", "run", "typecheck"), npm("build", "run", "build"));
    if (changed.length) {
      steps.push({ name: "prettier --check", cmd: "npx", args: ["prettier", "--check", ...changed] });
    }
  } else {
    steps.push(npm("typecheck", "run", "typecheck"));
  }

  const results: StepResult[] = [];
  for (const step of steps) {
    const result = await run(step, cwd, signal);
    results.push(result);
    if (!result.ok) break;
  }
  return { passed: results.length === steps.length && results.every((r) => r.ok), results };
}
