import { TriangleAlertIcon } from "lucide-react"
import { useState } from "react"

import { DetailsPane } from "~/components/tickets/details-pane"
import { WorkspaceActions } from "~/components/tickets/workspace-actions"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Spinner } from "~/components/ui/spinner"
import {
  retryWorkspace,
  ticketError,
  type Ticket,
  type TicketEvent,
} from "~/lib/tickets"

/**
 * What a ticket gets once it first becomes in progress: `details.md` as Markdown with the actions beneath it, and
 * [side] (the agent window) next to it. Nothing is shown for a ticket that never had a workspace.
 */
export function TicketWorkspace({
  ticket,
  history,
  archived,
  refreshKey,
  side,
  onChanged,
}: {
  ticket: Ticket
  history: TicketEvent[]
  archived: boolean
  /** Changes when the Markdown should be read again */
  refreshKey: number
  side?: React.ReactNode
  onChanged: () => void
}) {
  const { workspace } = ticket
  if (workspace.state === "none") return null
  if (workspace.state === "failed") {
    return (
      <FailedWorkspace
        ticket={ticket}
        archived={archived}
        onChanged={onChanged}
      />
    )
  }

  return (
    <section
      aria-label="Workspace"
      className={
        side
          ? "grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]"
          : "grid items-start gap-6"
      }
    >
      <div className="flex min-w-0 flex-col gap-6">
        {workspace.worktree?.missing && (
          <Alert>
            <TriangleAlertIcon aria-hidden />
            <AlertTitle>The worktree isn't on disk</AlertTitle>
            <AlertDescription>
              Check {workspace.branch} out again on the project page.
            </AlertDescription>
          </Alert>
        )}
        <DetailsPane
          projectId={ticket.projectId}
          ticketId={ticket.id}
          refreshKey={refreshKey}
        />
        <WorkspaceActions
          ticket={ticket}
          history={history}
          archived={archived}
          onChanged={onChanged}
        />
      </div>
      {side && <div className="min-w-0">{side}</div>}
    </section>
  )
}

function FailedWorkspace({
  ticket,
  archived,
  onChanged,
}: {
  ticket: Ticket
  archived: boolean
  onChanged: () => void
}) {
  const [retrying, setRetrying] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function retry() {
    setRetrying(true)
    setError(null)
    try {
      await retryWorkspace(ticket.projectId, ticket.id)
    } catch (reason) {
      setError(ticketError(reason).message)
    } finally {
      setRetrying(false)
      onChanged()
    }
  }

  return (
    <Alert variant="destructive">
      <TriangleAlertIcon aria-hidden />
      <AlertTitle>The workspace couldn't be set up</AlertTitle>
      <AlertDescription>
        <p className="break-words">{ticket.workspace.error}</p>
        {error && <p className="break-words">{error}</p>}
        {!archived && (
          <Button
            variant="outline"
            size="sm"
            className="mt-2"
            disabled={retrying}
            onClick={retry}
          >
            {retrying && <Spinner aria-hidden />}
            Retry
          </Button>
        )}
      </AlertDescription>
    </Alert>
  )
}
