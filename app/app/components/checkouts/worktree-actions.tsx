import { Trash2Icon, UploadIcon } from "lucide-react"
import { useState } from "react"
import { toast } from "sonner"

import { UnsavedWorkList } from "~/components/checkouts/unsaved-work-list"
import { WorktreeRow } from "~/components/checkouts/worktree-list"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog"
import { Spinner } from "~/components/ui/spinner"
import {
  checkoutError,
  pushWorktree,
  removeWorktree,
  type CheckoutError,
  type PushResult,
  type UnsavedWork,
  type Worktree,
} from "~/lib/checkouts"

type Pending = false | "push" | "force" | "remove"

// the card reloads after these, so the row shows the API's current view
const PUSH_REFRESH_CODES = [
  "worktree_not_found",
  "worktree_missing",
  "checkout_not_found",
]

/** A worktree row with its Push and Remove actions. */
export function WorktreeActions({
  projectId,
  worktree,
  defaultBranch,
  onChanged,
}: {
  projectId: number
  worktree: Worktree
  defaultBranch: string | null
  onChanged: () => void
}) {
  const [pending, setPending] = useState<Pending>(false)
  const [pushed, setPushed] = useState<PushResult | null>(null)
  const [pushError, setPushError] = useState<CheckoutError | null>(null)
  const [forced, setForced] = useState(false)
  const [removeOpen, setRemoveOpen] = useState(false)

  const canPush =
    !worktree.main && !worktree.missing && worktree.branch !== defaultBranch

  async function push(force: boolean) {
    setPending(force ? "force" : "push")
    setPushError(null)
    setPushed(null)
    setForced(force)
    try {
      const result = await pushWorktree(projectId, worktree.id, force)
      setPushed(result)
      toast.success(`Pushed ${worktree.branch}`)
    } catch (e) {
      const error = checkoutError(e)
      setPushError(error)
      if (error.code && PUSH_REFRESH_CODES.includes(error.code)) onChanged()
    } finally {
      setPending(false)
    }
  }

  const actions = (
    <>
      {canPush && (
        <Button
          variant="outline"
          size="sm"
          disabled={pending !== false}
          onClick={() => void push(false)}
        >
          {pending === "push" ? <Spinner /> : <UploadIcon aria-hidden />}
          Push
        </Button>
      )}
      {!worktree.main && (
        <Button
          variant="outline"
          size="sm"
          disabled={pending !== false}
          onClick={() => setRemoveOpen(true)}
        >
          {pending === "remove" ? <Spinner /> : <Trash2Icon aria-hidden />}
          Remove
        </Button>
      )}
    </>
  )

  const footer = (
    <>
      {pushed && (
        <p className="text-sm break-all text-muted-foreground">
          Pushed to GitHub at <span className="font-mono">{pushed.head}</span>
        </p>
      )}
      {pushError && (
        <PushErrorAlert
          error={pushError}
          forced={forced}
          pending={pending !== false}
          onForce={() => void push(true)}
          onRetry={() => void push(forced)}
        />
      )}
    </>
  )

  return (
    <>
      <WorktreeRow
        worktree={worktree}
        actions={worktree.main ? undefined : actions}
        footer={pushed || pushError ? footer : undefined}
      />
      {!worktree.main && (
        <RemoveWorktreeDialog
          projectId={projectId}
          worktree={worktree}
          open={removeOpen}
          onOpenChange={setRemoveOpen}
          pending={pending === "remove"}
          onPendingChange={(p) => setPending(p ? "remove" : false)}
          onChanged={onChanged}
        />
      )}
    </>
  )
}

