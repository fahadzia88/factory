import type { ReactNode } from "react"

import { StatusBadge } from "~/components/status-badge"
import { Badge } from "~/components/ui/badge"
import type { Worktree } from "~/lib/checkouts"
import { formatDateTime } from "~/lib/projects"

export function WorktreeList({
  worktrees,
  actions,
}: {
  worktrees: Worktree[]
  actions?: (worktree: Worktree) => ReactNode
}) {
  return (
    <ul className="divide-y">
      {worktrees.map((worktree) => (
        <li
          key={worktree.id}
          className="flex flex-col gap-3 py-3 sm:flex-row sm:items-start sm:justify-between"
        >
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm font-medium break-all">
                {worktree.branch}
              </span>
              {worktree.main && <Badge variant="secondary">Main</Badge>}
              {worktree.missing && (
                <StatusBadge tone="warning">Missing</StatusBadge>
              )}
            </div>
            <span className="font-mono text-xs break-all text-muted-foreground">
              {worktree.path}
            </span>
            {worktree.head ? (
              <span className="flex gap-2 text-sm">
                <span className="shrink-0 font-mono">{worktree.head.sha}</span>
                <span className="min-w-0 break-words">
                  {worktree.head.subject}
                </span>
              </span>
            ) : (
              <span className="text-sm text-muted-foreground">
                The folder isn't on disk.
              </span>
            )}
            <span className="text-xs text-muted-foreground">
              Created {formatDateTime(worktree.createdAt)}
            </span>
          </div>
          {actions && (
            <div className="flex shrink-0 flex-wrap gap-2">
              {actions(worktree)}
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}
