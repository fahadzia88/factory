import { projectId } from "~/lib/projects"
import {
  listPullRequests,
  positiveInt,
  pullRequestError,
} from "~/lib/pull-requests"
import type { Route } from "./+types/project-pulls"

// A component-less route the project page's "Pull requests" card loads with a fetcher, so
// paging through pull requests doesn't rerun the project page's own loader.
export async function clientLoader({
  params,
  request,
}: Route.ClientLoaderArgs) {
  const id = projectId(params.id)
  if (id === null) {
    return { page: null, error: pullRequestError(new Error("Invalid project")) }
  }
  const page = positiveInt(new URL(request.url).searchParams.get("page")) ?? 1
  try {
    return { page: await listPullRequests(id, page), error: null }
  } catch (error) {
    return { page: null, error: pullRequestError(error) }
  }
}
