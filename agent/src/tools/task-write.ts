import path from "node:path";
import { resolveTaskPath } from "../feature-api.ts";
import { isLocked } from "../locked-files.ts";
import { assertWritable, findTask, loadTasks, requireTaskWorktree } from "../task-api.ts";

/** The absolute path a task file tool may write [rel] to: an active task, inside its worktree, not a locked skeleton file. */
export async function writablePath(projectId: number, featureId: number, number: number, rel: string): Promise<string> {
  const tasks = await loadTasks(projectId, featureId);
  const task = findTask(tasks, number);
  assertWritable(task, tasks);
  const worktree = requireTaskWorktree(task);
  const file = await resolveTaskPath(worktree, rel);
  const relative = path.relative(await resolveTaskPath(worktree, "."), file).split(path.sep).join("/");
  if (await isLocked(worktree, relative)) {
    throw new Error(`${relative} is a locked skeleton file; the spec can't change it. Tell the user.`);
  }
  return file;
}
