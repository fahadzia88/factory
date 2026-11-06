import { useCallback, useEffect, useRef, useState } from "react"

import {
  checkoutError,
  getCheckout,
  type Checkout,
  type CheckoutError,
} from "~/lib/checkouts"

export type CheckoutView =
  | { status: "loading" }
  | { status: "archived" }
  | { status: "none" }
  | { status: "loaded"; checkout: Checkout }
  | { status: "error"; error: CheckoutError }

const POLL_MS = 3000

export function useCheckout(projectId: number, archived: boolean) {
  const [state, setState] = useState<CheckoutView>({ status: "loading" })
  const requestId = useRef(0)
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const reload = useCallback(async () => {
    const id = ++requestId.current
    const current = () => mounted.current && id === requestId.current
    try {
      const checkout = await getCheckout(projectId)
      if (current()) setState({ status: "loaded", checkout })
    } catch (e) {
      if (!current()) return
      const error = checkoutError(e)
      setState(
        error.code === "checkout_not_found"
          ? { status: "none" }
          : { status: "error", error }
      )
    }
  }, [projectId])

  const setCheckout = useCallback((checkout: Checkout) => {
    // a POST answer supersedes any GET still in flight
    requestId.current++
    setState({ status: "loaded", checkout })
  }, [])

  useEffect(() => {
    if (archived) {
      // archiving deleted the checkout, so there's nothing to load
      requestId.current++
      setState({ status: "archived" })
      return
    }
    setState({ status: "loading" })
    void reload()
  }, [archived, reload])

  useEffect(() => {
    if (state.status !== "loaded" || state.checkout.state !== "cloning") return
    const timer = setTimeout(() => void reload(), POLL_MS)
    return () => clearTimeout(timer)
  }, [state, reload])

  return { state, reload, setCheckout }
}
