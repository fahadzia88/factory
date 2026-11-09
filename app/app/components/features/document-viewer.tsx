import { FileQuestionIcon, FileTextIcon } from "lucide-react"
import { useEffect, useRef, useState } from "react"

import { Markdown } from "~/components/pull-requests/markdown"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "~/components/ui/empty"
import { Skeleton } from "~/components/ui/skeleton"
import {
  featureErrorCode,
  getFeatureFile,
  type FeatureFile,
} from "~/lib/feature-files"
import { featureError, type FeatureError } from "~/lib/features"
import { formatDateTime } from "~/lib/projects"

type ViewerState =
  | { status: "idle" }
  | { status: "loading"; file: FeatureFile | null }
  | { status: "loaded"; file: FeatureFile }
  | { status: "error"; code: string | null; error: FeatureError }

export function DocumentViewer({
  projectId,
  featureId,
  path,
  reloadKey,
}: {
  projectId: number
  featureId: number
  /** the file to show, relative to the feature folder; null shows a prompt */
  path: string | null
  /** bump to reload the open file */
  reloadKey: number
}) {
  const [state, setState] = useState<ViewerState>({ status: "idle" })
  const [retries, setRetries] = useState(0)
  const requestId = useRef(0)
  const loadedPath = useRef<string | null>(null)
  // the path shown on first render isn't focused, so opening the page doesn't move focus
  const focusedPath = useRef(path)
  const heading = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const id = ++requestId.current
    if (path === null) {
      loadedPath.current = null
      setState({ status: "idle" })
      return
    }
    // reloading the same path keeps the previous file on screen so it doesn't flash
    const samePath = loadedPath.current === path
    loadedPath.current = path
    setState((prev) => ({
      status: "loading",
      file: samePath && "file" in prev ? prev.file : null,
    }))
    getFeatureFile(projectId, featureId, path).then(
      (file) => {
        if (id === requestId.current) setState({ status: "loaded", file })
      },
      (e: unknown) => {
        if (id !== requestId.current) return
        setState({
          status: "error",
          code: featureErrorCode(e),
          error: featureError(e),
        })
      }
    )
    return () => {
      // answers after the inputs change or the component unmounts are ignored
      requestId.current++
    }
  }, [projectId, featureId, path, reloadKey, retries])

  const settled = state.status === "loaded" || state.status === "error"
  useEffect(() => {
    if (!settled || path === null || focusedPath.current === path) return
    focusedPath.current = path
    heading.current?.focus()
  }, [settled, path])

  const file =
    state.status === "loaded" || state.status === "loading" ? state.file : null

  return (
    <div
      className="min-w-0 rounded-lg border"
      aria-busy={state.status === "loading"}
      aria-live="polite"
    >
      {state.status === "idle" ? (
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FileTextIcon />
            </EmptyMedia>
            <EmptyTitle>Select a document</EmptyTitle>
            <EmptyDescription>
              Choose a markdown file from the list to read it here.
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : state.status === "loading" && file === null ? (
        <div className="flex flex-col gap-3 p-4">
          <Skeleton className="h-5 w-1/3" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-5/6" />
          <Skeleton className="h-4 w-2/3" />
        </div>
      ) : (
        <>
          <div
            ref={heading}
            tabIndex={-1}
            className="flex flex-col gap-1 border-b px-4 py-3 outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
          >
            <span className="font-mono text-xs break-all">
              {file?.path ?? path}
            </span>
            {file && (
              <span className="text-xs text-muted-foreground">
                Updated {formatDateTime(file.updatedAt)}
              </span>
            )}
          </div>
          {state.status === "error" ? (
            state.code === "file_not_found" ? (
              <Empty>
                <EmptyHeader>
                  <EmptyMedia variant="icon">
                    <FileQuestionIcon />
                  </EmptyMedia>
                  <EmptyTitle>This file no longer exists</EmptyTitle>
                  <EmptyDescription>
                    It was removed or renamed. Refresh the list to see what's
                    there.
                  </EmptyDescription>
                </EmptyHeader>
              </Empty>
            ) : (
              <div className="p-4">
                <Alert variant="destructive">
                  <AlertTitle>Couldn't open the file</AlertTitle>
                  <AlertDescription>
                    <p>{state.error.message}</p>
                    {state.error.retryable && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="mt-2"
                        onClick={() => setRetries((n) => n + 1)}
                      >
                        Try again
                      </Button>
                    )}
                  </AlertDescription>
                </Alert>
              </div>
            )
          ) : file && file.content === "" ? (
            <p className="p-4 text-sm text-muted-foreground">
              This file is empty.
            </p>
          ) : (
            file && (
              <div className="min-w-0 overflow-x-auto p-4">
                <Markdown>{file.content}</Markdown>
              </div>
            )
          )}
        </>
      )}
    </div>
  )
}
