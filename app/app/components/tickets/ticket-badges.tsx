import { StatusBadge, type StatusTone } from "~/components/status-badge"
import { Badge } from "~/components/ui/badge"
import {
  PRIORITY_LABEL,
  statusLabel,
  TYPE_LABEL,
  type TicketPriority,
  type TicketType,
} from "~/lib/tickets"

export function TicketTypeBadge({ type }: { type: TicketType }) {
  return <Badge variant="secondary">{TYPE_LABEL[type]}</Badge>
}

const STATUS_TONE: Record<string, StatusTone> = {
  deployed: "positive",
  merged: "positive",
  closed: "neutral",
  filed: "neutral",
  backlog: "neutral",
  out_of_scope: "neutral",
  incorrect_expectation: "neutral",
  open: "warning",
  in_progress: "warning",
  in_review: "warning",
  reopened: "negative",
}

export function TicketStatusBadge({ status }: { status: string }) {
  return (
    <StatusBadge tone={STATUS_TONE[status] ?? "neutral"}>
      {statusLabel(status)}
    </StatusBadge>
  )
}

export function TicketPriorityBadge({
  priority,
}: {
  priority: TicketPriority
}) {
  const tone: StatusTone =
    priority === "critical"
      ? "negative"
      : priority === "high"
        ? "warning"
        : "neutral"
  return <StatusBadge tone={tone}>{PRIORITY_LABEL[priority]}</StatusBadge>
}
