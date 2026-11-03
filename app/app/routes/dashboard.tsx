import { LayoutDashboardIcon } from "lucide-react"

import { PageHeader } from "~/components/page-header"
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "~/components/ui/empty"

export function meta() {
  return [{ title: "Dashboard" }]
}

// Signed-out visitors are sent to /login by the app layout's loader
export default function Dashboard() {
  return (
    <>
      <PageHeader title="Dashboard" />
      <Empty className="border border-dashed">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <LayoutDashboardIcon />
          </EmptyMedia>
          <EmptyTitle>Nothing here yet</EmptyTitle>
          <EmptyDescription>
            Build your first feature to see it here.
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    </>
  )
}
