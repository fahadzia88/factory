import { FileQuestionIcon } from "lucide-react"
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
export function FeatureNotFound({ projectId }: { projectId: number | null }) {
  return (
    <Empty className="border border-dashed">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <FileQuestionIcon />
        </EmptyMedia>
        <EmptyTitle>Feature not found</EmptyTitle>
        <EmptyDescription>
          It may belong to another project, or the link is wrong.
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
