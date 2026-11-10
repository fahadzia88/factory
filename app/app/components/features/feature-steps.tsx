import { ListTodoIcon } from "lucide-react"

import { StepStatusBadge } from "~/components/features/feature-status-badge"
import { Badge } from "~/components/ui/badge"
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "~/components/ui/empty"
import type { FeatureStep } from "~/lib/features"
import { formatDateTime } from "~/lib/projects"

/** Read-only: the agent records steps; people only see them. */
export function FeatureSteps({ steps }: { steps: FeatureStep[] }) {
  if (steps.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <ListTodoIcon />
          </EmptyMedia>
          <EmptyTitle>No steps yet</EmptyTitle>
          <EmptyDescription>
            Steps appear here as the agent works through the workflow.
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }
  const ordered = [...steps].sort((a, b) => a.position - b.position)
  return (
    <ul className="divide-y">
      {ordered.map((step) => (
        <li
          key={step.id}
          className="flex min-w-0 flex-col gap-1.5 py-3 first:pt-0 last:pb-0"
        >
          <span className="text-sm font-medium break-words">{step.title}</span>
          <div className="flex flex-wrap items-center gap-1.5">
            <Badge variant="secondary">{step.typeTitle}</Badge>
            <StepStatusBadge step={step} />
          </div>
          {step.documentPath && (
            <span className="font-mono text-xs break-all text-muted-foreground">
              {step.documentPath}
            </span>
          )}
          <span className="text-xs text-muted-foreground">
            Updated {formatDateTime(step.updatedAt)}
          </span>
        </li>
      ))}
    </ul>
  )
}
