import { PlusIcon } from "lucide-react"
import { useState, type FormEvent } from "react"
import { toast } from "sonner"

import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "~/components/ui/field"
import { Input } from "~/components/ui/input"
import { Spinner } from "~/components/ui/spinner"
import {
  addWorktree,
  checkoutError,
  type AddWorktreeInput,
  type CheckoutError,
} from "~/lib/checkouts"

type Errors = {
  branch?: string
  base?: string
  form?: { error: CheckoutError; input: AddWorktreeInput }
}

export function AddWorktreeForm({
  projectId,
  defaultBranch,
  onAdded,
}: {
  projectId: number
  defaultBranch: string | null
  onAdded: () => void
}) {
  const [branch, setBranch] = useState("")
  const [base, setBase] = useState("")
  const [errors, setErrors] = useState<Errors>({})
  const [pending, setPending] = useState(false)
  const fallbackBase = defaultBranch ?? "main"

  async function add(input: AddWorktreeInput) {
    setErrors({})
    setPending(true)
    try {
      await addWorktree(projectId, input)
      setBranch("")
      setBase("")
      toast.success(`Worktree added for ${input.branch}`)
      onAdded()
    } catch (e) {
      const error = checkoutError(e)
      switch (error.code) {
        case "invalid_branch":
        case "branch_checked_out":
          setErrors({ branch: error.message })
          break
        case "base_not_found":
          setErrors({ base: error.message })
          break
        case "checkout_not_found":
          onAdded()
          break
        default:
          setErrors({ form: { error, input } })
      }
    } finally {
      setPending(false)
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmedBranch = branch.trim()
    const trimmedBase = base.trim()
    if (trimmedBranch === "") {
      setErrors({ branch: "Enter a branch name." })
      return
    }
    void add({ branch: trimmedBranch, base: trimmedBase || undefined })
  }

  const form = errors.form

  return (
    <form onSubmit={submit} className="flex min-w-0 flex-col gap-4">
      {form && (
        <Alert variant="destructive">
          <AlertTitle>Couldn't add the worktree</AlertTitle>
          <AlertDescription>
            <p>{form.error.message}</p>
            {form.error.retryable && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="mt-2"
                disabled={pending}
                onClick={() => void add(form.input)}
              >
                Try again
              </Button>
            )}
          </AlertDescription>
        </Alert>
      )}
      <FieldGroup className="sm:grid sm:grid-cols-2">
        <Field data-invalid={errors.branch ? true : undefined}>
          <FieldLabel htmlFor="worktree-branch">Branch</FieldLabel>
          <Input
            id="worktree-branch"
            name="branch"
            className="font-mono"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            placeholder="feat/my-change"
            required
            autoComplete="off"
            aria-invalid={errors.branch ? true : undefined}
            aria-describedby={
              errors.branch ? "worktree-branch-error" : undefined
            }
          />
          {errors.branch && (
            <FieldError id="worktree-branch-error" className="break-words">
              {errors.branch}
            </FieldError>
          )}
        </Field>
        <Field data-invalid={errors.base ? true : undefined}>
          <FieldLabel htmlFor="worktree-base">Base branch</FieldLabel>
          <Input
            id="worktree-base"
            name="base"
            className="font-mono"
            value={base}
            onChange={(e) => setBase(e.target.value)}
            placeholder={fallbackBase}
            autoComplete="off"
            aria-invalid={errors.base ? true : undefined}
            aria-describedby={errors.base ? "worktree-base-error" : undefined}
          />
          <FieldDescription>
            Used only when the branch isn't on GitHub yet. Defaults to{" "}
            {fallbackBase}.
          </FieldDescription>
          {errors.base && (
            <FieldError id="worktree-base-error" className="break-words">
              {errors.base}
            </FieldError>
          )}
        </Field>
      </FieldGroup>
      <div>
        <Button type="submit" disabled={pending}>
          {pending ? <Spinner /> : <PlusIcon aria-hidden />}
          Add worktree
        </Button>
      </div>
    </form>
  )
}
