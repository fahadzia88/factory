import {
  ArrowUpIcon,
  BotIcon,
  CircleAlertIcon,
  SquareIcon,
  WrenchIcon,
} from "lucide-react"
import { memo, useEffect, useRef, useState } from "react"
import { useNavigate } from "react-router"

import { Markdown } from "~/components/pull-requests/markdown"
import { StatusBadge } from "~/components/status-badge"
import {
  useTicketChat,
  type TicketChatHistory,
  type TicketChatMessage,
} from "~/components/tickets/use-ticket-chat"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "~/components/ui/empty"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupTextarea,
} from "~/components/ui/input-group"
import { Skeleton } from "~/components/ui/skeleton"
import { Spinner } from "~/components/ui/spinner"
import { logout } from "~/lib/auth"
import type { TicketError } from "~/lib/tickets"
import { cn } from "~/lib/utils"

const TOOL_STATE = { running: "running", completed: "done", failed: "failed" }

/** The ticket's one conversation with the agent; there's no "New chat". */
export function TicketChat({
  projectId,
  ticketId,
  mode,
  archived,
  onTurnEnd,
}: {
  projectId: number
  ticketId: number
  mode: "investigator" | "implementer"
  archived: boolean
  onTurnEnd: () => void
}) {
  const chat = useTicketChat(projectId, ticketId, onTurnEnd)
  const [draft, setDraft] = useState("")
  const loaded = chat.history.status === "loaded"
  const investigating = mode === "investigator"

  function submit() {
    if (!draft.trim() || !loaded || chat.turn || archived) return
    const text = draft
    setDraft("")
    chat.send(text).then((restore) => {
      if (restore !== null) setDraft((current) => current || restore)
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          <BotIcon className="size-4 text-muted-foreground" aria-hidden />
          Agent
          {chat.turn ? (
            <StatusBadge tone="warning">Working</StatusBadge>
          ) : (
            <StatusBadge tone="positive">Ready</StatusBadge>
          )}
        </CardTitle>
        <p className="text-sm text-muted-foreground">
          {investigating
            ? "Investigator: reads the code and writes its findings in details.md."
            : "Implementer: can change code in the worktree."}
        </p>
      </CardHeader>
      <CardContent className="flex min-h-0 flex-col gap-4">
        <Messages
          history={chat.history}
          thinking={chat.turn?.thinking ?? false}
          busy={chat.turn !== null}
          investigating={investigating}
          onRetry={chat.reloadHistory}
        />
        {chat.error && <ChatError error={chat.error} />}
      </CardContent>
      <CardFooter>
        <form
          className="w-full"
          onSubmit={(event) => {
            event.preventDefault()
            submit()
          }}
        >
          <InputGroup>
            <InputGroupTextarea
              aria-label="Message the agent"
              placeholder={
                archived
                  ? "Archived projects can't be changed."
                  : investigating
                    ? "Ask the agent to investigate…"
                    : "Ask the agent to make the change…"
              }
              disabled={archived}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                // Enter sends; Shift+Enter adds a line
                if (
                  event.key === "Enter" &&
                  !event.shiftKey &&
                  !event.nativeEvent.isComposing
                ) {
                  event.preventDefault()
                  submit()
                }
              }}
              className="max-h-40"
            />
            <InputGroupAddon align="block-end" className="justify-end">
              {chat.turn && (
                <InputGroupButton
                  variant="outline"
                  size="sm"
                  onClick={chat.stop}
                >
                  <SquareIcon aria-hidden />
                  Stop
                </InputGroupButton>
              )}
              <InputGroupButton
                type="submit"
                variant="default"
                size="icon-sm"
                disabled={
                  !draft.trim() || !loaded || chat.turn !== null || archived
                }
                aria-label="Send"
              >
                <ArrowUpIcon aria-hidden />
              </InputGroupButton>
            </InputGroupAddon>
          </InputGroup>
        </form>
      </CardFooter>
    </Card>
  )
}

