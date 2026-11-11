import { BotIcon, FileTextIcon, GitPullRequestIcon } from "lucide-react"
import { useState } from "react"
import { Link, useLocation, useRevalidator } from "react-router"

import { DocumentsTab } from "~/components/features/documents-tab"
import { FeatureChat } from "~/components/features/feature-chat"
import { FeatureNotFound } from "~/components/features/feature-not-found"
import { FeatureStatusBadge } from "~/components/features/feature-status-badge"
import { FeatureSteps } from "~/components/features/feature-steps"
import { FeatureTasks } from "~/components/features/feature-tasks"
import { PageHeader } from "~/components/page-header"
import { ProjectBreadcrumb } from "~/components/projects/project-page"
import { StatusBadge } from "~/components/status-badge"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "~/components/ui/card"
import { Spinner } from "~/components/ui/spinner"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "~/components/ui/tabs"
import { ApiError } from "~/lib/api"
import {
  featureError,
  featureId,
  featureNumber,
  getFeature,
  type Feature,
  type FeatureError,
} from "~/lib/features"
import { formatDateTime, getProject, projectId } from "~/lib/projects"
import type { Route } from "./+types/feature-detail"

export function meta({ loaderData }: Route.MetaArgs) {
  const feature = loaderData?.feature
  return [
    {
      title: feature
        ? `${featureNumber(feature.number)} ${feature.name}`
        : "Feature",
    },
  ]
}

type LoaderData = {
  projectId: number | null
  project: { id: number; name: string; archived: boolean } | null
  feature: Feature | null
  notFound: boolean
  error: FeatureError | null
}

export async function clientLoader({
  params,
}: Route.ClientLoaderArgs): Promise<LoaderData> {
  const id = projectId(params.id)
  const fid = featureId(params.featureId)
  const none = { project: null, feature: null, error: null }
  if (id === null) return { ...none, projectId: null, notFound: true }
  if (fid === null) return { ...none, projectId: id, notFound: true }

  const [project, feature] = await Promise.allSettled([
    getProject(id),
    getFeature(id, fid),
  ])
  const projectMissing =
    project.status === "rejected" &&
    project.reason instanceof ApiError &&
    project.reason.status === 404
  if (projectMissing) return { ...none, projectId: null, notFound: true }
  if (feature.status === "rejected") {
    const error = featureError(feature.reason)
    if (
      error.code === "feature_not_found" ||
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
        ? {
            id: project.value.id,
            name: project.value.name,
            archived: project.value.archived,
          }
        : null,
    feature: feature.value,
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

export default function FeatureDetail({ loaderData }: Route.ComponentProps) {
  const revalidator = useRevalidator()
  const [turns, setTurns] = useState(0)
  const location = useLocation()
  // Set by the New feature form when the docs pull request couldn't be opened
  const docsPullError =
    (location.state as { docsPullError?: string | null } | null)
      ?.docsPullError ?? null
  const { projectId, project, feature, notFound, error } = loaderData

  if (notFound) return <FeatureNotFound projectId={projectId} />
  if (error || !feature) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn't load the feature</AlertTitle>
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

  const number = featureNumber(feature.number)
  return (
    <>
      <ProjectBreadcrumb
        name={feature.name}
        page={number}
        project={project ?? { id: feature.projectId, name: "Project" }}
      />
      <PageHeader
        title={
          <span className="flex flex-wrap items-center gap-2">
            <span className="min-w-0 break-words">{feature.name}</span>
            <span className="font-normal text-muted-foreground">{number}</span>
            <FeatureStatusBadge status={feature.status} />
          </span>
        }
      />

      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,20rem)_minmax(0,1fr)]">
        <aside
          aria-label="Feature details"
          className="flex min-w-0 flex-col gap-6"
        >
          <FeatureDetailsCard feature={feature} />
          <Card>
            <CardHeader>
              <CardTitle>Steps</CardTitle>
            </CardHeader>
            <CardContent>
              <FeatureSteps steps={feature.steps} />
            </CardContent>
          </Card>
        </aside>
        <FeatureTabs
          feature={feature}
          turns={turns}
          docsPullError={docsPullError}
          onTurnEnd={() => {
            revalidator.revalidate()
            setTurns((n) => n + 1)
          }}
        />
      </div>
    </>
  )
}

function FeatureTabs({
  feature,
  turns,
  docsPullError,
  onTurnEnd,
}: {
  feature: Feature
  turns: number
  docsPullError: string | null
  onTurnEnd: () => void
}) {
  return (
    <Tabs
      defaultValue={docsPullError ? "pull-requests" : "agent"}
      key={feature.id}
      className="min-w-0"
    >
      <TabsList className="w-fit max-w-full overflow-x-auto">
        <TabsTrigger value="agent">
          <BotIcon />
          Agent
        </TabsTrigger>
        <TabsTrigger value="pull-requests">
          <GitPullRequestIcon />
          Pull requests
        </TabsTrigger>
        <TabsTrigger value="documents">
          <FileTextIcon />
          Documents
        </TabsTrigger>
      </TabsList>
      <TabsContent value="agent" keepMounted className="min-w-0">
        <FeatureChat
          projectId={feature.projectId}
          featureId={feature.id}
          onTurnEnd={onTurnEnd}
        />
      </TabsContent>
      <TabsContent value="pull-requests" keepMounted className="min-w-0">
        <FeatureTasks
          projectId={feature.projectId}
          featureId={feature.id}
          refresh={turns}
          initialError={docsPullError}
        />
      </TabsContent>
      <TabsContent value="documents" keepMounted className="min-w-0">
        <DocumentsTab
          projectId={feature.projectId}
          featureId={feature.id}
          refreshKey={turns}
        />
      </TabsContent>
    </Tabs>
  )
}

function FeatureDetailsCard({ feature }: { feature: Feature }) {
  const worktree = feature.worktree
  return (
    <Card>
      <CardContent>
        <dl className="grid gap-x-6 gap-y-5 sm:grid-cols-2 lg:grid-cols-1">
          <Detail label="Status">
            <FeatureStatusBadge status={feature.status} />
          </Detail>
          <Detail label="Branch">
            <span className="font-mono text-sm break-all">
              {feature.branch}
            </span>
          </Detail>
          <Detail label="Folder">
            <span className="font-mono text-sm break-all">
              {feature.folder}
            </span>
          </Detail>
          <Detail label="Worktree" wide>
            {worktree && !worktree.missing ? (
              <span className="font-mono text-sm break-all">
                {worktree.path}
              </span>
            ) : (
              <span className="flex flex-col items-start gap-1">
                <StatusBadge tone="warning">Missing</StatusBadge>
                <span className="text-sm text-muted-foreground">
                  The worktree isn't on disk. Add a worktree for this branch on
                  the{" "}
                  <Link
                    to={`/projects/${feature.projectId}`}
                    className="underline underline-offset-4"
                  >
                    project page
                  </Link>
                  .
                </span>
              </span>
            )}
          </Detail>
          <Detail label="Created">
            <span className="text-sm">{formatDateTime(feature.createdAt)}</span>
          </Detail>
          <Detail label="Last updated">
            <span className="text-sm">{formatDateTime(feature.updatedAt)}</span>
          </Detail>
        </dl>
      </CardContent>
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
