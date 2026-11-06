import { ChevronRightIcon, ExternalLinkIcon } from "lucide-react"
import { useState } from "react"
import { Link } from "react-router"

import { FileDiff } from "~/components/pull-requests/file-diff"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import type { PullRequestFile, PullRequestPage } from "~/lib/pull-requests"

const STATUS_LABEL: Record<string, string> = {
  added: "Added",
  removed: "Removed",
  modified: "Modified",
  renamed: "Renamed",
  copied: "Copied",
  changed: "Changed",
  unchanged: "Unchanged",
}

export function ChangedFiles({
  files,
  total,
}: {
  files: PullRequestPage<PullRequestFile>
  total: number
}) {
  const first = (files.page - 1) * files.size + 1
  const last = first + files.items.length - 1
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          Changed files{" "}
          <span className="font-normal text-muted-foreground tabular-nums">
            {total}
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent>
        {files.items.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {files.page > 1 ? (
              <>
                No files on this page.{" "}
                <Link to="?" className="underline underline-offset-4">
                  Back to the first page
                </Link>
              </>
            ) : (
              "No files changed."
            )}
          </p>
        ) : (
          <ul className="flex flex-col gap-2">
            {files.items.map((file) => (
              <li key={`${files.page}:${file.filename}`}>
                <ChangedFile file={file} />
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      {(files.page > 1 || files.hasNext) && (
        <CardFooter className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-sm text-muted-foreground tabular-nums">
            {files.items.length > 0 && `Showing ${first}–${last} of ${total}`}
          </span>
          <div className="flex gap-2">
            {files.page > 1 && (
              <Button
                variant="outline"
                size="sm"
                render={
                  <Link
                    to={files.page === 2 ? "?" : `?page=${files.page - 1}`}
                    preventScrollReset
                  />
                }
                nativeButton={false}
              >
                Previous
              </Button>
            )}
            {files.hasNext && (
              <Button
                variant="outline"
                size="sm"
                render={
                  <Link to={`?page=${files.page + 1}`} preventScrollReset />
                }
                nativeButton={false}
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

function ChangedFile({ file }: { file: PullRequestFile }) {
  const [open, setOpen] = useState(false)
  return (
    <details
      className="group rounded-lg border"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="flex cursor-pointer list-none flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 [&::-webkit-details-marker]:hidden">
        <ChevronRightIcon
          aria-hidden
          className="size-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-90"
        />
        <span className="min-w-0 flex-1 font-mono text-sm break-all">
          {file.previousFilename && (
            <span className="text-muted-foreground">
              {file.previousFilename} →{" "}
            </span>
          )}
          {file.filename}
        </span>
        <span className="flex items-center gap-2 text-xs">
          <Badge variant="outline">
            {STATUS_LABEL[file.status] ?? file.status}
          </Badge>
          <span className="tabular-nums">
            +{file.additions} −{file.deletions}
          </span>
        </span>
      </summary>
      {open &&
        (file.patch ? (
          <FileDiff patch={file.patch} />
        ) : (
          <p className="flex flex-wrap items-center gap-x-2 border-t px-3 py-3 text-sm text-muted-foreground">
            The diff isn't available here (binary or too large).
            <a
              href={file.htmlUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 font-medium text-foreground underline-offset-4 hover:underline"
            >
              View on GitHub
              <ExternalLinkIcon aria-hidden className="size-3.5" />
            </a>
          </p>
        ))}
    </details>
  )
}
