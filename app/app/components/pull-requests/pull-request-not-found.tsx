import { GitPullRequestClosedIcon } from "lucide-react"
import { Link } from "react-router"

import { Button } from "~/components/ui/button"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "~/components/ui/empty"

/** `projectId` is null when the project itself doesn't exist. */
export function PullRequestNotFound({
  projectId,
}: {
  projectId: number | null
}) {
  return (
    <Empty className="border border-dashed">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <GitPullRequestClosedIcon />
        </EmptyMedia>
        <EmptyTitle>Pull request not found</EmptyTitle>
        <EmptyDescription>
          It may not exist in this repository, or the link is wrong.
        </EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <Button
          variant="outline"
          render={
            <Link
              to={projectId === null ? "/projects" : `/projects/${projectId}`}
            />
          }
          nativeButton={false}
        >
          {projectId === null ? "Back to projects" : "Back to project"}
        </Button>
      </EmptyContent>
    </Empty>
  )
}
