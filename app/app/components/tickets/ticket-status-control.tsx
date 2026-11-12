import { useState } from "react"
import { toast } from "sonner"

import { TicketStatusBadge } from "~/components/tickets/ticket-badges"
import { Alert, AlertDescription } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "~/components/ui/card"
import { Spinner } from "~/components/ui/spinner"
import {
  changeTicketStatus,
  statusLabel,
  ticketError,
  type StatusInfo,
  type Ticket,
} from "~/lib/tickets"

/**
 * The ticket's status and the moves it can make now. The buttons come from the API's `allowedStatuses`, each with
 * the description of the status it leads to; the app never works out the flow itself.
 */
export function TicketStatusControl({
  ticket,
  statuses,
  archived,
  onChanged,
}: {
  ticket: Ticket
  statuses: StatusInfo[]
  archived: boolean
  onChanged: () => void
}) {
  const [moving, setMoving] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const describe = (status: string) =>
    statuses.find((s) => s.status === status)?.description ?? ""

  async function move(status: string) {
    setMoving(status)
    setError(null)
    try {
      await changeTicketStatus(ticket.projectId, ticket.id, status)
      toast.success(`Moved to ${statusLabel(status).toLowerCase()}`)
      onChanged()
    } catch (reason) {
      const problem = ticketError(reason)
      setError(problem.message)
      // The ticket may have changed elsewhere; show what it is now
      if (problem.code === "invalid_transition") onChanged()
    } finally {
      setMoving(null)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          Status
          <TicketStatusBadge status={ticket.status} />
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">
          {ticket.statusDescription}
        </p>
        {error && (
          <Alert variant="destructive">
            <AlertDescription className="break-words">{error}</AlertDescription>
          </Alert>
        )}
        {archived ? (
          <p className="text-sm text-muted-foreground">
            Archived projects can't be changed. Restore the project first.
          </p>
        ) : ticket.allowedStatuses.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            This ticket has no further status.
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <p className="text-sm font-medium">Move to</p>
            <ul className="flex flex-col gap-3">
              {ticket.allowedStatuses.map((status) => (
                <li key={status} className="flex flex-col items-start gap-1">
                  <Button
                    variant="outline"
                    size="sm"
                    title={describe(status)}
                    disabled={moving !== null}
                    onClick={() => move(status)}
                  >
                    {moving === status && <Spinner aria-hidden />}
                    {statusLabel(status)}
                  </Button>
                  <span className="text-xs text-muted-foreground">
                    {describe(status)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
