import { Link, redirect, useNavigation, useRevalidator } from "react-router"
import { toast } from "sonner"

import { PageHeader } from "~/components/page-header"
import {
  formResult,
  ProjectForm,
  readProjectForm,
} from "~/components/projects/project-form"
import {
  ProjectBreadcrumb,
  ProjectNotFound,
} from "~/components/projects/project-page"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Spinner } from "~/components/ui/spinner"
import { ApiError, apiErrorMessage } from "~/lib/api"
import {
  getProject,
  projectFieldErrors,
  projectId,
  updateProject,
} from "~/lib/projects"
import type { Route } from "./+types/project-edit"

export function meta({ loaderData }: Route.MetaArgs) {
  const name = loaderData?.project?.name
  return [{ title: name ? `Edit ${name}` : "Edit project" }]
}

export async function clientLoader({ params }: Route.ClientLoaderArgs) {
  const id = projectId(params.id)
  if (id === null) return { project: null, notFound: true, error: null }
  try {
    return { project: await getProject(id), notFound: false, error: null }
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return { project: null, notFound: true, error: null }
    }
    return { project: null, notFound: false, error: apiErrorMessage(error) }
  }
}
clientLoader.hydrate = true as const

export async function clientAction({
  request,
  params,
}: Route.ClientActionArgs) {
  const id = projectId(params.id)
  const input = readProjectForm(await request.formData())
  if (id === null) return formResult({}, "Project not found.", input)
  try {
    await updateProject(id, input)
    toast.success("Changes saved")
    return redirect(`/projects/${id}`)
  } catch (error) {
    return formResult(projectFieldErrors(error), apiErrorMessage(error), input)
  }
}

export function HydrateFallback() {
  return (
    <div className="flex flex-1 items-center justify-center py-16">
      <Spinner className="size-6 text-muted-foreground" />
    </div>
  )
}

export default function EditProject({
  loaderData,
  actionData,
}: Route.ComponentProps) {
  const navigation = useNavigation()
  const revalidator = useRevalidator()
  const { project, notFound, error } = loaderData

  if (notFound) return <ProjectNotFound />
  if (error || !project) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn't load the project</AlertTitle>
        <AlertDescription>
          <p>{error}</p>
          <Button
            variant="outline"
            size="sm"
            className="mt-2"
            disabled={revalidator.state === "loading"}
            onClick={() => revalidator.revalidate()}
          >
            Try again
          </Button>
        </AlertDescription>
      </Alert>
    )
  }

  return (
    <>
      <ProjectBreadcrumb name={project.name} page={`Edit ${project.name}`} />
      <PageHeader title={`Edit ${project.name}`} />
      {project.archived ? (
        <Alert>
          <AlertTitle>
            This project is archived. Restore it to make changes.
          </AlertTitle>
          <AlertDescription>
            <Link to={`/projects/${project.id}`}>Back to the project</Link>
          </AlertDescription>
        </Alert>
      ) : (
        <ProjectForm
          defaultValues={{
            name: project.name,
            repositoryUrl: project.repositoryUrl,
            description: project.description ?? "",
            status: project.status,
          }}
          result={actionData}
          submitting={navigation.state === "submitting"}
          submitLabel="Save changes"
          cancelTo={`/projects/${project.id}`}
        />
      )}
    </>
  )
}
