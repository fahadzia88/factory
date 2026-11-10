import { FolderOpenIcon, RefreshCwIcon } from "lucide-react"
import { useCallback, useEffect, useRef, useState } from "react"
import { Link } from "react-router"

import { DocumentViewer } from "~/components/features/document-viewer"
import { FileTree } from "~/components/features/file-tree"
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
import { Spinner } from "~/components/ui/spinner"
import {
  featureErrorCode,
  getFeatureFiles,
  type FeatureFileTree,
} from "~/lib/feature-files"
import { featureError, type FeatureError } from "~/lib/features"

type TreeState =
  | { status: "loading"; tree: FeatureFileTree | null }
  | { status: "loaded"; tree: FeatureFileTree }
  | { status: "error"; code: string | null; error: FeatureError }

/** The feature folder's markdown files: a tree on the left, the open file on the right. */
export function DocumentsTab({
  projectId,
  featureId,
  refreshKey,
}: {
  projectId: number
  featureId: number
  /** bump to reload the tree and the open file, e.g. after a chat turn */
  refreshKey: number
}) {
  const [state, setState] = useState<TreeState>({
    status: "loading",
    tree: null,
  })
  const [selectedPath, setSelectedPath] = useState<string | null>(null)
  const [viewerKey, setViewerKey] = useState(0)
  const requestId = useRef(0)

  const loadTree = useCallback(() => {
    const id = ++requestId.current
    // the previous tree stays on screen while reloading so it doesn't flash
    setState((prev) => ({
      status: "loading",
      tree: prev.status === "error" ? null : prev.tree,
    }))
    setViewerKey((n) => n + 1)
    getFeatureFiles(projectId, featureId).then(
      (tree) => {
        if (id === requestId.current) setState({ status: "loaded", tree })
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
  }, [projectId, featureId])

  useEffect(() => {
    loadTree()
    return () => {
      // answers after the inputs change or the component unmounts are ignored
      requestId.current++
    }
  }, [loadTree, refreshKey])

  const loading = state.status === "loading"
  const tree = state.status === "error" ? null : state.tree

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <p className="min-w-0 font-mono text-xs break-all text-muted-foreground">
          {tree?.root}
        </p>
        <Button
          variant="outline"
          size="sm"
          aria-label="Refresh documents"
          disabled={loading}
          onClick={loadTree}
        >
          {loading ? <Spinner /> : <RefreshCwIcon />}
          Refresh
        </Button>
      </div>

      {state.status === "error" ? (
        state.code === "worktree_missing" ? (
          <Alert>
            <AlertTitle>The worktree is missing</AlertTitle>
            <AlertDescription>
              <p>{state.error.message}</p>
              <Button
                variant="outline"
                size="sm"
                className="mt-2"
                render={<Link to={`/projects/${projectId}`} />}
                nativeButton={false}
              >
                Go to project
              </Button>
            </AlertDescription>
          </Alert>
        ) : (
          <Alert variant="destructive">
            <AlertTitle>Couldn't load the documents</AlertTitle>
            <AlertDescription>
              <p>{state.error.message}</p>
              {state.error.retryable && (
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-2"
                  onClick={loadTree}
                >
                  Try again
                </Button>
              )}
            </AlertDescription>
          </Alert>
        )
      ) : tree === null ? (
        <div className="grid gap-4 md:grid-cols-[16rem_minmax(0,1fr)]">
          <div className="flex flex-col gap-2 rounded-lg border p-3">
            {Array.from({ length: 5 }, (_, i) => (
              <Skeleton key={i} className="h-5 w-full" />
            ))}
          </div>
          <div className="flex flex-col gap-3 rounded-lg border p-4">
            <Skeleton className="h-5 w-1/3" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-5/6" />
            <Skeleton className="h-4 w-2/3" />
          </div>
        </div>
      ) : tree.entries.length === 0 ? (
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FolderOpenIcon />
            </EmptyMedia>
            <EmptyTitle>No documents yet</EmptyTitle>
            <EmptyDescription>
              Documents appear here once the agent writes them.
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <div className="grid min-w-0 gap-4 md:grid-cols-[16rem_minmax(0,1fr)]">
          <div className="max-h-72 min-w-0 overflow-auto rounded-lg border p-1 md:max-h-[40rem]">
            <FileTree
              entries={tree.entries}
              selectedPath={selectedPath}
              onSelect={setSelectedPath}
            />
          </div>
          <DocumentViewer
            projectId={projectId}
            featureId={featureId}
            path={selectedPath}
            reloadKey={viewerKey}
          />
        </div>
      )}
    </div>
  )
}
