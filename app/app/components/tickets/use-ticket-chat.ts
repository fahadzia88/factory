import { useCallback, useEffect, useRef, useState } from "react"

import type { FeatureChatTool } from "~/lib/features"
import {
  cancelTicketChat,
  listTicketMessages,
  sendTicketMessage,
  ticketError,
  type TicketError,
} from "~/lib/tickets"

export type TicketChatMessage = {
  key: string
  role: "user" | "assistant"
  text: string
  tools: FeatureChatTool[]
  failed?: boolean
}

export type TicketChatHistory =
  | { status: "loading" }
  | { status: "loaded"; messages: TicketChatMessage[] }
  | { status: "error"; error: TicketError }

export type TicketChatTurn = { abort: AbortController; thinking: boolean }

/** A ticket's one conversation with the agent: stored history, then turns streamed one at a time. */
export function useTicketChat(
  projectId: number,
  ticketId: number,
  onTurnEnd: () => void
) {
  const [history, setHistory] = useState<TicketChatHistory>({
    status: "loading",
  })
  const [turn, setTurn] = useState<TicketChatTurn | null>(null)
  const [error, setError] = useState<TicketError | null>(null)
  const request = useRef(0)
  const turnRef = useRef<TicketChatTurn | null>(null)
  const nextKey = useRef(0)
  const onTurnEndRef = useRef(onTurnEnd)
  onTurnEndRef.current = onTurnEnd

  const reloadHistory = useCallback(() => {
    const id = ++request.current
    setHistory({ status: "loading" })
    listTicketMessages(projectId, ticketId).then(
      (items) => {
        if (id !== request.current) return
        setHistory({
          status: "loaded",
          messages: items.map((item) => ({
            key: `stored-${item.id}`,
            role: item.role,
            text: item.text,
            tools: [],
          })),
        })
      },
      (reason) => {
        if (id !== request.current) return
        setHistory({ status: "error", error: ticketError(reason) })
      }
    )
  }, [projectId, ticketId])

  useEffect(() => {
    reloadHistory()
    return () => {
      // Closing the connection makes the API cancel the turn on the agent
      turnRef.current?.abort.abort()
    }
  }, [reloadHistory])

  const edit = (change: (messages: TicketChatMessage[]) => TicketChatMessage[]) =>
    setHistory((h) =>
      h.status === "loaded" ? { ...h, messages: change(h.messages) } : h
    )

  const update = (
    key: string,
    change: (message: TicketChatMessage) => TicketChatMessage
  ) =>
    edit((messages) => messages.map((m) => (m.key === key ? change(m) : m)))

  /** Sends a message; resolves with the text to put back in the draft when it wasn't sent. */
  async function send(raw: string): Promise<string | null> {
    const text = raw.trim()
    if (!text || turnRef.current || history.status !== "loaded") return null
    const userKey = `local-${++nextKey.current}`
    const replyKey = `local-${++nextKey.current}`
    const current: TicketChatTurn = {
      abort: new AbortController(),
      thinking: true,
    }
    turnRef.current = current
    setTurn(current)
    setError(null)
    edit((messages) => [
      ...messages,
      { key: userKey, role: "user", text, tools: [] },
      { key: replyKey, role: "assistant", text: "", tools: [] },
    ])
    const settle = (thinking: boolean) => {
      if (turnRef.current !== current) return
      turnRef.current = { ...current, thinking }
      setTurn(turnRef.current)
    }

    let failed = false
    try {
      const final = await sendTicketMessage(projectId, ticketId, text, {
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
        failed = true
        setError(ticketError(reason))
        update(userKey, (m) => ({ ...m, failed: true }))
        edit((messages) => messages.filter((m) => m.key !== replyKey))
      }
    } finally {
      // An empty reply bubble (stopped before any text) isn't worth keeping
      edit((messages) =>
        messages.filter(
          (m) => m.key !== replyKey || m.text !== "" || m.tools.length > 0
        )
      )
      if (turnRef.current?.abort === current.abort) {
        turnRef.current = null
        setTurn(null)
      }
      onTurnEndRef.current()
    }
    return failed ? text : null
  }

  function stop() {
    // The API's stream ends with `cancelled`, so the fetch isn't aborted here
    cancelTicketChat(projectId, ticketId).catch(() => {})
  }

  return { history, turn, error, send, stop, reloadHistory }
}
