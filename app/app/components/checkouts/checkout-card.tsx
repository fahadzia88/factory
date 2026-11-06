import {
  DownloadIcon,
  GitBranchIcon,
  RefreshCwIcon,
  RotateCcwIcon,
} from "lucide-react"
import { useState } from "react"
import { toast } from "sonner"

import { useCheckout } from "~/components/checkouts/use-checkout"
import { WorktreeList } from "~/components/checkouts/worktree-list"
import { StatusBadge, type StatusTone } from "~/components/status-badge"
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
import { Spinner } from "~/components/ui/spinner"
import {
  checkoutError,
  fetchCheckout,
  startCheckout,
  type Checkout,
  type CheckoutError,
  type CheckoutState,
} from "~/lib/checkouts"
import { formatDateTime } from "~/lib/projects"

const STATE_BADGE: Record<CheckoutState, { tone: StatusTone; label: string }> =
  {
    cloning: { tone: "neutral", label: "Cloning" },
    ready: { tone: "positive", label: "Ready" },
    failed: { tone: "negative", label: "Failed" },
  }

type Pending = "checkout" | "fetch" | null
type Failure = { error: CheckoutError; retry: () => void } | null

export function CheckoutCard({
  projectId,
  archived,
}: {
  projectId: number
  archived: boolean
}) {
  const { state, reload, setCheckout } = useCheckout(projectId, archived)
  const [pending, setPending] = useState<Pending>(null)
  const [failure, setFailure] = useState<Failure>(null)

  async function run(
    kind: Exclude<Pending, null>,
    call: () => Promise<Checkout>,
    retry: () => void,
    // returns true when the error needs no message
    handled: (error: CheckoutError) => boolean
  ) {
    setFailure(null)
    setPending(kind)
    try {
      const checkout = await call()
      setCheckout(checkout)
      return true
    } catch (e) {
      const error = checkoutError(e)
      if (!handled(error)) setFailure({ error, retry })
      return false
    } finally {
      setPending(null)
    }
  }

  function check() {
    void run(
      "checkout",
      () => startCheckout(projectId),
      check,
      (error) => {
        if (error.code !== "checkout_exists") return false
        void reload()
        return true
      }
    )
  }

  async function fetchNow() {
    const ok = await run(
      "fetch",
      () => fetchCheckout(projectId),
      () => void fetchNow(),
      (error) => {
        if (
          error.code !== "checkout_not_ready" &&
          error.code !== "checkout_not_found"
        )
          return false
        void reload()
        return true
      }
    )
    if (ok) toast.success("Fetched from GitHub")
  }

  const checkout = state.status === "loaded" ? state.checkout : null
  const badge = checkout ? STATE_BADGE[checkout.state] : null

  return (
    <Card>
      <CardHeader>
        <CardTitle>Repository checkout</CardTitle>
        {badge && (
          <CardAction className="flex flex-wrap items-center justify-end gap-2">
            <StatusBadge tone={badge.tone}>{badge.label}</StatusBadge>
            {checkout?.state === "ready" && !archived && (
              <Button
                variant="outline"
                size="sm"
                disabled={pending !== null}
                onClick={() => void fetchNow()}
              >
                {pending === "fetch" ? (
                  <Spinner />
                ) : (
                  <RefreshCwIcon aria-hidden />
                )}
                Fetch
              </Button>
            )}
          </CardAction>
        )}
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-4">
        {failure && (
          <Alert variant="destructive">
            <AlertTitle>That didn't work</AlertTitle>
            <AlertDescription>
              <p>{failure.error.message}</p>
              {failure.error.retryable && (
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-2"
                  disabled={pending !== null}
                  onClick={failure.retry}
                >
                  Try again
                </Button>
              )}
            </AlertDescription>
          </Alert>
        )}

        {state.status === "loading" && (
          <div className="flex flex-col gap-2">
            <Skeleton className="h-4 w-48" />
            <Skeleton className="h-4 w-72 max-w-full" />
          </div>
        )}

        {state.status === "archived" && (
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <GitBranchIcon />
              </EmptyMedia>
              <EmptyTitle>No checkout</EmptyTitle>
              <EmptyDescription>
                Archived projects have no checkout. Restore the project to check
                out its repository.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        )}

        {state.status === "none" && (
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <GitBranchIcon />
              </EmptyMedia>
              <EmptyTitle>Not checked out</EmptyTitle>
              <EmptyDescription>
                Clone the repository onto the server to work on it here.
              </EmptyDescription>
            </EmptyHeader>
            <EmptyContent>
              <Button disabled={pending !== null} onClick={check}>
                {pending === "checkout" ? (
                  <Spinner />
                ) : (
                  <DownloadIcon aria-hidden />
                )}
                Check out repository
              </Button>
            </EmptyContent>
          </Empty>
        )}

        {state.status === "error" && (
          <Alert variant="destructive">
            <AlertTitle>Couldn't load the repository checkout</AlertTitle>
            <AlertDescription>
              <p>{state.error.message}</p>
              {state.error.retryable && (
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-2"
                  onClick={() => void reload()}
                >
                  Try again
                </Button>
              )}
            </AlertDescription>
          </Alert>
        )}

        {checkout?.state === "cloning" && (
          <>
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Spinner className="shrink-0" />
              Cloning the repository. This can take a few minutes.
            </div>
            <dl>
              <CheckoutPath path={checkout.path} />
            </dl>
          </>
        )}

        {checkout?.state === "failed" && (
          <Alert variant="destructive">
            <AlertTitle>The clone failed</AlertTitle>
            <AlertDescription>
              {checkout.error && <p>{checkout.error}</p>}
              <Button
                variant="outline"
                size="sm"
                className="mt-2"
                disabled={pending !== null}
                onClick={check}
              >
                {pending === "checkout" ? (
                  <Spinner />
                ) : (
                  <RotateCcwIcon aria-hidden />
                )}
                Retry
              </Button>
            </AlertDescription>
          </Alert>
        )}

        {checkout?.state === "ready" && (
          <>
            <dl className="grid gap-x-6 gap-y-5 sm:grid-cols-2">
              <CheckoutPath path={checkout.path} />
              <div className="flex min-w-0 flex-col gap-1">
                <dt className="text-sm text-muted-foreground">
                  Default branch
                </dt>
                <dd className="font-mono break-all">
                  {checkout.defaultBranch ?? "Unknown"}
                </dd>
              </div>
              <div className="flex min-w-0 flex-col gap-1">
                <dt className="text-sm text-muted-foreground">Last fetched</dt>
                <dd>
                  {checkout.lastFetchedAt
                    ? formatDateTime(checkout.lastFetchedAt)
                    : "Never"}
                </dd>
              </div>
            </dl>
            <div className="flex min-w-0 flex-col gap-1">
              <h3 className="text-sm font-medium">Worktrees</h3>
              <WorktreeList worktrees={checkout.worktrees} />
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}

function CheckoutPath({ path }: { path: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <dt className="text-sm text-muted-foreground">Path</dt>
      <dd className="font-mono text-sm break-all">{path}/</dd>
    </div>
  )
}
