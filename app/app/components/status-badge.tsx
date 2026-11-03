import { Badge } from "~/components/ui/badge"
import { cn } from "~/lib/utils"

export type StatusTone = "positive" | "neutral" | "warning" | "negative"

const TONE_DOT: Record<StatusTone, string> = {
  positive: "bg-emerald-500",
  warning: "bg-amber-500",
  neutral: "bg-muted-foreground/50",
  negative: "bg-destructive",
}

/** The label carries the meaning; the dot only reinforces it. */
export function StatusBadge({
  tone,
  children,
  ...props
}: React.ComponentProps<typeof Badge> & { tone: StatusTone }) {
  return (
    <Badge variant="outline" className="gap-1.5" {...props}>
      <span
        className={cn("size-1.5 rounded-full", TONE_DOT[tone])}
        aria-hidden
      />
      {children}
    </Badge>
  )
}
