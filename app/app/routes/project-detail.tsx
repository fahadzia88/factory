import {
  ArchiveIcon,
  ArchiveRestoreIcon,
  ExternalLinkIcon,
  PencilIcon,
  RefreshCwIcon,
  TriangleAlertIcon,
} from "lucide-react"
import { useEffect, useState } from "react"
import { Form, Link, useNavigation, useRevalidator } from "react-router"
import { toast } from "sonner"

import { CheckoutCard } from "~/components/checkouts/checkout-card"
import { FeaturesCard } from "~/components/features/features-card"
import { UnsavedWorkList } from "~/components/checkouts/unsaved-work-list"
import { PageHeader } from "~/components/page-header"
import {
  ProjectBreadcrumb,
  ProjectNotFound,
} from "~/components/projects/project-page"
import {
  ArchivedBadge,
  ProjectStatusBadge,
  UnverifiedBadge,
} from "~/components/projects/project-status-badge"
import { PullRequestsCard } from "~/components/pull-requests/pull-request-list"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Card, CardContent } from "~/components/ui/card"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog"
import { Spinner } from "~/components/ui/spinner"
import { ApiError, apiErrorMessage } from "~/lib/api"
import {
  checkoutError,
  getUnsavedWork,
  type UnsavedWork,
} from "~/lib/checkouts"
import {
  archiveProject,
  formatDateTime,
  getProject,
  projectId,
  restoreProject,
  STATUS_LABEL,
  verifyProject,
  type Project,
} from "~/lib/projects"
import type { Route } from "./+types/project-detail"

export function meta({ loaderData }: Route.MetaArgs) {
  return [{ title: loaderData?.project?.name ?? "Project" }]
}

export async function clientLoader({ params }: Route.ClientLoaderArgs) {
  const id = projectId(params.id)
  if (id === null) return { project: null, notFound: true, error: null }
  try {
    return { project: await getProject(id), notFound: false, error: null }
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return { project: null, notFound: true, error: null }
    }
    return { project: null, notFound: false, error: apiErrorMessage(error) }
  }
}
clientLoader.hydrate = true as const

export async function clientAction({
  request,
  params,
}: Route.ClientActionArgs) {
  const id = projectId(params.id)
  if (id === null) return { error: "Project not found." }
  const intent = (await request.formData()).get("intent")
  try {
    if (intent === "archive") {
      await archiveProject(id)
      toast.success("Project archived")
    } else if (intent === "restore") {
      await restoreProject(id)
      toast.success("Project restored")
    } else if (intent === "verify") {
      await verifyProject(id)
      toast.success("Repository verified")
    }
    return { error: null }
  } catch (error) {
    return { error: apiErrorMessage(error) }
  }
}

export function HydrateFallback() {
  return (
    <div className="flex flex-1 items-center justify-center py-16">
      <Spinner className="size-6 text-muted-foreground" />
    </div>
  )
}

export default function ProjectDetail({
  loaderData,
  actionData,
}: Route.ComponentProps) {
  const revalidator = useRevalidator()
  const { project, notFound, error } = loaderData

  if (notFound) return <ProjectNotFound />
  if (error || !project) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn't load the project</AlertTitle>
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
    )
  }

  return (
    <>
      <ProjectBreadcrumb name={project.name} />
      <PageHeader
        title={
          <span className="flex flex-wrap items-center gap-2">
            <span className="min-w-0 break-words">{project.name}</span>
            <ProjectStatusBadge status={project.status} />
            {project.archived && <ArchivedBadge />}
          </span>
        }
      >
        {project.archived ? (
          <RestoreButton />
        ) : (
          <>
            <Button
              variant="outline"
              render={<Link to={`/projects/${project.id}/edit`} />}
              nativeButton={false}
            >
              <PencilIcon aria-hidden />
              Edit
            </Button>
            <ArchiveDialog name={project.name} projectId={project.id} />
          </>
        )}
      </PageHeader>

      {actionData?.error && (
        <Alert variant="destructive">
          <AlertTitle>That didn't work</AlertTitle>
          <AlertDescription>{actionData.error}</AlertDescription>
        </Alert>
      )}
      {project.archived && (
        <Alert>
          <ArchiveIcon aria-hidden />
          <AlertTitle>
            This project is archived. Restore it to make changes.
          </AlertTitle>
        </Alert>
      )}

      <ProjectDetails project={project} />
      <FeaturesCard projectId={project.id} archived={project.archived} />
      <PullRequestsCard
        projectId={project.id}
        repositoryUrl={project.repositoryUrl}
      />
      <CheckoutCard projectId={project.id} archived={project.archived} />
    </>
  )
}

function useSubmitting(intent: string) {
  const navigation = useNavigation()
  return (
    navigation.state !== "idle" && navigation.formData?.get("intent") === intent
  )
}

function RestoreButton() {
  const submitting = useSubmitting("restore")
  return (
    <Form method="post">
      <Button type="submit" name="intent" value="restore" disabled={submitting}>
        {submitting ? <Spinner /> : <ArchiveRestoreIcon aria-hidden />}
        Restore
      </Button>
    </Form>
  )
}

