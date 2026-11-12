import { Link, useRevalidator } from "react-router"

import { PageHeader } from "~/components/page-header"
import { ProjectBreadcrumb } from "~/components/projects/project-page"
import {
  TicketPriorityBadge,
  TicketStatusBadge,
  TicketTypeBadge,
} from "~/components/tickets/ticket-badges"
import { TicketDetailsCard } from "~/components/tickets/ticket-details-card"
import { TicketHistory } from "~/components/tickets/ticket-history"
import { TicketNotFound } from "~/components/tickets/ticket-not-found"
import { TicketStatusControl } from "~/components/tickets/ticket-status-control"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Spinner } from "~/components/ui/spinner"
import { ApiError } from "~/lib/api"
import { getProject, projectId } from "~/lib/projects"
import {
  getHistory,
  getTicket,
  listStatuses,
  ticketError,
  ticketId,
  type StatusInfo,
  type Ticket,
  type TicketError,
  type TicketEvent,
} from "~/lib/tickets"
import type { Route } from "./+types/ticket-detail"

export function meta({ loaderData }: Route.MetaArgs) {
  const ticket = loaderData?.ticket
  return [{ title: ticket ? `#${ticket.number} ${ticket.title}` : "Ticket" }]
}

type LoaderData = {
  /** null when the project itself doesn't exist */
  projectId: number | null
  project: { id: number; name: string; archived: boolean } | null
  ticket: Ticket | null
  history: TicketEvent[]
  statuses: StatusInfo[]
  notFound: boolean
  error: TicketError | null
}

export async function clientLoader({
  params,
}: Route.ClientLoaderArgs): Promise<LoaderData> {
  const id = projectId(params.id)
  const tid = ticketId(params.ticketId)
  const none = { project: null, ticket: null, history: [], statuses: [] }
  if (id === null) {
    return { ...none, projectId: null, notFound: true, error: null }
  }
  if (tid === null) {
    return { ...none, projectId: id, notFound: true, error: null }
  }

  const [project, ticket, history, statuses] = await Promise.allSettled([
    getProject(id),
    getTicket(id, tid),
    getHistory(id, tid),
    listStatuses(id),
  ])
  if (
    project.status === "rejected" &&
    project.reason instanceof ApiError &&
    project.reason.status === 404
  ) {
    return { ...none, projectId: null, notFound: true, error: null }
  }
  for (const result of [ticket, history]) {
    if (result.status === "rejected") {
      const error = ticketError(result.reason)
      if (error.code === "ticket_not_found" || error.code === "project_not_found") {
        return { ...none, projectId: id, notFound: true, error: null }
      }
      return { ...none, projectId: id, notFound: false, error }
    }
  }
  if (ticket.status !== "fulfilled" || history.status !== "fulfilled") {
    return { ...none, projectId: id, notFound: true, error: null }
  }
  return {
    projectId: id,
    project:
      project.status === "fulfilled"
        ? {
            id: project.value.id,
            name: project.value.name,
            archived: project.value.archived,
          }
        : null,
    ticket: ticket.value,
    history: history.value,
    // Without the descriptions the page still works, so a failure here isn't fatal
    statuses: statuses.status === "fulfilled" ? statuses.value : [],
    notFound: false,
    error: null,
  }
}
clientLoader.hydrate = true as const

export function HydrateFallback() {
  return (
    <div className="flex flex-1 items-center justify-center py-16">
      <Spinner className="size-6 text-muted-foreground" />
    </div>
  )
}

export default function TicketDetail({ loaderData }: Route.ComponentProps) {
  const revalidator = useRevalidator()
  const { projectId, project, ticket, history, statuses, notFound, error } =
    loaderData

  if (notFound) return <TicketNotFound projectId={projectId} />
  if (error || !ticket) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn't load the ticket</AlertTitle>
        <AlertDescription>
          <p>{error?.message}</p>
          {error?.retryable && (
            <Button
              variant="outline"
              size="sm"
              className="mt-2"
              disabled={revalidator.state === "loading"}
              onClick={() => revalidator.revalidate()}
            >
              Try again
            </Button>
          )}
        </AlertDescription>
      </Alert>
    )
  }

  const archived = project?.archived ?? false
  const onChanged = () => revalidator.revalidate()
  const crumbProject = project ?? { id: ticket.projectId, name: "Project" }

  return (
    <>
      <ProjectBreadcrumb
        name={`Ticket #${ticket.number}`}
        project={crumbProject}
      />
      <PageHeader
        title={
          <span className="flex flex-wrap items-center gap-2">
            <span className="font-normal text-muted-foreground">
              #{ticket.number}
            </span>
            <span className="min-w-0 break-words">{ticket.title}</span>
            <TicketTypeBadge type={ticket.type} />
            <TicketStatusBadge status={ticket.status} />
            <TicketPriorityBadge priority={ticket.priority} />
          </span>
        }
      >
        <Button
          variant="outline"
          render={<Link to={`/projects/${ticket.projectId}/tickets`} />}
          nativeButton={false}
        >
          All tickets
        </Button>
      </PageHeader>

      {archived && (
        <Alert>
          <AlertTitle>
            This project is archived. Restore it to make changes.
          </AlertTitle>
        </Alert>
      )}

      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,20rem)_minmax(0,1fr)]">
        <aside
          aria-label="Ticket details"
          className="flex min-w-0 flex-col gap-6"
        >
          <TicketDetailsCard
            ticket={ticket}
            archived={archived}
            onChanged={onChanged}
          />
          <TicketStatusControl
            ticket={ticket}
            statuses={statuses}
            archived={archived}
            onChanged={onChanged}
          />
        </aside>
        <div className="min-w-0">
          <TicketHistory events={history} />
        </div>
      </div>
    </>
  )
}
