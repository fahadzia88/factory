import {
  CheckIcon,
  ExternalLinkIcon,
  GitCommitHorizontalIcon,
  GitPullRequestArrowIcon,
  UploadIcon,
} from "lucide-react"
import { useState } from "react"
import { toast } from "sonner"

import { StatusBadge } from "~/components/status-badge"
import { Alert, AlertDescription } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "~/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { Spinner } from "~/components/ui/spinner"
import { formatDateTime } from "~/lib/projects"
import {
  approveTicket,
  commitTicket,
  openTicketPull,
  pushTicket,
  ticketError,
  type Ticket,
  type TicketEvent,
} from "~/lib/tickets"

type Action = "approve" | "commit" | "push" | "pull"

/**
 * Approve, Commit, Push and Create pull request for a ticket with a workspace. One action runs at a time. The pull
 * request needs a pushed branch; the API doesn't say whether it was pushed, so a `push` entry in [history] counts.
 */
export function WorkspaceActions({
  ticket,
  history,
  archived,
  onChanged,
}: {
  ticket: Ticket
  history: TicketEvent[]
  archived: boolean
  onChanged: () => void
}) {
  const [running, setRunning] = useState<Action | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [dialog, setDialog] = useState<"commit" | "pull" | null>(null)
  const defaultTitle = `#${ticket.number} ${ticket.title}`
  const [message, setMessage] = useState(defaultTitle)
  const [pullTitle, setPullTitle] = useState(defaultTitle)

  const pushed = history.some((event) => event.kind === "push")
  const approved = ticket.mode === "implementer"
  const idle = running === null && !archived

  async function run(action: Action, work: () => Promise<void>) {
    setRunning(action)
    setError(null)
    try {
      await work()
    } catch (reason) {
      const problem = ticketError(reason)
      setError(
        problem.retryable ? `${problem.message} Try again.` : problem.message
      )
      // The ticket may have changed elsewhere; show what it is now
      onChanged()
    } finally {
      setRunning(null)
    }
  }

  const approve = () =>
    run("approve", async () => {
      await approveTicket(ticket.projectId, ticket.id)
      toast.success("Investigation approved")
      onChanged()
    })

  const commit = () =>
    run("commit", async () => {
      const result = await commitTicket(ticket.projectId, ticket.id, message)
      setDialog(null)
      if (result.committed) toast.success(`Committed ${result.sha}`)
      else toast.info("Nothing to commit.")
      onChanged()
    })

  const push = () =>
    run("push", async () => {
      const result = await pushTicket(ticket.projectId, ticket.id)
      toast.success(`Pushed ${result.branch}`)
      onChanged()
    })

  const pull = () =>
    run("pull", async () => {
      await openTicketPull(ticket.projectId, ticket.id, {
        title: pullTitle,
        replaceLink: ticket.pullRequestUrl !== null,
      })
      setDialog(null)
      toast.success("Pull request created")
      onChanged()
    })

  return (
    <Card>
      <CardHeader>
        <CardTitle>Actions</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {archived && (
          <p className="text-sm text-muted-foreground">
            Archived projects can't be changed.
          </p>
        )}
        {error && (
          <Alert variant="destructive">
            <AlertDescription className="break-words">{error}</AlertDescription>
          </Alert>
        )}

        <div className="flex flex-wrap items-center gap-2">
          {approved ? (
            <StatusBadge tone="positive">
              Approved
              {ticket.approvedAt ? ` ${formatDateTime(ticket.approvedAt)}` : ""}
            </StatusBadge>
          ) : (
            <Button
              disabled={!idle || !ticket.hasFindings}
              onClick={approve}
            >
              {running === "approve" ? (
                <Spinner aria-hidden />
              ) : (
                <CheckIcon aria-hidden />
              )}
              Approve
            </Button>
          )}
          <Button
            variant="outline"
            disabled={!idle}
            onClick={() => setDialog("commit")}
          >
            <GitCommitHorizontalIcon aria-hidden />
            Commit
          </Button>
          <Button variant="outline" disabled={!idle} onClick={push}>
            {running === "push" ? (
              <Spinner aria-hidden />
            ) : (
              <UploadIcon aria-hidden />
            )}
            Push
          </Button>
          {ticket.pullNumber === null && (
            <Button
              variant="outline"
              disabled={!idle || !pushed}
              onClick={() => setDialog("pull")}
            >
              <GitPullRequestArrowIcon aria-hidden />
              Create pull request
            </Button>
          )}
        </div>

        {!approved && !ticket.hasFindings && (
          <p className="text-sm text-muted-foreground">
            Approve is available once the agent has written findings in
            details.md.
          </p>
        )}
        {ticket.pullNumber === null && !pushed && (
          <p className="text-sm text-muted-foreground">
            Push the branch first to create a pull request.
          </p>
        )}
        {ticket.pullNumber !== null && ticket.pullRequestUrl && (
          <a
            href={ticket.pullRequestUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex min-w-0 items-center gap-1 text-sm font-medium break-all underline-offset-4 hover:underline"
          >
            Pull request #{ticket.pullNumber}
            <ExternalLinkIcon aria-hidden className="size-3.5 shrink-0" />
          </a>
        )}
      </CardContent>

      <Dialog
        open={dialog === "commit"}
        onOpenChange={(open) => !open && setDialog(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Commit changes</DialogTitle>
            <DialogDescription>
              Commits everything in the ticket's worktree to its branch.
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor="commit-message">Message</Label>
            <Input
              id="commit-message"
              value={message}
              onChange={(event) => setMessage(event.target.value)}
              maxLength={200}
              autoComplete="off"
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(null)}>
              Cancel
            </Button>
            <Button disabled={running !== null} onClick={commit}>
              {running === "commit" && <Spinner aria-hidden />}
              Commit
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={dialog === "pull"}
        onOpenChange={(open) => !open && setDialog(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create pull request</DialogTitle>
            <DialogDescription>
              Opens a pull request from {ticket.workspace.branch} into the
              default branch.
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor="pull-title">Title</Label>
            <Input
              id="pull-title"
              value={pullTitle}
              onChange={(event) => setPullTitle(event.target.value)}
              maxLength={200}
              autoComplete="off"
            />
          </div>
          {ticket.pullRequestUrl && (
            <p className="text-sm text-muted-foreground">
              This replaces the link you entered.
            </p>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(null)}>
              Cancel
            </Button>
            <Button disabled={running !== null} onClick={pull}>
              {running === "pull" && <Spinner aria-hidden />}
              Create pull request
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}
