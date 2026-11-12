import { PlusIcon } from "lucide-react"
import { Link, useRevalidator } from "react-router"

import { PageHeader } from "~/components/page-header"
import {
  ProjectBreadcrumb,
  ProjectNotFound,
} from "~/components/projects/project-page"
import { TicketFilters, TicketList } from "~/components/tickets/ticket-list"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Spinner } from "~/components/ui/spinner"
import { ApiError, apiErrorMessage } from "~/lib/api"
import { getProject, projectId } from "~/lib/projects"
import {
  isTicketType,
  listStatuses,
  listTickets,
  ticketError,
  type StatusInfo,
  type Ticket,
  type TicketError,
} from "~/lib/tickets"
import type { Route } from "./+types/ticket-list"

export function meta() {
  return [{ title: "Tickets" }]
}

type LoaderData =
  | { notFound: true }
  | { notFound: false; error: string }
  | {
      notFound: false
      error: null
      project: { id: number; name: string; archived: boolean }
      statuses: StatusInfo[]
      tickets: Ticket[]
      ticketsError: TicketError | null
      filtered: boolean
    }

export async function clientLoader({
  params,
  request,
}: Route.ClientLoaderArgs): Promise<LoaderData> {
  const id = projectId(params.id)
  if (id === null) return { notFound: true }
  const search = new URL(request.url).searchParams
  try {
    const [project, statuses] = await Promise.all([
      getProject(id),
      listStatuses(id),
    ])
    const type = search.get("type")
    const status = search.get("status")
    const filters = {
      type: isTicketType(type) ? type : undefined,
      status: statuses.some((s) => s.status === status)
        ? (status ?? undefined)
        : undefined,
    }
    let tickets: Ticket[] = []
    let ticketsError: TicketError | null = null
    try {
      tickets = await listTickets(id, filters)
    } catch (error) {
      ticketsError = ticketError(error)
    }
    return {
      notFound: false,
      error: null,
      project: {
        id: project.id,
        name: project.name,
        archived: project.archived,
      },
      statuses,
      tickets,
      ticketsError,
      filtered: Boolean(filters.type || filters.status),
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return { notFound: true }
    }
    return { notFound: false, error: apiErrorMessage(error) }
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

export default function Tickets({ loaderData }: Route.ComponentProps) {
  const revalidator = useRevalidator()

  if (loaderData.notFound) return <ProjectNotFound />
  if (loaderData.error !== null) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn't load the tickets</AlertTitle>
        <AlertDescription>
          <p>{loaderData.error}</p>
          <Button
            variant="outline"
            size="sm"
            className="mt-2"
            disabled={revalidator.state === "loading"}
            onClick={() => revalidator.revalidate()}
          >
            Try again
          </Button>
        </AlertDescription>
      </Alert>
    )
  }

  const { project, statuses, tickets, ticketsError, filtered } = loaderData
  return (
    <>
      <ProjectBreadcrumb name="Tickets" project={project} />
      <PageHeader
        title="Tickets"
        description="Bugs and improvements for this project."
      >
        {!project.archived && (
          <Button
            render={<Link to={`/projects/${project.id}/tickets/new`} />}
            nativeButton={false}
          >
            <PlusIcon aria-hidden />
            New ticket
          </Button>
        )}
      </PageHeader>

      {project.archived && (
        <Alert>
          <AlertTitle>
            This project is archived. Restore it to make changes.
          </AlertTitle>
        </Alert>
      )}

      <TicketFilters statuses={statuses} />

      {ticketsError ? (
        <Alert variant="destructive">
          <AlertTitle>Couldn't load the tickets</AlertTitle>
          <AlertDescription>
            <p>{ticketsError.message}</p>
            {ticketsError.retryable && (
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
      ) : (
        <TicketList
          projectId={project.id}
          tickets={tickets}
          filtered={filtered}
          canCreate={!project.archived}
        />
      )}
    </>
  )
}
