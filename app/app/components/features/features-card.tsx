import { ListTodoIcon, PlusIcon } from "lucide-react"
import { useEffect } from "react"
import { Link, useFetcher } from "react-router"

import { FeatureStatusBadge } from "~/components/features/feature-status-badge"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
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
import { Skeleton } from "~/components/ui/skeleton"
import { featureNumber } from "~/lib/features"
import { formatDateTime } from "~/lib/projects"
import type { clientLoader } from "~/routes/project-features"

export function FeaturesCard({
  projectId,
  archived,
}: {
  projectId: number
  archived: boolean
}) {
  const fetcher = useFetcher<typeof clientLoader>()
  const { load } = fetcher

  // archived is a dependency so a restore reloads whether a feature can be started
  useEffect(() => {
    load(`/projects/${projectId}/features`)
  }, [load, projectId, archived])

  const data = fetcher.data
  const canCreate = !archived && (data?.canCreate ?? false)
  const blocked = archived
    ? "Archived projects can't start features."
    : (data?.blocked ?? null)
  const newFeature = (
    <Button
      size="sm"
      render={<Link to={`/projects/${projectId}/features/new`} />}
      nativeButton={false}
    >
      <PlusIcon aria-hidden />
      New feature
    </Button>
  )

  return (
    <Card>
      <CardHeader>
        <CardTitle>Features</CardTitle>
        {canCreate && <CardAction>{newFeature}</CardAction>}
      </CardHeader>
      <CardContent>
        {!data ? (
          <div className="flex flex-col gap-3">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : data.error ? (
          <Alert variant="destructive">
            <AlertTitle>Couldn't load features</AlertTitle>
            <AlertDescription>
              <p>{data.error.message}</p>
              {data.error.retryable && (
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-2"
                  disabled={fetcher.state !== "idle"}
                  onClick={() => load(`/projects/${projectId}/features`)}
                >
                  Try again
                </Button>
              )}
            </AlertDescription>
          </Alert>
        ) : data.features.length === 0 ? (
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <ListTodoIcon />
              </EmptyMedia>
              <EmptyTitle>No features yet</EmptyTitle>
              <EmptyDescription>
                {canCreate
                  ? "Start a feature to plan it with the agent."
                  : blocked}
              </EmptyDescription>
            </EmptyHeader>
            {canCreate && <EmptyContent>{newFeature}</EmptyContent>}
          </Empty>
        ) : (
          <>
            <ul className="-mx-2 divide-y">
              {data.features.map((feature) => (
                <li key={feature.id}>
                  <Link
                    to={`/projects/${projectId}/features/${feature.id}`}
                    className="flex flex-col gap-1 rounded-md px-2 py-3 outline-none hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring sm:flex-row sm:items-center sm:justify-between"
                  >
                    <span className="flex min-w-0 items-baseline gap-2">
                      <span className="font-mono text-sm text-muted-foreground">
                        {featureNumber(feature.number)}
                      </span>
                      <span className="min-w-0 font-medium break-words">
                        {feature.name}
                      </span>
                    </span>
                    <span className="flex shrink-0 items-center gap-2">
                      <FeatureStatusBadge status={feature.status} />
                      <span className="text-xs text-muted-foreground">
                        Updated {formatDateTime(feature.updatedAt)}
                      </span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
            {!canCreate && blocked && (
              <p className="mt-3 text-sm text-muted-foreground">{blocked}</p>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
