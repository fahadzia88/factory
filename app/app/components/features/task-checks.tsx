import { StatusBadge } from "~/components/status-badge"
import type { TaskChecks as Checks } from "~/lib/features"

const PASSING = new Set(["success", "neutral", "skipped"])

/** A task's checks at a glance, with the failing runs named. */
export function TaskChecks({ checks }: { checks: Checks }) {
  if (checks.state === "none") {
    return <span className="text-xs text-muted-foreground">No checks</span>
  }
  if (checks.state === "pending") {
    return <StatusBadge tone="warning">Checks running</StatusBadge>
  }
  if (checks.state === "success") {
    return <StatusBadge tone="positive">Checks passing</StatusBadge>
  }
  const failing = checks.runs.filter(
    (run) => run.conclusion === null || !PASSING.has(run.conclusion)
  )
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <StatusBadge tone="negative">Checks failing</StatusBadge>
      <ul className="flex flex-col gap-0.5 text-xs">
        {failing.map((run) => (
          <li key={run.name} className="min-w-0 break-words">
            {run.htmlUrl ? (
              <a
                href={run.htmlUrl}
                target="_blank"
                rel="noreferrer"
                className="underline underline-offset-4"
              >
                {run.name}
              </a>
            ) : (
              run.name
            )}
            {run.title && (
              <span className="text-muted-foreground"> — {run.title}</span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
