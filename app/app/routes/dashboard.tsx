import { FolderGit2Icon, PlusIcon } from "lucide-react"
import { Link, useRevalidator } from "react-router"

import { PageHeader } from "~/components/page-header"
import { ProjectStatusBadge } from "~/components/projects/project-status-badge"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardContent,
  CardFooter,
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
import { Spinner } from "~/components/ui/spinner"
import { apiErrorMessage } from "~/lib/api"
import {
  formatDateTime,
  getProjectSummary,
  PROJECT_STATUSES,
  repositoryName,
  STATUS_LABEL,
} from "~/lib/projects"
import type { Route } from "./+types/dashboard"

export function meta() {
  return [{ title: "Dashboard" }]
}

// Signed-out visitors are sent to /login by the app layout's loader
export async function clientLoader() {
  try {
    return { summary: await getProjectSummary(), error: null }
  } catch (error) {
    return { summary: null, error: apiErrorMessage(error) }
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

function NewProjectButton() {
  return (
    <Button render={<Link to="/projects/new" />} nativeButton={false}>
      <PlusIcon aria-hidden />
      New project
    </Button>
  )
}

export default function Dashboard({ loaderData }: Route.ComponentProps) {
  const revalidator = useRevalidator()
  const { summary, error } = loaderData

  if (error || !summary) {
    return (
      <>
        <PageHeader title="Dashboard" />
        <Alert variant="destructive">
          <AlertTitle>Couldn't load the dashboard</AlertTitle>
          <AlertDescription>
            <p>{error}</p>
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
      </>
    )
  }

  const total = PROJECT_STATUSES.reduce(
    (sum, status) => sum + summary.counts[status],
    0
  )

  if (total === 0) {
    return (
      <>
        <PageHeader title="Dashboard" />
        <Empty className="border border-dashed">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FolderGit2Icon />
            </EmptyMedia>
            <EmptyTitle>No projects yet</EmptyTitle>
            <EmptyDescription>
              Add the codebases the factory works on.
            </EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <NewProjectButton />
          </EmptyContent>
        </Empty>
      </>
    )
  }

  return (
    <>
      <PageHeader title="Dashboard">
        <NewProjectButton />
      </PageHeader>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        {PROJECT_STATUSES.map((status) => (
          <Link
            key={status}
            to={`/projects?status=${status}`}
            className="rounded-xl outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            <Card className="h-full transition-colors hover:bg-muted/50">
              <CardHeader>
                <CardTitle className="text-sm font-normal text-muted-foreground">
                  {STATUS_LABEL[status]}
                </CardTitle>
              </CardHeader>
              <CardContent>
                <p className="font-heading text-3xl font-medium tabular-nums">
                  {summary.counts[status]}
                </p>
              </CardContent>
            </Card>
          </Link>
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Recent projects</CardTitle>
        </CardHeader>
        <CardContent>
          <ul className="flex flex-col divide-y">
            {summary.recent.map((project) => (
              <li
                key={project.id}
                className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-3 first:pt-0 last:pb-0"
              >
                <div className="flex min-w-0 flex-col">
                  <Link
                    to={`/projects/${project.id}`}
                    className="truncate font-medium underline-offset-4 hover:underline"
                  >
                    {project.name}
                  </Link>
                  <span className="truncate text-xs text-muted-foreground">
                    {repositoryName(project.repositoryUrl)}
                  </span>
                </div>
                <div className="flex items-center gap-3 text-sm text-muted-foreground">
                  <ProjectStatusBadge status={project.status} />
                  <span>{formatDateTime(project.updatedAt)}</span>
                </div>
              </li>
            ))}
          </ul>
        </CardContent>
        <CardFooter>
          <Button
            variant="outline"
            render={<Link to="/projects" />}
            nativeButton={false}
          >
            View all
          </Button>
        </CardFooter>
      </Card>
    </>
  )
}
