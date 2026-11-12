import { ExternalLinkIcon, PencilIcon } from "lucide-react"
import { useState } from "react"

import { TicketEditForm } from "~/components/tickets/ticket-edit-form"
import {
  TicketPriorityBadge,
  TicketStatusBadge,
  TicketTypeBadge,
} from "~/components/tickets/ticket-badges"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog"
import { formatDateTime } from "~/lib/projects"
import type { Ticket } from "~/lib/tickets"

/** A ticket's fields. The type is shown but can't be changed; editing is hidden for an archived project. */
export function TicketDetailsCard({
  ticket,
  archived,
  onChanged,
}: {
  ticket: Ticket
  archived: boolean
  onChanged: () => void
}) {
  const [editing, setEditing] = useState(false)
  return (
    <Card>
      <CardHeader>
        <CardTitle>Details</CardTitle>
        {!archived && (
          <CardAction>
            <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
              <PencilIcon aria-hidden />
              Edit
            </Button>
          </CardAction>
        )}
      </CardHeader>
      <CardContent>
        <dl className="grid gap-x-6 gap-y-5 sm:grid-cols-2 lg:grid-cols-1">
          <Detail label="Type">
            <TicketTypeBadge type={ticket.type} />
          </Detail>
          <Detail label="Priority">
            <TicketPriorityBadge priority={ticket.priority} />
          </Detail>
          <Detail label="Status" wide>
            <div className="flex flex-col gap-1">
              <span>
                <TicketStatusBadge status={ticket.status} />
              </span>
              <span className="text-sm text-muted-foreground">
                {ticket.statusDescription}
              </span>
            </div>
          </Detail>
          <Detail label="Description" wide>
            {ticket.description ? (
              <p className="break-words whitespace-pre-wrap">
                {ticket.description}
              </p>
            ) : (
              <span className="text-muted-foreground">No description</span>
            )}
          </Detail>
          <Detail label="Pull request" wide>
            {ticket.pullRequestUrl ? (
              <a
                href={ticket.pullRequestUrl}
                target="_blank"
                rel="noreferrer"
                className="inline-flex min-w-0 items-center gap-1 break-all underline-offset-4 hover:underline"
              >
                {ticket.pullRequestUrl}
                <ExternalLinkIcon aria-hidden className="size-3.5 shrink-0" />
              </a>
            ) : (
              <span className="text-muted-foreground">None</span>
            )}
          </Detail>
          <Detail label="Created">
            <span className="text-sm">{formatDateTime(ticket.createdAt)}</span>
          </Detail>
          <Detail label="Last updated">
            <span className="text-sm">{formatDateTime(ticket.updatedAt)}</span>
          </Detail>
        </dl>
      </CardContent>
      <Dialog open={editing} onOpenChange={setEditing}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Edit ticket #{ticket.number}</DialogTitle>
            <DialogDescription>
              The type can't be changed after a ticket is filed.
            </DialogDescription>
          </DialogHeader>
          <TicketEditForm
            ticket={ticket}
            onCancel={() => setEditing(false)}
            onSaved={() => {
              setEditing(false)
              onChanged()
            }}
          />
        </DialogContent>
      </Dialog>
    </Card>
  )
}

function Detail({
  label,
  children,
  wide = false,
}: {
  label: string
  children: React.ReactNode
  wide?: boolean
}) {
  return (
    <div
      className={`flex min-w-0 flex-col gap-1 ${wide ? "sm:col-span-2 lg:col-span-1" : ""}`}
    >
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </div>
  )
}
