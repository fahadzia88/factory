import { api, ApiError, problemDetail } from "~/lib/api"

export type FeatureFileEntry =
  | {
      name: string
      path: string
      type: "directory"
      children: FeatureFileEntry[]
    }
  | { name: string; path: string; type: "file"; markdown: boolean }

export type FeatureFileTree = {
  /** the feature folder, relative to the worktree */
  root: string
  /** [] when the folder is empty or doesn't exist yet */
  entries: FeatureFileEntry[]
}

export type FeatureFile = {
  path: string
  content: string
  /** bytes */
  size: number
  updatedAt: string
}

export type FeatureFilesErrorCode =
  | "worktree_missing"
  | "invalid_path"
  | "file_not_found"
  | "unsupported_file_type"
  | "file_too_large"
  | "project_not_found"
  | "feature_not_found"
  | "invalid_request"

const filesPath = (projectId: number, featureId: number) =>
  `/api/projects/${projectId}/features/${featureId}/files`

export function getFeatureFiles(projectId: number, featureId: number) {
  return api.get<FeatureFileTree>(filesPath(projectId, featureId))
}

export function getFeatureFile(
  projectId: number,
  featureId: number,
  path: string
) {
  return api.get<FeatureFile>(
    `${filesPath(projectId, featureId)}/content?path=${encodeURIComponent(path)}`
  )
}

/** The problem's `code` (a FeatureFilesErrorCode, or another server code), or null. */
export function featureErrorCode(
  error: unknown
): FeatureFilesErrorCode | string | null {
  if (!(error instanceof ApiError)) return null
  const code = (problemDetail(error) as { code?: unknown }).code
  return typeof code === "string" ? code : null
}
