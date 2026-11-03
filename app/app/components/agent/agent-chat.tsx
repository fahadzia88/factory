import { ClientError } from "eve/client"
import { activeTurn } from "eve/events"
import {
  useEveAgent,
  type EveMessage,
  type EveMessagePart,
  type Interaction,
} from "eve/react"
import {
  ArrowUpIcon,
  BotIcon,
  CircleAlertIcon,
  FileIcon,
  SquareIcon,
  SquarePenIcon,
  WrenchIcon,
} from "lucide-react"
import { useEffect, useRef, useState } from "react"
import { useNavigate } from "react-router"

import { StatusBadge, type StatusTone } from "~/components/status-badge"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardAction,
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
import { Spinner } from "~/components/ui/spinner"
import { logout } from "~/lib/auth"
import { getPassword } from "~/lib/password"
import { cn } from "~/lib/utils"

/** Chat with the eve agent. "New chat" remounts the conversation, so it starts a fresh session. */
export function AgentChat({ className }: { className?: string }) {
  const [chat, setChat] = useState(0)
  return (
    <Conversation
      key={chat}
      className={className}
      onNewChat={() => setChat((n) => n + 1)}
    />
  )
}

const STATUS: Record<string, { tone: StatusTone; label: string }> = {
  idle: { tone: "positive", label: "Ready" },
  busy: { tone: "warning", label: "Working" },
  resuming: { tone: "neutral", label: "Reconnecting" },
}

