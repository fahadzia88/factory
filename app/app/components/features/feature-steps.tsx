import { ListTodoIcon } from "lucide-react"

import { StepStatusBadge } from "~/components/features/feature-status-badge"
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
          className="flex flex-col gap-2 py-3 first:pt-0 last:pb-0 sm:flex-row sm:items-start sm:justify-between"
        >
          <div className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span className="text-sm font-medium break-words">
              {step.title}
            </span>
            <span className="text-sm text-muted-foreground">
              {step.typeTitle}
            </span>
            {step.documentPath && (
              <span className="font-mono text-xs break-all text-muted-foreground">
                {step.documentPath}
              </span>
            )}
          </div>
          <div className="flex shrink-0 flex-col items-start gap-1 sm:items-end">
            <StepStatusBadge step={step} />
            <span className="text-xs text-muted-foreground">
              Updated {formatDateTime(step.updatedAt)}
            </span>
          </div>
        </li>
      ))}
    </ul>
  )
}
