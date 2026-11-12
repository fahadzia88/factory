import { existsSync } from "node:fs";
import path from "node:path";
import { apiGet } from "./api.ts";

/** A ticket as `GET /api/projects/{id}/tickets/{ticketId}` returns it. */
export type Ticket = {
  id: number;
  projectId: number;
  number: number;
  type: "bug" | "improvement";
  title: string;
  description: string | null;
  priority: string;
  status: string;
  statusDescription: string;
  allowedStatuses: string[];
  pullRequestUrl: string | null;
  pullNumber: number | null;
  workspace: {
    state: "none" | "ready" | "failed";
    error: string | null;
    branch: string | null;
    folder: string | null;
    worktree: { id: number; path: string; missing: boolean } | null;
  };
  mode: "investigator" | "implementer";
  approvedAt: string | null;
  hasFindings: boolean;
  createdAt: string;
  updatedAt: string;
};

/** Loads a ticket from the API; errors are `ApiError`s whose message is the problem's `detail`. */
export function loadTicket(projectId: number, ticketId: number): Promise<Ticket> {
  return apiGet<Ticket>(`/api/projects/${projectId}/tickets/${ticketId}`);
}

/** The ticket's `details.md`, as a path from its worktree's root. */
export function detailsPath(ticket: Ticket): string {
  const folder = ticket.workspace.folder;
  if (!folder) throw new Error(noWorkspace());
  return `${folder}/details.md`;
}

/** The ticket's worktree folder, or an error saying the ticket has no workspace or its worktree is missing. */
export function requireTicketWorktree(ticket: Ticket): string {
  const { state, branch, worktree } = ticket.workspace;
  if (state !== "ready" || !branch || !ticket.workspace.folder) throw new Error(noWorkspace());
  if (!worktree || worktree.missing || !existsSync(path.join(worktree.path, ".git"))) {
    throw new Error(`The worktree for ${branch} is missing. Check it out again on the project page.`);
  }
  return worktree.path;
}

/** Refuses code changes while the ticket is in investigator mode; only `details.md` can be written then. */
export function assertTicketWritable(ticket: Ticket): void {
  if (ticket.mode !== "implementer") {
    throw new Error(
      "This ticket is in investigator mode: only details.md can be written until the user approves the investigation. " +
        "Write your findings and plan in details.md and ask the user to press Approve.",
    );
  }
}

function noWorkspace(): string {
  return "This ticket has no workspace yet; it gets one when it first moves to in progress.";
}