function VerifyButton() {
  const submitting = useSubmitting("verify")
  return (
    <Form method="post">
      <Button
        type="submit"
        name="intent"
        value="verify"
        variant="outline"
        size="sm"
        disabled={submitting}
      >
        {submitting ? <Spinner /> : <RefreshCwIcon aria-hidden />}
        Verify repository
      </Button>
    </Form>
  )
}

type UnsavedState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "loaded"; items: UnsavedWork[] }
  | { status: "error"; message: string }

function ArchiveDialog({
  name,
  projectId,
}: {
  name: string
  projectId: number
}) {
  const [open, setOpen] = useState(false)
  const [unsaved, setUnsaved] = useState<UnsavedState>({ status: "idle" })
  const submitting = useSubmitting("archive")

  useEffect(() => {
    if (!open) {
      setUnsaved({ status: "idle" })
      return
    }
    let cancelled = false
    setUnsaved({ status: "loading" })
    getUnsavedWork(projectId).then(
      (items) => {
        if (!cancelled) setUnsaved({ status: "loaded", items })
      },
      (error) => {
        if (!cancelled) {
          setUnsaved({ status: "error", message: checkoutError(error).message })
        }
      }
    )
    return () => {
      cancelled = true
    }
  }, [open, projectId])

  const hasUnsaved = unsaved.status === "loaded" && unsaved.items.length > 0

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger
        render={<Button variant="outline" disabled={submitting} />}
      >
        {submitting ? <Spinner /> : <ArchiveIcon aria-hidden />}
        Archive
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Archive {name}?</DialogTitle>
          <DialogDescription>
            It's hidden from the projects list and the Dashboard. You can
            restore it at any time. Its repository checkout is deleted too, and
            restoring the project doesn't bring it back.
          </DialogDescription>
        </DialogHeader>
        {unsaved.status === "loading" && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner />
            Checking for unsaved work…
          </div>
        )}
        {unsaved.status === "loaded" && unsaved.items.length > 0 && (
          <div className="flex min-w-0 flex-col gap-3">
            <Alert variant="destructive">
              <TriangleAlertIcon aria-hidden />
              <AlertTitle>
                Some worktrees have work that isn't on GitHub
              </AlertTitle>
              <AlertDescription>Archiving deletes it.</AlertDescription>
            </Alert>
            <UnsavedWorkList items={unsaved.items} />
          </div>
        )}
        {unsaved.status === "error" && (
          <Alert>
            <AlertTitle>Couldn't check for unsaved work</AlertTitle>
            <AlertDescription>
              <p>{unsaved.message}</p>
              <p>
                Archiving deletes the checkout, including anything not pushed to
                GitHub.
              </p>
            </AlertDescription>
          </Alert>
        )}
        <Form method="post" onSubmit={() => setOpen(false)}>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" type="button" />}>
              Cancel
            </DialogClose>
            <Button
              type="submit"
              name="intent"
              value="archive"
              disabled={unsaved.status === "loading"}
            >
              {hasUnsaved ? "Archive anyway" : "Archive"}
            </Button>
          </DialogFooter>
        </Form>
      </DialogContent>
    </Dialog>
  )
}

function ProjectDetails({ project }: { project: Project }) {
  return (
    <Card>
      <CardContent>
        <dl className="grid gap-x-6 gap-y-5 sm:grid-cols-2">
          <div className="flex min-w-0 flex-col gap-1 sm:col-span-2">
            <dt className="text-sm text-muted-foreground">Repository</dt>
            <dd className="flex flex-wrap items-center gap-2">
              <a
                href={project.repositoryUrl}
                target="_blank"
                rel="noreferrer"
                className="inline-flex min-w-0 items-center gap-1 font-medium break-all underline-offset-4 hover:underline"
              >
                {project.repositoryUrl}
                <ExternalLinkIcon aria-hidden className="size-3.5 shrink-0" />
              </a>
              {!project.repositoryVerified && <UnverifiedBadge />}
              {!project.repositoryVerified && !project.archived && (
                <VerifyButton />
              )}
            </dd>
            {!project.repositoryVerified && (
              <p className="text-sm text-muted-foreground">
                {project.archived
                  ? "GitHub couldn't be reached when it was saved. Saving the project again re-checks it."
                  : "GitHub couldn't be reached when it was saved. Verify it again, or save the project to re-check it."}
              </p>
            )}
          </div>
          <div className="flex flex-col gap-1">
            <dt className="text-sm text-muted-foreground">Status</dt>
            <dd>{STATUS_LABEL[project.status]}</dd>
          </div>
          <div className="flex flex-col gap-1 sm:col-span-2">
            <dt className="text-sm text-muted-foreground">Description</dt>
            <dd className="whitespace-pre-wrap">
              {project.description ?? (
                <span className="text-muted-foreground">No description</span>
              )}
            </dd>
          </div>
          <div className="flex flex-col gap-1">
            <dt className="text-sm text-muted-foreground">Created</dt>
            <dd>{formatDateTime(project.createdAt)}</dd>
          </div>
          <div className="flex flex-col gap-1">
            <dt className="text-sm text-muted-foreground">Last updated</dt>
            <dd>{formatDateTime(project.updatedAt)}</dd>
          </div>
        </dl>
      </CardContent>
    </Card>
  )
}
