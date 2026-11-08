import { useCallback, useEffect, useRef, useState } from "react"

import {
  cancelFeatureChat,
  featureError,
  listFeatureMessages,
  sendFeatureMessage,
  type FeatureChatTool,
  type FeatureError,
} from "~/lib/features"

export type ChatMessage = {
  key: string
  role: "user" | "assistant"
  text: string
  tools: FeatureChatTool[]
  failed?: boolean
}

export type ChatHistory =
  | { status: "loading" }
  | { status: "loaded"; messages: ChatMessage[] }
  | { status: "error"; error: FeatureError }

export type ChatTurn = { abort: AbortController; thinking: boolean }

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
      (messages) => {
        if (id !== request.current) return
        setHistory({
          status: "loaded",
          messages: messages.map((m) => ({
            key: `stored-${m.id}`,
            role: m.role,
            text: m.text,
            tools: [],
          })),
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

  const update = (key: string, change: (m: ChatMessage) => ChatMessage) =>
    setHistory((h) =>
      h.status === "loaded"
        ? {
            ...h,
            messages: h.messages.map((m) => (m.key === key ? change(m) : m)),
          }
        : h
    )

  /** Sends `raw`; resolves with text to put back in the draft when it wasn't sent. */
  async function send(raw: string): Promise<string | null> {
    const text = raw.trim()
    if (!text || turnRef.current || history.status !== "loaded") return null
    const userKey = `local-${++nextKey.current}`
    const replyKey = `local-${++nextKey.current}`
    const current: ChatTurn = { abort: new AbortController(), thinking: true }
    turnRef.current = current
    setTurn(current)
    setError(null)
    setHistory((h) =>
      h.status === "loaded"
        ? {
            ...h,
            messages: [
              ...h.messages,
              { key: userKey, role: "user", text, tools: [] },
              { key: replyKey, role: "assistant", text: "", tools: [] },
            ],
          }
        : h
    )
    const settle = (thinking: boolean) => {
      if (turnRef.current !== current) return
      turnRef.current = { ...current, thinking }
      setTurn(turnRef.current)
    }

    let restore: string | null = null
    try {
      const final = await sendFeatureMessage(projectId, featureId, text, {
        signal: current.abort.signal,
        onEvent: (event) => {
          if (event.type === "text") {
            settle(false)
            update(replyKey, (m) => ({ ...m, text: m.text + event.delta }))
          } else if (event.type === "tool") {
            settle(false)
            const tool = {
              id: event.id,
              name: event.name,
              status: event.status,
            }
            update(replyKey, (m) => ({
              ...m,
              tools: m.tools.some((t) => t.id === tool.id)
                ? m.tools.map((t) => (t.id === tool.id ? tool : t))
                : [...m.tools, tool],
            }))
          } else if (event.type === "done") {
            update(replyKey, (m) => ({ ...m, text: event.text }))
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
        setError(featureError(reason))
        restore = text
        update(userKey, (m) => ({ ...m, failed: true }))
        setHistory((h) =>
          h.status === "loaded"
            ? { ...h, messages: h.messages.filter((m) => m.key !== replyKey) }
            : h
        )
      }
    } finally {
      // An empty reply bubble (stopped before any text) isn't worth keeping
      setHistory((h) =>
        h.status === "loaded"
          ? {
              ...h,
              messages: h.messages.filter(
                (m) => m.key !== replyKey || m.text !== "" || m.tools.length > 0
              ),
            }
          : h
      )
      if (turnRef.current?.abort === current.abort) {
        turnRef.current = null
        setTurn(null)
      }
      onTurnEndRef.current()
    }
    return restore
  }

  function stop() {
    // The API's stream ends with `cancelled`, so the fetch isn't aborted here
    cancelFeatureChat(projectId, featureId).catch(() => {})
  }

  return { history, turn, error, send, stop, reloadHistory }
}
