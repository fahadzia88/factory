import { checkoutError, getCheckout } from "~/lib/checkouts"
import { featureError, listFeatures } from "~/lib/features"
import { projectId } from "~/lib/projects"
import type { Route } from "./+types/project-features"

// A component-less route the project page's "Features" card loads with a fetcher, so the card
// reloads on its own without rerunning the project page's loader.
export async function clientLoader({ params }: Route.ClientLoaderArgs) {
  const id = projectId(params.id)
  if (id === null) {
    return {
      features: null,
      canCreate: false,
      blocked: null,
      error: featureError(new Error("Invalid project")),
    }
  }
  const [features, checkout] = await Promise.allSettled([
    listFeatures(id),
    getCheckout(id),
  ])
  if (features.status === "rejected") {
    return {
      features: null,
      canCreate: false,
      blocked: null,
      error: featureError(features.reason),
    }
  }
  const canCreate =
    checkout.status === "fulfilled" && checkout.value.state === "ready"
  let blocked: string | null = null
  if (checkout.status === "rejected") {
    blocked =
      checkoutError(checkout.reason).code === "checkout_not_found"
        ? "Check out the repository below to start a feature."
        : "The repository checkout isn't ready."
  } else if (checkout.value.state === "cloning") {
    blocked = "The repository is still being cloned."
  } else if (!canCreate) {
    blocked = "The repository checkout isn't ready."
  }
  return { features: features.value, canCreate, blocked, error: null }
}