function PushErrorAlert({
  error,
  forced,
  pending,
  onForce,
  onRetry,
}: {
  error: CheckoutError
  forced: boolean
  pending: boolean
  onForce: () => void
  onRetry: () => void
}) {
  if (error.code === "push_rejected") {
    return (
      <Alert variant="destructive">
        <AlertTitle>GitHub rejected the push</AlertTitle>
        <AlertDescription>
          <p className="break-words">{error.message}</p>
          {forced ? (
            <p>
              The branch on GitHub moved since the last fetch. Fetch, check what
              changed, then push again.
            </p>
          ) : (
            <>
              <p>
                If you rebased this branch, force push with lease. It overwrites
                the branch on GitHub only if nobody pushed to it since the last
                fetch.
              </p>
              <Button
                variant="outline"
                size="sm"
                className="mt-2"
                disabled={pending}
                onClick={onForce}
              >
                Force with lease
              </Button>
            </>
          )}
        </AlertDescription>
      </Alert>
    )
  }
  return (
    <Alert variant="destructive">
      <AlertTitle>Couldn't push</AlertTitle>
      <AlertDescription>
        <p className="break-words">{error.message}</p>
        {error.retryable && (
          <Button
            variant="outline"
            size="sm"
            className="mt-2"
            disabled={pending}
            onClick={onRetry}
          >
            Try again
          </Button>
        )}
      </AlertDescription>
    </Alert>
  )
}

export function RemoveWorktreeDialog({
  projectId,
  worktree,
  open,
  onOpenChange,
  pending,
  onPendingChange,
  onChanged,
}: {
  projectId: number
  worktree: Worktree
  open: boolean
  onOpenChange: (open: boolean) => void
  pending: boolean
  onPendingChange: (pending: boolean) => void
  onChanged: () => void
}) {
  const [step, setStep] = useState<"confirm" | "unsaved">("confirm")
  const [unsaved, setUnsaved] = useState<UnsavedWork | null>(null)
  const [unsavedDetail, setUnsavedDetail] = useState("")
  const [error, setError] = useState<CheckoutError | null>(null)

  function changeOpen(next: boolean) {
    if (pending) return
    if (!next) {
      setStep("confirm")
      setUnsaved(null)
      setError(null)
    }
    onOpenChange(next)
  }

  async function remove(force: boolean) {
    setError(null)
    onPendingChange(true)
    let close = false
    try {
      await removeWorktree(projectId, worktree.id, force)
      toast.success("Worktree removed")
      close = true
      onChanged()
    } catch (e) {
      const err = checkoutError(e)
      if (err.code === "worktree_unsaved" && !force) {
        setUnsaved(err.unsaved)
        setUnsavedDetail(err.message)
        setStep("unsaved")
      } else if (
        err.code === "worktree_not_found" ||
        err.code === "checkout_not_found"
      ) {
        toast("Worktree was already removed")
        close = true
        onChanged()
      } else {
        setError(err)
      }
    } finally {
      onPendingChange(false)
    }
    if (close) {
      setStep("confirm")
      setUnsaved(null)
      onOpenChange(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent showCloseButton={!pending}>
        {step === "confirm" ? (
          <DialogHeader>
            <DialogTitle>Remove worktree?</DialogTitle>
            <p className="font-mono text-sm break-all">{worktree.branch}</p>
            <DialogDescription className="break-words">
              {worktree.missing ? (
                "The folder is already gone. This clears its record."
              ) : (
                <>
                  Deletes <span className="break-all">{worktree.path}</span>.
                  The branch stays on GitHub if it was pushed.
                </>
              )}
            </DialogDescription>
          </DialogHeader>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>This worktree has unsaved work</DialogTitle>
              <DialogDescription>
                These changes and commits aren't on GitHub. Removing the
                worktree deletes them.
              </DialogDescription>
            </DialogHeader>
            {unsaved ? (
              <UnsavedWorkList items={[unsaved]} />
            ) : (
              <p className="break-words">{unsavedDetail}</p>
            )}
          </>
        )}
        {error && (
          <Alert variant="destructive">
            <AlertDescription className="break-words">
              {error.message}
            </AlertDescription>
          </Alert>
        )}
        <DialogFooter>
          <DialogClose
            render={<Button variant="outline" type="button" />}
            disabled={pending}
          >
            Cancel
          </DialogClose>
          <Button
            variant="destructive"
            disabled={pending}
            onClick={() => void remove(step === "unsaved")}
          >
            {pending && <Spinner />}
            {step === "unsaved" ? "Remove anyway" : "Remove"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
