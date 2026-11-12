import { PlusIcon, SearchXIcon, TicketIcon } from "lucide-react"
import { Link, useSearchParams } from "react-router"

import {
  TicketPriorityBadge,
  TicketStatusBadge,
  TicketTypeBadge,
} from "~/components/tickets/ticket-badges"
import { Button } from "~/components/ui/button"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "~/components/ui/empty"
import { Label } from "~/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "~/components/ui/select"
import { formatDateTime } from "~/lib/projects"
import {
  isTicketType,
  statusLabel,
  TICKET_TYPES,
  TYPE_LABEL,
  type StatusInfo,
  type Ticket,
} from "~/lib/tickets"

/** The `type` and `status` filters of the list, kept in the URL. */
export function TicketFilters({ statuses }: { statuses: StatusInfo[] }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const type = searchParams.get("type")
  const status = searchParams.get("status")

  function update(name: "type" | "status", value: string | null) {
    const next = new URLSearchParams(searchParams)
    if (value && value !== "any") next.set(name, value)
    else next.delete(name)
    setSearchParams(next)
  }

  const typeItems = [
    { value: "any", label: "Any type" },
    ...TICKET_TYPES.map((value) => ({ value, label: TYPE_LABEL[value] })),
  ]
  const statusItems = [
    { value: "any", label: "Any status" },
    ...statuses.map((s) => ({ value: s.status, label: statusLabel(s.status) })),
  ]

  return (
    <div className="flex flex-wrap items-center gap-3">
      <div className="flex flex-col gap-1">
        <Label htmlFor="ticket-type-filter" className="sr-only">
          Filter by type
        </Label>
        <Select
          items={typeItems}
          value={isTicketType(type) ? type : "any"}
          onValueChange={(value) => update("type", value)}
        >
          <SelectTrigger id="ticket-type-filter" className="w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {typeItems.map((item) => (
              <SelectItem key={item.value} value={item.value}>
                {item.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor="ticket-status-filter" className="sr-only">
          Filter by status
        </Label>
        <Select
          items={statusItems}
          value={status ?? "any"}
          onValueChange={(value) => update("status", value)}
        >
          <SelectTrigger id="ticket-status-filter" className="w-48">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {statusItems.map((item) => (
              <SelectItem key={item.value} value={item.value}>
                {item.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  )
}

/** The tickets as rows that wrap at phone width; empty states for no tickets and for filters with no match. */
export function TicketList({
  projectId,
  tickets,
  filtered,
  canCreate,
}: {
  projectId: number
  tickets: Ticket[]
  filtered: boolean
  canCreate: boolean
}) {
  if (tickets.length === 0 && filtered) {
    return (
      <Empty className="border border-dashed">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <SearchXIcon />
          </EmptyMedia>
          <EmptyTitle>No tickets match these filters</EmptyTitle>
          <EmptyDescription>
            Try another type or status, or clear the filters.
          </EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button
            variant="outline"
            render={<Link to={`/projects/${projectId}/tickets`} />}
            nativeButton={false}
          >
            Clear filters
          </Button>
        </EmptyContent>
      </Empty>
    )
  }
  if (tickets.length === 0) {
    return (
      <Empty className="border border-dashed">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <TicketIcon />
          </EmptyMedia>
          <EmptyTitle>No tickets yet</EmptyTitle>
          <EmptyDescription>
            {canCreate
              ? "File a bug or an improvement to start tracking it."
              : "Archived projects can't get new tickets."}
          </EmptyDescription>
        </EmptyHeader>
        {canCreate && (
          <EmptyContent>
            <Button
              render={<Link to={`/projects/${projectId}/tickets/new`} />}
              nativeButton={false}
            >
              <PlusIcon aria-hidden />
              New ticket
            </Button>
          </EmptyContent>
        )}
      </Empty>
    )
  }
  return (
    <ul className="divide-y rounded-lg border">
      {tickets.map((ticket) => (
        <li key={ticket.id}>
          <Link
            to={`/projects/${projectId}/tickets/${ticket.id}`}
            className="flex flex-col gap-2 px-3 py-3 outline-none hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring sm:flex-row sm:items-center sm:justify-between"
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
              <TicketPriorityBadge priority={ticket.priority} />
              <span className="text-xs text-muted-foreground">
                Updated {formatDateTime(ticket.updatedAt)}
              </span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  )
}
