import { ExternalLinkIcon } from "lucide-react"
import { Suspense } from "react"
import { Await, useAsyncError, useRevalidator } from "react-router"

import { PageHeader } from "~/components/page-header"
import { ProjectBreadcrumb } from "~/components/projects/project-page"
import { ChangedFiles } from "~/components/pull-requests/changed-files"
import { Markdown } from "~/components/pull-requests/markdown"
import { PullRequestAuthor } from "~/components/pull-requests/pull-request-author"
import { PullRequestStateBadge } from "~/components/pull-requests/pull-request-badges"
import { PullRequestNotFound } from "~/components/pull-requests/pull-request-not-found"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "~/components/ui/card"
import { Skeleton } from "~/components/ui/skeleton"
import { Spinner } from "~/components/ui/spinner"
import { ApiError } from "~/lib/api"
import { formatDateTime, getProject, projectId } from "~/lib/projects"
import {
  getPullRequest,
  listPullRequestFiles,
  positiveInt,
  pullRequestError,
  type PullRequest,
  type PullRequestError,
  type PullRequestFile,
  type PullRequestPage,
} from "~/lib/pull-requests"
import type { Route } from "./+types/pull-request-detail"

export function meta({ loaderData }: Route.MetaArgs) {
  const pull = loaderData?.pull
  return [{ title: pull ? `#${pull.number} ${pull.title}` : "Pull request" }]
}

type LoaderData = {
  projectId: number | null
  project: { id: number; name: string } | null
  pull: PullRequest | null
  notFound: boolean
  error: PullRequestError | null
  /** Streams in after the summary renders. */
  files: Promise<PullRequestPage<PullRequestFile>> | null
}

export async function clientLoader({
  params,
  request,
}: Route.ClientLoaderArgs): Promise<LoaderData> {
  const id = projectId(params.id)
  const number = positiveInt(params.number)
  const none = { project: null, pull: null, error: null, files: null }
  if (id === null) return { ...none, projectId: null, notFound: true }
  if (number === null) return { ...none, projectId: id, notFound: true }

  const page = positiveInt(new URL(request.url).searchParams.get("page")) ?? 1
  // Started alongside the pull request so it arrives sooner; the catch only marks the
  // rejection handled for the paths below that don't render the files.
  const files = listPullRequestFiles(id, number, page)
  files.catch(() => undefined)

  const [project, pull] = await Promise.allSettled([
    getProject(id),
    getPullRequest(id, number),
  ])
  const projectMissing =
    project.status === "rejected" &&
    project.reason instanceof ApiError &&
    project.reason.status === 404
  if (projectMissing) return { ...none, projectId: null, notFound: true }
  if (pull.status === "rejected") {
    const error = pullRequestError(pull.reason)
    if (
      error.code === "pull_request_not_found" ||
      error.code === "project_not_found"
    ) {
      return { ...none, projectId: id, notFound: true }
    }
    return { ...none, projectId: id, notFound: false, error }
  }
  return {
    projectId: id,
    project:
      project.status === "fulfilled"
        ? { id: project.value.id, name: project.value.name }
        : null,
    pull: pull.value,
    notFound: false,
    error: null,
    files,
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

export default function PullRequestDetail({
  loaderData,
}: Route.ComponentProps) {
  const revalidator = useRevalidator()
  const { projectId, project, pull, notFound, error, files } = loaderData

  if (notFound) return <PullRequestNotFound projectId={projectId} />
  if (error || !pull) {
    const repositoryMissing = error?.code === "repository_not_found"
    return (
      <Alert variant="destructive">
        <AlertTitle>
          {repositoryMissing
            ? "Couldn't find the repository"
            : "Couldn't load the pull request"}
        </AlertTitle>
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

  return (
    <>
      <ProjectBreadcrumb
        name={pull.title}
        page={`#${pull.number}`}
        project={project ?? { id: projectId!, name: "Project" }}
      />
      <PageHeader
        title={
          <span className="flex flex-wrap items-center gap-2">
            <span className="min-w-0 break-words">{pull.title}</span>
            <span className="font-normal text-muted-foreground">
              #{pull.number}
            </span>
            <PullRequestStateBadge pull={pull} />
          </span>
        }
      >
        <Button
          variant="outline"
          render={<a href={pull.htmlUrl} target="_blank" rel="noreferrer" />}
          nativeButton={false}
        >
          <ExternalLinkIcon aria-hidden />
          Open on GitHub
        </Button>
      </PageHeader>

      <PullRequestSummaryCard pull={pull} />

      <Card>
        <CardHeader>
          <CardTitle>Description</CardTitle>
        </CardHeader>
        <CardContent>
          {pull.body ? (
            <Markdown>{pull.body}</Markdown>
          ) : (
            <p className="text-sm text-muted-foreground">No description</p>
          )}
        </CardContent>
      </Card>

      {files && (
        <Suspense fallback={<FilesSkeleton />}>
          <Await resolve={files} errorElement={<FilesError />}>
            {(page) => <ChangedFiles files={page} total={pull.changedFiles} />}
          </Await>
        </Suspense>
      )}
    </>
  )
}

function FilesSkeleton() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Changed files</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-10 w-full" />
        ))}
      </CardContent>
    </Card>
  )
}

function FilesError() {
  const revalidator = useRevalidator()
  const error = pullRequestError(useAsyncError())
  return (
    <Alert variant="destructive">
      <AlertTitle>Couldn't load the changed files</AlertTitle>
      <AlertDescription>
        <p>{error.message}</p>
        {error.retryable && (
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
      className={`flex min-w-0 flex-col gap-1 ${wide ? "sm:col-span-2" : ""}`}
    >
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </div>
  )
}

function PullRequestSummaryCard({ pull }: { pull: PullRequest }) {
  return (
    <Card>
      <CardContent>
        <dl className="grid gap-x-6 gap-y-5 sm:grid-cols-2">
          <Detail label="Author">
            <PullRequestAuthor author={pull.author} />
          </Detail>
          <Detail label="Branches">
            <span className="font-mono text-sm break-all">
              {pull.headRef} → {pull.baseRef}
            </span>
          </Detail>
          <Detail label="Created">{formatDateTime(pull.createdAt)}</Detail>
          <Detail label="Last updated">{formatDateTime(pull.updatedAt)}</Detail>
          {pull.mergedAt ? (
            <Detail label="Merged">{formatDateTime(pull.mergedAt)}</Detail>
          ) : (
            pull.closedAt && (
              <Detail label="Closed">{formatDateTime(pull.closedAt)}</Detail>
            )
          )}
          <Detail label="Commits">
            <span className="tabular-nums">{pull.commits}</span>
          </Detail>
          <Detail label="Changes">
            <span className="tabular-nums">
              +{pull.additions} −{pull.deletions}{" "}
              <span className="text-muted-foreground">
                in {pull.changedFiles}{" "}
                {pull.changedFiles === 1 ? "file" : "files"}
              </span>
            </span>
          </Detail>
        </dl>
      </CardContent>
    </Card>
  )
}
