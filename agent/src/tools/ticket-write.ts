import path from "node:path";
import { resolveTaskPath } from "../feature-api.ts";
import { isLocked } from "../locked-files.ts";
import { assertTicketWritable, loadTicket, requireTicketWorktree } from "../ticket-api.ts";

/**
 * The absolute path a ticket's code-writing tools may write [rel] to: the ticket must be in implementer mode (read
 * from the API on every call), the path inside its worktree and not in `.git`, and not a locked skeleton file.
 */
export async function ticketWritablePath(projectId: number, ticketId: number, rel: string): Promise<string> {
  const ticket = await loadTicket(projectId, ticketId);
  const worktree = requireTicketWorktree(ticket);
  assertTicketWritable(ticket);
  const file = await resolveTaskPath(worktree, rel);
  const relative = path.relative(await resolveTaskPath(worktree, "."), file).split(path.sep).join("/");
  if (await isLocked(worktree, relative)) {
    throw new Error(`${relative} is a locked skeleton file; the ticket can't change it. Tell the user.`);
  }
  return file;
}
