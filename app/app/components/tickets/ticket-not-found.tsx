import { TicketXIcon } from "lucide-react"
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
export function TicketNotFound({ projectId }: { projectId: number | null }) {
  return (
    <Empty className="border border-dashed">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <TicketXIcon />
        </EmptyMedia>
        <EmptyTitle>Ticket not found</EmptyTitle>
        <EmptyDescription>
          It may belong to another project, or the link is wrong.
        </EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <Button
          variant="outline"
          render={
            <Link
              to={
                projectId === null
                  ? "/projects"
                  : `/projects/${projectId}/tickets`
              }
            />
          }
          nativeButton={false}
        >
          {projectId === null ? "Back to projects" : "Back to tickets"}
        </Button>
      </EmptyContent>
    </Empty>
  )
}
