import { useEffect, useState } from "react"

import { Markdown } from "~/components/pull-requests/markdown"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import { Skeleton } from "~/components/ui/skeleton"
import {
  getDetails,
  ticketError,
  type TicketDetails,
  type TicketError,
} from "~/lib/tickets"

type State =
  | { status: "loading" }
  | { status: "loaded"; details: TicketDetails }
  | { status: "error"; error: TicketError }

/**
 * A ticket's `details.md` as Markdown, read from its worktree so the agent's edits show. It loads again whenever
 * [refreshKey] changes, for example after each turn of the agent.
 */
export function DetailsPane({
  projectId,
  ticketId,
  refreshKey,
}: {
  projectId: number
  ticketId: number
  refreshKey: number
}) {
  const [state, setState] = useState<State>({ status: "loading" })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    getDetails(projectId, ticketId).then(
      (details) => {
        if (!cancelled) setState({ status: "loaded", details })
      },
      (reason) => {
        if (!cancelled) {
          setState({ status: "error", error: ticketError(reason) })
        }
      }
    )
    return () => {
      cancelled = true
    }
  }, [projectId, ticketId, refreshKey, attempt])

  return (
    <Card>
      <CardHeader>
        <CardTitle>Investigation</CardTitle>
        {state.status === "loaded" && (
          <CardDescription className="font-mono break-all">
            {state.details.path}
          </CardDescription>
        )}
      </CardHeader>
      <CardContent className="min-w-0">
        {state.status === "loading" ? (
          <div className="flex flex-col gap-3">
            <Skeleton className="h-6 w-1/2" />
            <Skeleton className="h-16 w-full" />
          </div>
        ) : state.status === "error" ? (
          <Alert variant="destructive">
            <AlertTitle>Couldn't load details.md</AlertTitle>
            <AlertDescription>
              <p>{state.error.message}</p>
              <Button
                variant="outline"
                size="sm"
                className="mt-2"
                onClick={() => {
                  setState({ status: "loading" })
                  setAttempt((n) => n + 1)
                }}
              >
                Try again
              </Button>
            </AlertDescription>
          </Alert>
        ) : (
          <Markdown>{state.details.content}</Markdown>
        )}
      </CardContent>
    </Card>
  )
}
