import { StatusBadge } from "~/components/status-badge"
import type { PullRequestSummary } from "~/lib/pull-requests"

export function PullRequestStateBadge({
  pull,
}: {
  pull: Pick<PullRequestSummary, "state" | "draft">
}) {
  if (pull.state === "merged") {
    return <StatusBadge tone="positive">Merged</StatusBadge>
  }
  if (pull.state === "closed") {
    return <StatusBadge tone="negative">Closed</StatusBadge>
  }
  if (pull.draft) return <StatusBadge tone="neutral">Draft</StatusBadge>
  return <StatusBadge tone="positive">Open</StatusBadge>
}
