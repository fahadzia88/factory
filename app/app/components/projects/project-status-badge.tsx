import { StatusBadge, type StatusTone } from "~/components/status-badge"
import { Badge } from "~/components/ui/badge"
import { STATUS_LABEL, type ProjectStatus } from "~/lib/projects"

const TONE: Record<ProjectStatus, StatusTone> = {
  planning: "neutral",
  active: "positive",
  maintenance: "warning",
  retired: "neutral",
}

export function ProjectStatusBadge({ status }: { status: ProjectStatus }) {
  return <StatusBadge tone={TONE[status]}>{STATUS_LABEL[status]}</StatusBadge>
}

export function ArchivedBadge() {
  return <Badge variant="secondary">Archived</Badge>
}

export function UnverifiedBadge() {
  return <StatusBadge tone="warning">Repository not verified</StatusBadge>
}