function Conversation({
  className,
  onNewChat,
}: {
  className?: string
  onNewChat: () => void
}) {
  // Same-origin: Vite proxies /eve to the agent. The agent checks the same token as the API.
  const agent = useEveAgent({ auth: { bearer: () => getPassword() ?? "" } })
  const [draft, setDraft] = useState("")
  const turnOpen = activeTurn(agent.view) !== undefined
  const resuming = agent.status === "resuming"
  const status = STATUS[agent.status] ?? STATUS.idle

  function submit() {
    const text = draft.trim()
    if (!text || resuming) return
    setDraft("")
    // Rejections also land in agent.error, which the alert shows
    agent.send(text).catch(() => {})
  }

  return (
    <Card className={cn("min-h-0", className)}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <BotIcon className="size-4 text-muted-foreground" aria-hidden />
          Assistant
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
        </CardTitle>
        <CardAction>
          <Button
            variant="ghost"
            size="sm"
            onClick={onNewChat}
            disabled={agent.messages.length === 0}
          >
            <SquarePenIcon aria-hidden />
            New chat
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="flex min-h-0 flex-1 flex-col gap-4">
        <Messages agent={agent} />
        {agent.error && <ChatError error={agent.error} />}
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
              aria-label="Message the assistant"
              placeholder="Ask the assistant…"
              value={draft}
              disabled={resuming}
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
              {turnOpen && (
                <InputGroupButton
                  variant="outline"
                  size="sm"
                  onClick={() => agent.cancel().catch(() => {})}
                >
                  <SquareIcon aria-hidden />
                  Stop
                </InputGroupButton>
              )}
              <InputGroupButton
                type="submit"
                variant="default"
                size="icon-sm"
                disabled={!draft.trim() || resuming}
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

type Agent = ReturnType<typeof useEveAgent>

function Messages({ agent }: { agent: Agent }) {
  const end = useRef<HTMLDivElement>(null)
  const last = agent.messages.at(-1)
  // Waiting for the first words of a reply
  const thinking =
    agent.status === "busy" &&
    !(last?.role === "assistant" && last.parts.length > 0)

  // Keep the newest text in view as it streams
  useEffect(() => {
    end.current?.scrollIntoView({ block: "nearest" })
  }, [agent.messages, thinking])

  if (agent.messages.length === 0) {
    return (
      <Empty className="flex-1">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <BotIcon />
          </EmptyMedia>
          <EmptyTitle>Start a conversation</EmptyTitle>
          <EmptyDescription>
            Ask the assistant anything to get started.
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }

  return (
    <div
      className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto"
      role="log"
      aria-live="polite"
      aria-busy={agent.status === "busy"}
    >
      {agent.messages.map((message) => (
        <Message key={message.id} message={message} agent={agent} />
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

function Message({ message, agent }: { message: EveMessage; agent: Agent }) {
  const fromUser = message.role === "user"
  return (
    <div
      className={cn(
        "flex max-w-[85%] flex-col gap-2",
        fromUser ? "items-end self-end" : "items-start self-start"
      )}
    >
      {message.parts.map((part, index) => (
        <MessagePart
          key={partKey(part, index)}
          part={part}
          fromUser={fromUser}
          agent={agent}
        />
      ))}
      {message.metadata?.status === "failed" && (
        <span className="text-xs text-destructive">Not sent</span>
      )}
    </div>
  )
}

function partKey(part: EveMessagePart, index: number) {
  if (part.type === "tool") return part.callId
  if (part.type === "interaction") return part.interactionId
  if (part.type === "text" || part.type === "reasoning") return part.id ?? index
  return index
}

function MessagePart({
  part,
  fromUser,
  agent,
}: {
  part: EveMessagePart
  fromUser: boolean
  agent: Agent
}) {
  switch (part.type) {
    case "text":
      return (
        <div
          className={cn(
            "rounded-lg px-3 py-2 text-sm break-words whitespace-pre-wrap",
            fromUser ? "bg-primary text-primary-foreground" : "bg-muted"
          )}
        >
          {part.text}
        </div>
      )
    case "reasoning":
      return (
        <details className="text-xs text-muted-foreground">
          <summary className="cursor-pointer select-none">Reasoning</summary>
          <p className="mt-1 whitespace-pre-wrap">{part.text}</p>
        </details>
      )
    case "file":
      return (
        <Badge variant="outline">
          <FileIcon aria-hidden />
          {part.filename ?? part.mediaType}
        </Badge>
      )
    case "tool":
      // Task controls are bookkeeping, not something to show people
      if (part.kind === "control") return null
      return (
        <div className="flex flex-col gap-2">
          <Badge variant="outline" className="gap-1.5">
            <WrenchIcon aria-hidden />
            {part.label}
            <span className="text-muted-foreground">
              {TOOL_STATE[part.state] ?? part.state}
            </span>
          </Badge>
          {part.interaction && (
            <InteractionPrompt interaction={part.interaction} agent={agent} />
          )}
        </div>
      )
    case "interaction":
      return <InteractionPrompt interaction={part} agent={agent} />
  }
}

const TOOL_STATE: Record<string, string> = {
  streaming: "preparing",
  requested: "queued",
  approval: "needs approval",
  running: "running",
  blocked: "waiting",
  completed: "done",
  failed: "failed",
  rejected: "rejected",
  cancelled: "cancelled",
}

/** An approval, question or sign-in the agent asked for. */
function InteractionPrompt({
  interaction,
  agent,
}: {
  interaction: Interaction
  agent: Agent
}) {
  const { request } = interaction
  if (interaction.status === "settled") {
    return (
      <p className="text-xs text-muted-foreground">
        {request.title ?? request.prompt}:{" "}
        {OUTCOME[interaction.outcome ?? ""] ?? interaction.outcome ?? "closed"}
      </p>
    )
  }

  const answered = interaction.answer !== undefined
  const respond = (optionId: string) =>
    agent
      .respond([{ interactionId: interaction.interactionId, optionId }])
      .catch(() => {})
  const signIn = request.signIn

  return (
    <Alert>
      <CircleAlertIcon />
      <AlertTitle>
        {request.title ??
          (request.kind === "approval" ? "Approval needed" : "Question")}
      </AlertTitle>
      <AlertDescription className="flex flex-col gap-2">
        <p>{signIn?.instructions ?? request.prompt}</p>
        {signIn?.userCode && <code>{signIn.userCode}</code>}
        {signIn?.url && (
          <a
            href={signIn.url}
            target="_blank"
            rel="noreferrer"
            className="underline underline-offset-4"
          >
            Sign in{signIn.displayName ? ` to ${signIn.displayName}` : ""}
          </a>
        )}
        {request.options && request.options.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {request.options.map((option) => (
              <Button
                key={option.id}
                size="sm"
                variant="outline"
                disabled={answered || agent.status === "resuming"}
                onClick={() => respond(option.id)}
              >
                {option.label}
              </Button>
            ))}
          </div>
        )}
      </AlertDescription>
    </Alert>
  )
}

// Only accepted and declined mean a person decided; show anything else as eve named it
const OUTCOME: Record<string, string> = {
  accepted: "approved",
  declined: "declined",
}

function ChatError({ error }: { error: Error }) {
  const navigate = useNavigate()
  const expired = ClientError.is(error) && error.status === 401

  return (
    <Alert variant="destructive">
      <CircleAlertIcon />
      <AlertTitle>
        {expired
          ? "Your session has expired"
          : "The assistant ran into a problem"}
      </AlertTitle>
      <AlertDescription className="flex flex-col items-start gap-2">
        <p>
          {expired
            ? "Sign in again to keep chatting."
            : error.message || "Something went wrong. Try again."}
        </p>
        {expired && (
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              logout()
              navigate("/login", { replace: true })
            }}
          >
            Sign in again
          </Button>
        )}
      </AlertDescription>
    </Alert>
  )
}
