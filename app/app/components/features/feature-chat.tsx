import {
  ArrowUpIcon,
  BotIcon,
  CircleAlertIcon,
  SquareIcon,
  WrenchIcon,
} from "lucide-react"
import { useEffect, useRef, useState } from "react"
import { useNavigate } from "react-router"

import { QuestionCards } from "~/components/features/question-cards"
import {
  useFeatureChat,
  type ChatTextMessage,
  type ChatHistory,
} from "~/components/features/use-feature-chat"
import { StatusBadge } from "~/components/status-badge"
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
import type { FeatureError, QuestionAnswer, QuestionSet } from "~/lib/features"
import { cn } from "~/lib/utils"

const TOOL_STATE = { running: "running", completed: "done", failed: "failed" }

/** The feature's one conversation with the agent; there's no "New chat". */
export function FeatureChat({
  projectId,
  featureId,
  onTurnEnd,
}: {
  projectId: number
  featureId: number
  onTurnEnd: () => void
}) {
  const chat = useFeatureChat(projectId, featureId, onTurnEnd)
  const [draft, setDraft] = useState("")
  const loaded = chat.history.status === "loaded"
  const pending =
    chat.history.status === "loaded" &&
    chat.history.messages.some(
      (m) => m.kind === "questions" && m.set.status === "pending"
    )

  function submit() {
    if (!draft.trim() || !loaded || chat.turn) return
    const text = draft
    setDraft("")
    chat.send(text).then((restore) => {
      if (restore !== null) setDraft((current) => current || restore)
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <BotIcon className="size-4 text-muted-foreground" aria-hidden />
          Agent
          {chat.turn ? (
            <StatusBadge tone="warning">Working</StatusBadge>
          ) : (
            <StatusBadge tone="positive">Ready</StatusBadge>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex min-h-0 flex-col gap-4">
        <Messages
          history={chat.history}
          thinking={chat.turn?.thinking ?? false}
          busy={chat.turn !== null}
          onRetry={chat.reloadHistory}
          onAnswer={(set, answers) => chat.answer(set, answers)}
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
                pending
                  ? "Answer the cards or type a reply…"
                  : "Message the agent…"
              }
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
                disabled={!draft.trim() || !loaded || chat.turn !== null}
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
  onRetry,
  onAnswer,
}: {
  history: ChatHistory
  thinking: boolean
  busy: boolean
  onRetry: () => void
  onAnswer: (set: QuestionSet, answers: QuestionAnswer[]) => void
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
          <EmptyTitle>Start working on this feature</EmptyTitle>
          <EmptyDescription>
            Ask the agent to start the next step, for example "Start the
            requirements".
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }

  return (
    <div
      className="flex max-h-[32rem] min-h-48 flex-col gap-4 overflow-y-auto"
      role="log"
      aria-live="polite"
      aria-busy={busy}
    >
      {history.messages.map((message) =>
        message.kind === "questions" ? (
          <QuestionCards
            key={message.key}
            set={message.set}
            disabled={busy}
            onSubmit={(answers) => onAnswer(message.set, answers)}
          />
        ) : (
          <Message key={message.key} message={message} />
        )
      )}
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

function Message({ message }: { message: ChatTextMessage }) {
  const fromUser = message.role === "user"
  return (
    <div
      className={cn(
        "flex max-w-[85%] flex-col gap-2",
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
      {message.text && (
        <div
          className={cn(
            "rounded-lg px-3 py-2 text-sm break-words whitespace-pre-wrap",
            fromUser ? "bg-primary text-primary-foreground" : "bg-muted"
          )}
        >
          {message.text}
        </div>
      )}
      {message.failed && (
        <span className="text-xs text-destructive">Not sent</span>
      )}
    </div>
  )
}

function ChatError({ error }: { error: FeatureError }) {
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