function Messages({
  history,
  thinking,
  busy,
  investigating,
  onRetry,
}: {
  history: TicketChatHistory
  thinking: boolean
  busy: boolean
  investigating: boolean
  onRetry: () => void
}) {
  const end = useRef<HTMLDivElement>(null)

  // Keep the newest text in view as it streams
  useEffect(() => {
    end.current?.scrollIntoView({ block: "nearest" })
  }, [history, thinking])

  if (history.status === "loading") {
    return (
      <div className="flex flex-col gap-4">
        <Skeleton className="h-10 w-2/3 self-end" />
        <Skeleton className="h-16 w-3/4" />
      </div>
    )
  }
  if (history.status === "error") {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn't load the conversation</AlertTitle>
        <AlertDescription>
          <p>{history.error.message}</p>
          {history.error.retryable && (
            <Button
              variant="outline"
              size="sm"
              className="mt-2"
              onClick={onRetry}
            >
              Try again
            </Button>
          )}
        </AlertDescription>
      </Alert>
    )
  }
  if (history.messages.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <BotIcon />
          </EmptyMedia>
          <EmptyTitle>
            {investigating
              ? "Ask the agent to investigate this ticket"
              : "Ask the agent to make the change"}
          </EmptyTitle>
          <EmptyDescription>
            {investigating
              ? 'For example "Find out why this happens and write the plan in details.md".'
              : 'For example "Make the change from the plan in details.md".'}
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }

  return (
    <div
      className="flex max-h-[24rem] min-h-48 min-w-0 flex-col gap-4 overflow-y-auto lg:max-h-[32rem]"
      role="log"
      aria-live="polite"
      aria-busy={busy}
    >
      {history.messages.map((message) => (
        <Message key={message.key} message={message} />
      ))}
      {thinking && (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Spinner aria-hidden />
          Thinking…
        </div>
      )}
      <div ref={end} />
    </div>
  )
}

// Memoized so earlier messages don't re-parse their Markdown on every streamed delta
const Message = memo(function Message({
  message,
}: {
  message: TicketChatMessage
}) {
  const fromUser = message.role === "user"
  return (
    <div
      className={cn(
        "flex max-w-[85%] min-w-0 flex-col gap-2",
        fromUser ? "items-end self-end" : "items-start self-start"
      )}
    >
      {message.tools.map((tool) => (
        <Badge key={tool.id} variant="outline" className="max-w-full gap-1.5">
          <WrenchIcon aria-hidden />
          <span className="truncate">
            {tool.name.replace(/^mcp__app__/, "")}
          </span>
          <span className="text-muted-foreground">
            {TOOL_STATE[tool.status] ?? tool.status}
          </span>
        </Badge>
      ))}
      {message.text &&
        (fromUser ? (
          <div className="rounded-lg bg-primary px-3 py-2 text-sm break-words whitespace-pre-wrap text-primary-foreground">
            {message.text}
          </div>
        ) : (
          <div className="max-w-full min-w-0 rounded-lg bg-muted px-3 py-2 break-words">
            <Markdown>{message.text}</Markdown>
          </div>
        ))}
      {message.failed && (
        <span className="text-xs text-destructive">Not sent</span>
      )}
    </div>
  )
})

function ChatError({ error }: { error: TicketError }) {
  const navigate = useNavigate()
  const expired = error.status === 401
  return (
    <Alert variant="destructive">
      <CircleAlertIcon />
      <AlertTitle>The agent couldn't answer</AlertTitle>
      <AlertDescription>
        <p>
          {expired ? "Your session has expired. Sign in again." : error.message}
        </p>
        {expired && (
          <Button
            variant="outline"
            size="sm"
            className="mt-2"
            onClick={() => {
              logout()
              navigate("/login")
            }}
          >
            Sign in
          </Button>
        )}
      </AlertDescription>
    </Alert>
  )
}
