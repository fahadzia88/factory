import { WorkflowIcon } from "lucide-react"
import { useEffect, useId, useState } from "react"

import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog"
import { Spinner } from "~/components/ui/spinner"
import {
  featureError,
  getProjectWorkflow,
  type FeatureWorkflow,
} from "~/lib/features"

type State =
  | { status: "loading" }
  | { status: "ready"; svg: string }
  | { status: "error"; message: string; retryable: boolean }

/** A button that opens the project's feature workflow as a Mermaid mind map. */
export function WorkflowMindMapDialog({ projectId }: { projectId: number }) {
  const [open, setOpen] = useState(false)
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger render={<Button variant="outline" />}>
        <WorkflowIcon aria-hidden />
        Workflow
      </DialogTrigger>
      <DialogContent className="sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>Feature workflow</DialogTitle>
          <DialogDescription>
            From <code className="font-mono">feature/workflow.json</code> on the
            repository's default branch, as last fetched.
          </DialogDescription>
        </DialogHeader>
        {open && <WorkflowMindMap projectId={projectId} />}
      </DialogContent>
    </Dialog>
  )
}

function WorkflowMindMap({ projectId }: { projectId: number }) {
  const [state, setState] = useState<State>({ status: "loading" })
  const [attempt, setAttempt] = useState(0)
  const id = `workflow-${useId().replace(/[^a-zA-Z0-9]/g, "")}`

  useEffect(() => {
    let cancelled = false
    setState({ status: "loading" })
    ;(async () => {
      try {
        const workflow = await getProjectWorkflow(projectId)
        const { default: mermaid } = await import("mermaid")
        const dark = document.documentElement.classList.contains("dark")
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: "strict",
          theme: dark ? "dark" : "default",
        })
        const { svg } = await mermaid.render(
          `${id}-${attempt}`,
          mindMap(workflow)
        )
        if (!cancelled) setState({ status: "ready", svg })
      } catch (error) {
        if (cancelled) return
        const { message, retryable } = featureError(error)
        setState({ status: "error", message, retryable })
      }
    })()
    return () => {
      cancelled = true
    }
  }, [projectId, attempt, id])

  if (state.status === "loading") {
    return (
      <div className="flex h-64 items-center justify-center">
        <Spinner className="size-6 text-muted-foreground" />
      </div>
    )
  }
  if (state.status === "error") {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn't show the workflow</AlertTitle>
        <AlertDescription>
          <p>{state.message}</p>
          {state.retryable && (
            <Button
              variant="outline"
              size="sm"
              className="mt-2"
              onClick={() => setAttempt((n) => n + 1)}
            >
              Try again
            </Button>
          )}
        </AlertDescription>
      </Alert>
    )
  }
  return (
    <div
      role="img"
      aria-label="Mind map of the feature workflow: its feature states and its step types with their skills and states"
      className="max-h-[70svh] min-w-0 overflow-auto [&_svg]:mx-auto [&_svg]:h-auto [&_svg]:max-w-none"
      // Rendered by Mermaid with securityLevel "strict", from labels it sanitizes
      dangerouslySetInnerHTML={{ __html: state.svg }}
    />
  )
}

/** Mermaid mind map source; labels are quoted so workflow text can't change the syntax. */
export function mindMap(workflow: FeatureWorkflow): string {
  let next = 0
  const lines = ["mindmap"]
  const node = (depth: number, label: string, shape: "(" | "[" = "[") => {
    const text = label.replace(/["`\n\r]/g, "'")
    const close = shape === "(" ? ")" : "]"
    lines.push(`${"  ".repeat(depth)}n${next++}${shape}"${text}"${close}`)
  }

  lines.push(`  root(("Feature workflow"))`)
  node(2, "Feature states", "(")
  workflow.featureStates.forEach((state, i) => node(3, `${i + 1}. ${state}`))
  node(2, "Steps", "(")
  workflow.steps.forEach((step, i) => {
    node(3, `${i + 1}. ${step.title}`)
    node(4, `key: ${step.key}`)
    node(4, `skill: ${step.skill}`)
    node(4, "States", "(")
    step.states.forEach((state) => node(5, state))
  })
  return lines.join("\n")
}
