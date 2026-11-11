import { ExternalLinkIcon, RefreshCwIcon } from "lucide-react"
import { useCallback, useEffect, useRef, useState } from "react"
import { Link } from "react-router"

import { TaskChecks } from "~/components/features/task-checks"
import { PullRequestStateBadge } from "~/components/pull-requests/pull-request-badges"
import { StatusBadge } from "~/components/status-badge"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import { Skeleton } from "~/components/ui/skeleton"
import { Spinner } from "~/components/ui/spinner"
import {
  featureError,
  listFeatureTasks,
  openDocsPull,
  type FeatureError,
  type FeatureTask,
} from "~/lib/features"

type TasksState =
  | { status: "loading" }
  | { status: "loaded"; tasks: FeatureTask[] }
  | { status: "error"; error: FeatureError }

/** The feature's branches and their pull requests, read-only apart from opening the docs pull request. */
export function FeatureTasks({
  projectId,
  featureId,
  refresh,
  initialError,
}: {
  projectId: number
  featureId: number
  refresh: number
  initialError: string | null
}) {
  const [state, setState] = useState<TasksState>({ status: "loading" })
  const [loading, setLoading] = useState(true)
  const [reloads, setReloads] = useState(0)
  const request = useRef(0)

  const reload = useCallback(() => setReloads((n) => n + 1), [])

  useEffect(() => {
    const id = ++request.current
    setLoading(true)
    listFeatureTasks(projectId, featureId).then(
      (tasks) => {
        if (id !== request.current) return
        setState({ status: "loaded", tasks })
        setLoading(false)
      },
      (reason) => {
        if (id !== request.current) return
        setState({ status: "error", error: featureError(reason) })
        setLoading(false)
      }
    )
  }, [projectId, featureId, refresh, reloads])

  return (
    <Card>
      <CardHeader>
        <CardTitle>Pull requests</CardTitle>
        <CardAction>
          <Button
            variant="outline"
            size="sm"
            disabled={loading}
            onClick={reload}
          >
            {loading ? <Spinner aria-hidden /> : <RefreshCwIcon aria-hidden />}
            Refresh
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        {state.status === "loading" ? (
          <div className="flex flex-col gap-3">
            {[0, 1].map((i) => (
              <Skeleton key={i} className="h-12 w-full" />
            ))}
          </div>
        ) : state.status === "error" ? (
          <Alert variant="destructive">
            <AlertTitle>Couldn't load the pull requests</AlertTitle>
            <AlertDescription>
              <p>{state.error.message}</p>
              {state.error.retryable && (
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-2"
                  disabled={loading}
                  onClick={reload}
                >
                  Try again
                </Button>
              )}
            </AlertDescription>
          </Alert>
        ) : (
          <ul className="divide-y">
            {state.tasks.map((task) => (
              <TaskRow
                key={task.number}
                projectId={projectId}
                featureId={featureId}
                task={task}
                initialError={task.number === 0 ? initialError : null}
                onOpened={reload}
              />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

function TaskRow({
  projectId,
  featureId,
  task,
  initialError,
  onOpened,
}: {
  projectId: number
  featureId: number
  task: FeatureTask
  initialError: string | null
  onOpened: () => void
}) {
  const [opening, setOpening] = useState(false)
  const [openError, setOpenError] = useState<{
    title: string
    message: string
  } | null>(
    initialError
      ? { title: "The docs pull request wasn't opened", message: initialError }
      : null
  )
  const label = `${String(task.number).padStart(2, "0")} ${task.layer}${task.number === 0 ? "" : ` ${task.slug}`}`
  const pull = task.pull

  async function open() {
    setOpening(true)
    try {
      await openDocsPull(projectId, featureId)
      setOpenError(null)
      onOpened()
    } catch (reason) {
      setOpenError({
        title: "Couldn't open the pull request",
        message: featureError(reason).message,
      })
    } finally {
      setOpening(false)
    }
  }

  return (
    <li className="flex flex-col gap-2 py-3 first:pt-0 last:pb-0">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="text-sm font-medium">{label}</span>
          <span className="font-mono text-xs break-all text-muted-foreground">
            {task.branch}
          </span>
        </div>
        <div className="flex min-w-0 flex-wrap items-center gap-2 sm:justify-end">
          {pull && (
            <>
              <Link
                to={`/projects/${projectId}/pulls/${pull.number}`}
                className="text-sm underline underline-offset-4"
              >
                #{pull.number}
              </Link>
              <a
                href={pull.htmlUrl}
                target="_blank"
                rel="noreferrer"
                aria-label={`Pull request #${pull.number} on GitHub`}
                className="text-muted-foreground hover:text-foreground"
              >
                <ExternalLinkIcon className="size-3.5" aria-hidden />
              </a>
              <PullRequestStateBadge pull={pull} />
              <span className="font-mono text-xs break-all text-muted-foreground">
                into {pull.baseRef}
              </span>
            </>
          )}
          {task.needsRestack && (
            <StatusBadge tone="warning">Needs restack</StatusBadge>
          )}
        </div>
      </div>
      {pull ? (
        <TaskChecks checks={pull.checks} />
      ) : task.pullError ? (
        <p className="text-sm break-words text-destructive">
          Couldn't read this pull request: {task.pullError}
        </p>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm text-muted-foreground">
            No pull request yet
          </span>
          {task.number === 0 && (
            <Button
              size="sm"
              variant="outline"
              disabled={opening}
              onClick={open}
            >
              {opening && <Spinner aria-hidden />}
              Open pull request
            </Button>
          )}
        </div>
      )}
      {openError && (
        <Alert variant="destructive">
          <AlertTitle>{openError.title}</AlertTitle>
          <AlertDescription>
            <p className="break-words">{openError.message}</p>
          </AlertDescription>
        </Alert>
      )}
    </li>
  )
}
