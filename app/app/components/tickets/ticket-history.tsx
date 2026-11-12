import { Card, CardContent, CardHeader, CardTitle } from "~/components/ui/card"
import { formatDateTime } from "~/lib/projects"
import { statusLabel, type TicketEvent } from "~/lib/tickets"

const FIELD_LABEL: Record<string, string> = {
  title: "Title",
  description: "Description",
  priority: "Priority",
  pullRequestUrl: "Pull request link",
}

const SHORT = 200

/** Cuts a long value so one entry doesn't fill the page. */
function clip(value: string | null) {
  if (value === null || value === "") return "none"
  return value.length > SHORT ? `${value.slice(0, SHORT)}…` : value
}

/** The initial values of a ticket, from the JSON the API stored in the creation entry. */
function created(event: TicketEvent) {
  try {
    const values = JSON.parse(event.newValue ?? "{}") as {
      type?: string
      priority?: string
    }
    const parts = [
      values.type ? `as a ${values.type}` : null,
      values.priority ? `with ${values.priority} priority` : null,
    ].filter(Boolean)
    return `Ticket filed${parts.length ? ` ${parts.join(" ")}` : ""}`
  } catch {
    return "Ticket filed"
  }
}

/** One sentence for an entry, and for edits the old and new values. */
function summary(event: TicketEvent): { text: string; lines?: string[] } {
  switch (event.kind) {
    case "created":
      return { text: created(event) }
    case "status":
      return {
        text: `Status: ${statusLabel(event.oldValue ?? "")} → ${statusLabel(event.newValue ?? "")}`,
      }
    case "edit":
      return {
        text: `${FIELD_LABEL[event.field ?? ""] ?? event.field} changed`,
        lines: [`From: ${clip(event.oldValue)}`, `To: ${clip(event.newValue)}`],
      }
    case "workspace":
      return { text: `Workspace (${event.field}): ${event.detail}` }
    case "approve":
      return { text: "Investigation approved" }
    case "commit":
      return {
        text: `Committed ${event.newValue}`,
        lines: event.detail ? [event.detail] : undefined,
      }
    case "push":
      return { text: `Pushed ${event.detail} (${event.newValue})` }
    case "pull":
      return {
        text: "Pull request created",
        lines: [event.newValue ?? "", ...(event.detail ? [event.detail] : [])],
      }
    default:
      return { text: event.kind }
  }
}

/** Everything that happened to the ticket, oldest first. */
export function TicketHistory({ events }: { events: TicketEvent[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>History</CardTitle>
      </CardHeader>
      <CardContent>
        <ol className="flex flex-col gap-4">
          {events.map((event) => {
            const { text, lines } = summary(event)
            return (
              <li key={event.id} className="flex min-w-0 flex-col gap-0.5">
                <span className="text-xs text-muted-foreground">
                  {formatDateTime(event.createdAt)}
                </span>
                <span className="text-sm font-medium break-words">{text}</span>
                {lines?.map((line, i) => (
                  <span
                    key={i}
                    className="text-sm break-words whitespace-pre-wrap text-muted-foreground"
                  >
                    {line}
                  </span>
                ))}
              </li>
            )
          })}
        </ol>
      </CardContent>
    </Card>
  )
}
