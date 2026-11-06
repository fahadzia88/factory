import { ExternalLinkIcon, GitPullRequestIcon } from "lucide-react"
import { useEffect, useState } from "react"
import { Link, useFetcher } from "react-router"

import { PullRequestAuthor } from "~/components/pull-requests/pull-request-author"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "~/components/ui/empty"
import { Skeleton } from "~/components/ui/skeleton"
import { formatDateTime } from "~/lib/projects"
import type { PullRequestSummary } from "~/lib/pull-requests"
import { cn } from "~/lib/utils"
import type { clientLoader } from "~/routes/project-pulls"

/** `https://github.com/owner/repo/pulls` from the stored repository URL. */
function pullsUrl(repositoryUrl: string) {
  return `${repositoryUrl.replace(/\/$/, "").replace(/\.git$/, "")}/pulls`
}

export function PullRequestsCard({
  projectId,
  repositoryUrl,
}: {
  projectId: number
  repositoryUrl: string
}) {
  const fetcher = useFetcher<typeof clientLoader>()
  const [page, setPage] = useState(1)
  const { load } = fetcher

  useEffect(() => {
    load(`/projects/${projectId}/pulls?page=${page}`)
  }, [load, projectId, page])

  const data = fetcher.data
  const loading = fetcher.state !== "idle"

  return (
    <Card>
      <CardHeader>
        <CardTitle>Pull requests</CardTitle>
        <CardAction>
          <Button
            variant="ghost"
            size="sm"
            render={
              <a
                href={pullsUrl(repositoryUrl)}
                target="_blank"
                rel="noreferrer"
              />
            }
            nativeButton={false}
          >
            <ExternalLinkIcon aria-hidden />
            Open on GitHub
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        {!data ? (
          <ListSkeleton />
        ) : data.error ? (
          <Alert variant="destructive">
            <AlertTitle>Couldn't load pull requests</AlertTitle>
            <AlertDescription>
              <p>{data.error.message}</p>
              {data.error.retryable && (
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-2"
                  disabled={loading}
                  onClick={() =>
                    load(`/projects/${projectId}/pulls?page=${page}`)
                  }
                >
                  Try again
                </Button>
              )}
            </AlertDescription>
          </Alert>
        ) : data.page.items.length === 0 ? (
          <Empty className="border border-dashed p-6">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <GitPullRequestIcon />
              </EmptyMedia>
              <EmptyTitle>No open pull requests</EmptyTitle>
              {data.page.page > 1 && (
                <EmptyDescription>
                  <button
                    type="button"
                    className="underline underline-offset-4"
                    onClick={() => setPage(1)}
                  >
                    Back to the first page
                  </button>
                </EmptyDescription>
              )}
            </EmptyHeader>
          </Empty>
        ) : (
          <ul
            className={cn(
              "flex flex-col divide-y transition-opacity",
              loading && "opacity-60"
            )}
            aria-busy={loading}
          >
            {data.page.items.map((pull) => (
              <PullRequestRow
                key={pull.number}
                projectId={projectId}
                pull={pull}
              />
            ))}
          </ul>
        )}
      </CardContent>
      {data?.page && (data.page.page > 1 || data.page.hasNext) && (
        <CardFooter className="flex items-center justify-between gap-2">
          <span className="text-sm text-muted-foreground tabular-nums">
            Page {data.page.page}
          </span>
          <div className="flex gap-2">
            {data.page.page > 1 && (
              <Button
                variant="outline"
                size="sm"
                disabled={loading}
                onClick={() => setPage(data.page.page - 1)}
              >
                Previous
              </Button>
            )}
            {data.page.hasNext && (
              <Button
                variant="outline"
                size="sm"
                disabled={loading}
                onClick={() => setPage(data.page.page + 1)}
              >
                Next
              </Button>
            )}
          </div>
        </CardFooter>
      )}
    </Card>
  )
}

function ListSkeleton() {
  return (
    <div className="flex flex-col gap-3" aria-busy>
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-12 w-full" />
      ))}
    </div>
  )
}

function PullRequestRow({
  projectId,
  pull,
}: {
  projectId: number
  pull: PullRequestSummary
}) {
  return (
    <li className="flex items-start justify-between gap-3 py-3 first:pt-0 last:pb-0">
      <div className="flex min-w-0 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <Link
            to={`/projects/${projectId}/pulls/${pull.number}`}
            className="min-w-0 font-medium break-words underline-offset-4 hover:underline"
          >
            {pull.title}
          </Link>
          <span className="text-sm text-muted-foreground">#{pull.number}</span>
          {pull.draft && <Badge variant="secondary">Draft</Badge>}
        </div>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <PullRequestAuthor author={pull.author} />
          <span className="font-mono break-all">
            {pull.headRef} → {pull.baseRef}
          </span>
          <span>Updated {formatDateTime(pull.updatedAt)}</span>
        </div>
      </div>
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label={`Open #${pull.number} on GitHub`}
        render={<a href={pull.htmlUrl} target="_blank" rel="noreferrer" />}
        nativeButton={false}
      >
        <ExternalLinkIcon aria-hidden />
      </Button>
    </li>
  )
}
