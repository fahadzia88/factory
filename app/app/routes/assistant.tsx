import { AgentChat } from "~/components/agent/agent-chat"
import { PageHeader } from "~/components/page-header"

export function meta() {
  return [{ title: "Assistant" }]
}

// Signed-out visitors are sent to /login by the app layout's loader
export default function Assistant() {
  return (
    <>
      <PageHeader
        title="Assistant"
        description="Chat with the AI agent. Replies stream in as it works."
      />
      {/* Fill the rest of the viewport so only the message list scrolls */}
      <AgentChat className="h-[calc(100svh-12rem)] min-h-96" />
    </>
  )
}
