import type { UnsavedWork } from "~/lib/checkouts"

// the API returns at most this many of each
const LIST_LIMIT = 50

export function UnsavedWorkList({ items }: { items: UnsavedWork[] }) {
  return (
    <div className="max-h-64 overflow-y-auto">
      <ul className="flex flex-col gap-4">
        {items.map((item) => (
          <li key={item.worktreeId} className="flex min-w-0 flex-col gap-2">
            <span className="font-mono text-sm font-medium break-all">
              {item.branch}
            </span>
            {item.changedFiles.length > 0 && (
              <div className="flex flex-col gap-1">
                <span className="text-xs text-muted-foreground">
                  Changed files ({item.changedFiles.length})
                </span>
                <ul className="flex flex-col gap-0.5">
                  {item.changedFiles.map((file) => (
                    <li key={file} className="font-mono text-xs break-all">
                      {file}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {item.unpushedCommits.length > 0 && (
              <div className="flex flex-col gap-1">
                <span className="text-xs text-muted-foreground">
                  Unpushed commits ({item.unpushedCommits.length})
                </span>
                <ul className="flex flex-col gap-0.5">
                  {item.unpushedCommits.map((commit) => (
                    <li key={commit.sha} className="flex gap-2 text-xs">
                      <span className="shrink-0 font-mono">{commit.sha}</span>
                      <span className="min-w-0 break-words">
                        {commit.subject}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {(item.changedFiles.length >= LIST_LIMIT ||
              item.unpushedCommits.length >= LIST_LIMIT) && (
              <span className="text-xs text-muted-foreground">
                Showing the first 50.
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
