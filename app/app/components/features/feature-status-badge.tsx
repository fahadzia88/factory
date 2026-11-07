import { StatusBadge, type StatusTone } from "~/components/status-badge"
import { stateLabel, type FeatureStep } from "~/lib/features"

export function FeatureStatusBadge({ status }: { status: string }) {
  const tone: StatusTone =
    status === "done"
      ? "positive"
      : status === "abandoned"
        ? "negative"
        : "neutral"
  return <StatusBadge tone={tone}>{stateLabel(status)}</StatusBadge>
}

export function StepStatusBadge({ step }: { step: FeatureStep }) {
  const tone: StatusTone =
    step.status === "approved" || step.status === "done"
      ? "positive"
      : step.status === step.states[0]
        ? "neutral"
        : "warning"
  return <StatusBadge tone={tone}>{stateLabel(step.status)}</StatusBadge>
}
