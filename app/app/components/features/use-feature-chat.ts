import { useCallback, useEffect, useRef, useState } from "react"

import {
  cancelFeatureChat,
  featureError,
  formatAnswers,
  listFeatureMessages,
  sendFeatureMessage,
  type FeatureChatTool,
  type FeatureError,
  type QuestionAnswer,
  type QuestionSet,
} from "~/lib/features"

export type ChatTextMessage = {
  kind: "message"
  key: string
  role: "user" | "assistant"
  text: string
  tools: FeatureChatTool[]
  failed?: boolean
}

export type ChatQuestions = { kind: "questions"; key: string; set: QuestionSet }

export type ChatMessage = ChatTextMessage | ChatQuestions

export type ChatHistory =
  | { status: "loading" }
  | { status: "loaded"; messages: ChatMessage[] }
  | { status: "error"; error: FeatureError }

export type ChatTurn = { abort: AbortController; thinking: boolean }

type Input = string | { questionsId: number; answers: QuestionAnswer[] }

/** A feature's one conversation with the agent: stored history, then turns streamed one at a time. */
export function useFeatureChat(
  projectId: number,
  featureId: number,
  onTurnEnd: () => void
) {
  const [history, setHistory] = useState<ChatHistory>({ status: "loading" })
  const [turn, setTurn] = useState<ChatTurn | null>(null)
  const [error, setError] = useState<FeatureError | null>(null)
  const request = useRef(0)
  const turnRef = useRef<ChatTurn | null>(null)
  const nextKey = useRef(0)
  const onTurnEndRef = useRef(onTurnEnd)
  onTurnEndRef.current = onTurnEnd

  const reloadHistory = useCallback(() => {
    const id = ++request.current
    setHistory({ status: "loading" })
    listFeatureMessages(projectId, featureId).then(
      (items) => {
        if (id !== request.current) return
        setHistory({
          status: "loaded",
          messages: items.map((item): ChatMessage =>
            item.kind === "message"
              ? {
                  kind: "message",
                  key: `stored-${item.id}`,
                  role: item.role,
                  text: item.text,
                  tools: [],
                }
              : { kind: "questions", key: `questions-${item.id}`, set: item }
          ),
        })
      },
      (reason) => {
        if (id !== request.current) return
        setHistory({ status: "error", error: featureError(reason) })
      }
    )
  }, [projectId, featureId])

  useEffect(() => {
    reloadHistory()
    return () => {
      // Closing the connection makes the API cancel the turn on the agent
      turnRef.current?.abort.abort()
    }
  }, [reloadHistory])

  const edit = (change: (messages: ChatMessage[]) => ChatMessage[]) =>
    setHistory((h) =>
      h.status === "loaded" ? { ...h, messages: change(h.messages) } : h
    )

  const updateText = (
    key: string,
    change: (m: ChatTextMessage) => ChatTextMessage
  ) =>
    edit((messages) =>
      messages.map((m) =>
        m.key === key && m.kind === "message" ? change(m) : m
      )
    )

  /** Marks pending question sets answered; returns the keys so a failed send can restore them. */
  const answerPending = (
    only: number | null,
    answers: QuestionAnswer[] | null
  ): string[] => {
    if (history.status !== "loaded") return []
    const keys = history.messages
      .filter(
        (m): m is ChatQuestions =>
          m.kind === "questions" &&
          m.set.status === "pending" &&
          (only === null || m.set.id === only)
      )
      .map((m) => m.key)
    edit((messages) =>
      messages.map((m) =>
        m.kind === "questions" && keys.includes(m.key)
          ? { ...m, set: { ...m.set, status: "answered", answers } }
          : m
      )
    )
    return keys
  }

  const restorePending = (keys: string[]) =>
    edit((messages) =>
      messages.map((m) =>
        m.kind === "questions" && keys.includes(m.key)
          ? { ...m, set: { ...m.set, status: "pending", answers: null } }
          : m
      )
    )

  /** Runs one turn; resolves with text to put back in the draft when it wasn't sent. */
  async function run(input: Input, echo: string, answered: string[]) {
    const userKey = `local-${++nextKey.current}`
    const replyKey = `local-${++nextKey.current}`
    const current: ChatTurn = { abort: new AbortController(), thinking: true }
    turnRef.current = current
    setTurn(current)
    setError(null)
    edit((messages) => [
      ...messages,
      { kind: "message", key: userKey, role: "user", text: echo, tools: [] },
      {
        kind: "message",
        key: replyKey,
        role: "assistant",
        text: "",
        tools: [],
      },
    ])
    const settle = (thinking: boolean) => {
      if (turnRef.current !== current) return
      turnRef.current = { ...current, thinking }
      setTurn(turnRef.current)
    }

    let failed: FeatureError | null = null
    try {
      const final = await sendFeatureMessage(projectId, featureId, input, {
        signal: current.abort.signal,
        onEvent: (event) => {
          if (event.type === "text") {
            settle(false)
            updateText(replyKey, (m) => ({ ...m, text: m.text + event.delta }))
          } else if (event.type === "tool") {
            settle(false)
            const tool = {
              id: event.id,
              name: event.name,
              status: event.status,
            }
            updateText(replyKey, (m) => ({
              ...m,
              tools: m.tools.some((t) => t.id === tool.id)
                ? m.tools.map((t) => (t.id === tool.id ? tool : t))
                : [...m.tools, tool],
            }))
          } else if (event.type === "questions") {
            const set: QuestionSet = {
              id: event.id,
              status: "pending",
              questions: event.questions,
              answers: null,
              answerMessageId: null,
              createdAt: new Date().toISOString(),
            }
            edit((messages) => [
              ...messages,
              { kind: "questions", key: `questions-${event.id}`, set },
            ])
          } else if (event.type === "done") {
            updateText(replyKey, (m) => ({ ...m, text: event.text }))
          }
        },
      })
      if (final.type === "error") {
        setError({
          status: null,
          code: null,
          message: final.message,
          retryable: true,
        })
      }
    } catch (reason) {
      if (!current.abort.signal.aborted) {
        failed = featureError(reason)
        setError(failed)
        updateText(userKey, (m) => ({ ...m, failed: true }))
        edit((messages) => messages.filter((m) => m.key !== replyKey))
        restorePending(answered)
      }
    } finally {
      // An empty reply bubble (stopped before any text) isn't worth keeping
      edit((messages) =>
        messages.filter(
          (m) =>
            m.key !== replyKey ||
            (m.kind === "message" && (m.text !== "" || m.tools.length > 0))
        )
      )
      if (turnRef.current?.abort === current.abort) {
        turnRef.current = null
        setTurn(null)
      }
      onTurnEndRef.current()
    }
    return failed
  }

  /** Sends a typed message; resolves with text to put back in the draft when it wasn't sent. */
  async function send(raw: string): Promise<string | null> {
    const text = raw.trim()
    if (!text || turnRef.current || history.status !== "loaded") return null
    // A typed reply answers whatever questions are pending, as the API records it
    const answered = answerPending(null, null)
    const failed = await run(text, text, answered)
    return failed ? text : null
  }

  /** Sends answers to the pending set `questionsId`. */
  async function answer(set: QuestionSet, answers: QuestionAnswer[]) {
    if (turnRef.current || history.status !== "loaded") return
    const answered = answerPending(set.id, answers)
    const failed = await run(
      { questionsId: set.id, answers },
      formatAnswers(set.questions, answers),
      answered
    )
    // The set changed on the server (answered elsewhere, or replaced): show what it holds now
    if (
      failed?.code === "questions_not_pending" ||
      failed?.code === "invalid_answers"
    ) {
      reloadHistory()
    }
  }

  function stop() {
    // The API's stream ends with `cancelled`, so the fetch isn't aborted here
    cancelFeatureChat(projectId, featureId).catch(() => {})
  }

  return { history, turn, error, send, answer, stop, reloadHistory }
}
