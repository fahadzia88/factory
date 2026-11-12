import { Link, redirect, useNavigation, useRevalidator } from "react-router"
import { toast } from "sonner"

import { PageHeader } from "~/components/page-header"
import {
  ProjectBreadcrumb,
  ProjectNotFound,
} from "~/components/projects/project-page"
import {
  TicketForm,
  type TicketFormResult,
} from "~/components/tickets/ticket-form"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Card, CardContent } from "~/components/ui/card"
import { Spinner } from "~/components/ui/spinner"
import { ApiError, apiErrorMessage } from "~/lib/api"
import { getProject, projectId } from "~/lib/projects"
import {
  createTicket,
  isTicketType,
  ticketError,
  TICKET_PRIORITIES,
  type TicketInput,
  type TicketPriority,
} from "~/lib/tickets"
import type { Route } from "./+types/ticket-new"

export function meta() {
  return [{ title: "New ticket" }]
}

type LoaderData =
  | { notFound: true }
  | { notFound: false; error: string }
  | {
      notFound: false
      error: null
      project: { id: number; name: string; archived: boolean }
    }

export async function clientLoader({
  params,
}: Route.ClientLoaderArgs): Promise<LoaderData> {
  const id = projectId(params.id)
  if (id === null) return { notFound: true }
  try {
    const project = await getProject(id)
    return {
      notFound: false,
      error: null,
      project: {
        id: project.id,
        name: project.name,
        archived: project.archived,
      },
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

const EMPTY: TicketInput = {
  type: "",
  title: "",
  description: "",
  priority: "medium",
}

export async function clientAction({
  request,
  params,
}: Route.ClientActionArgs): Promise<TicketFormResult | Response> {
  const id = projectId(params.id)
  if (id === null) return { values: EMPTY, formError: "Project not found." }
  const form = await request.formData()
  const text = (name: string) => String(form.get(name) ?? "")
  const type = text("type")
  const priority = text("priority")
  const values: TicketInput = {
    type: isTicketType(type) ? type : "",
    title: text("title"),
    description: text("description"),
    priority: TICKET_PRIORITIES.includes(priority as TicketPriority)
      ? (priority as TicketPriority)
      : "medium",
  }
  const errors: NonNullable<TicketFormResult["errors"]> = {}
  if (!values.type) errors.type = "Choose a type."
  if (!values.title.trim()) errors.title = "Enter a title."
  if (errors.type || errors.title) return { values, errors }
  try {
    const ticket = await createTicket(id, values)
    toast.success(`Ticket #${ticket.number} created`)
    return redirect(`/projects/${id}/tickets/${ticket.id}`)
  } catch (error) {
    const problem = ticketError(error)
    return { values, formError: problem.message, retryable: problem.retryable }
  }
}

export default function NewTicket({
  loaderData,
  actionData,
}: Route.ComponentProps) {
  const navigation = useNavigation()
  const revalidator = useRevalidator()

  if (loaderData.notFound) return <ProjectNotFound />
  if (loaderData.error !== null) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn't load the project</AlertTitle>
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

  const { project } = loaderData
  const crumbs = <ProjectBreadcrumb project={project} name="New ticket" />

  if (project.archived) {
    return (
      <>
        {crumbs}
        <PageHeader title="New ticket" />
        <Alert>
          <AlertTitle>Archived projects can't get new tickets.</AlertTitle>
          <AlertDescription>
            <Button
              variant="outline"
              size="sm"
              className="mt-2"
              render={<Link to={`/projects/${project.id}/tickets`} />}
              nativeButton={false}
            >
              Back to tickets
            </Button>
          </AlertDescription>
        </Alert>
      </>
    )
  }

  return (
    <>
      {crumbs}
      <PageHeader title="New ticket" />
      <Card>
        <CardContent>
          <TicketForm
            defaultValues={EMPTY}
            result={actionData as TicketFormResult | undefined}
            submitting={navigation.state === "submitting"}
            cancelTo={`/projects/${project.id}/tickets`}
          />
        </CardContent>
      </Card>
    </>
  )
}
