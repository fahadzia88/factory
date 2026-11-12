import { PlusIcon, TicketIcon } from "lucide-react"
import { useEffect, useState } from "react"
import { Link } from "react-router"

import {
  TicketStatusBadge,
  TicketTypeBadge,
} from "~/components/tickets/ticket-badges"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "~/components/ui/empty"
import { Skeleton } from "~/components/ui/skeleton"
import {
  listTickets,
  ticketError,
  type Ticket,
  type TicketError,
} from "~/lib/tickets"

const RECENT = 5

type State =
  | { status: "loading" }
  | { status: "loaded"; tickets: Ticket[] }
  | { status: "error"; error: TicketError }

/** The project page's Tickets tab: the five newest tickets, with links to the whole tracker. */
export function TicketsCard({
  projectId,
  archived,
}: {
  projectId: number
  archived: boolean
}) {
  const [state, setState] = useState<State>({ status: "loading" })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    listTickets(projectId).then(
      (tickets) => {
        if (!cancelled) setState({ status: "loaded", tickets })
      },
      (reason) => {
        if (!cancelled) {
          setState({ status: "error", error: ticketError(reason) })
        }
      }
    )
    return () => {
      cancelled = true
    }
  }, [projectId, attempt])

  const newTicket = (
    <Button
      size="sm"
      render={<Link to={`/projects/${projectId}/tickets/new`} />}
      nativeButton={false}
    >
      <PlusIcon aria-hidden />
      New ticket
    </Button>
  )

  return (
    <Card>
      <CardHeader>
        <CardTitle>Tickets</CardTitle>
        <CardAction className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            render={<Link to={`/projects/${projectId}/tickets`} />}
            nativeButton={false}
          >
            View all
          </Button>
          {!archived && newTicket}
        </CardAction>
      </CardHeader>
      <CardContent>
        {state.status === "loading" ? (
          <div className="flex flex-col gap-3">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : state.status === "error" ? (
          <Alert variant="destructive">
            <AlertTitle>Couldn't load tickets</AlertTitle>
            <AlertDescription>
              <p>{state.error.message}</p>
              {state.error.retryable && (
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-2"
                  onClick={() => {
                    setState({ status: "loading" })
                    setAttempt((n) => n + 1)
                  }}
                >
                  Try again
                </Button>
              )}
            </AlertDescription>
          </Alert>
        ) : state.tickets.length === 0 ? (
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <TicketIcon />
              </EmptyMedia>
              <EmptyTitle>No tickets yet</EmptyTitle>
              <EmptyDescription>
                {archived
                  ? "Archived projects can't get new tickets."
                  : "File a bug or an improvement to start tracking it."}
              </EmptyDescription>
            </EmptyHeader>
            {!archived && <EmptyContent>{newTicket}</EmptyContent>}
          </Empty>
        ) : (
          <ul className="-mx-2 divide-y">
            {state.tickets.slice(0, RECENT).map((ticket) => (
              <li key={ticket.id}>
                <Link
                  to={`/projects/${projectId}/tickets/${ticket.id}`}
                  className="flex flex-col gap-1 rounded-md px-2 py-3 outline-none hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring sm:flex-row sm:items-center sm:justify-between"
                >
                  <span className="flex min-w-0 items-baseline gap-2">
                    <span className="font-mono text-sm text-muted-foreground">
                      #{ticket.number}
                    </span>
                    <span className="min-w-0 font-medium break-words">
                      {ticket.title}
                    </span>
                  </span>
                  <span className="flex shrink-0 flex-wrap items-center gap-2">
                    <TicketTypeBadge type={ticket.type} />
                    <TicketStatusBadge status={ticket.status} />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
