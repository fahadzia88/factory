import {
  Form,
  Link,
  redirect,
  useNavigation,
  useRevalidator,
} from "react-router"
import { toast } from "sonner"

import { PageHeader } from "~/components/page-header"
import {
  ProjectBreadcrumb,
  ProjectNotFound,
} from "~/components/projects/project-page"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import { Card, CardContent } from "~/components/ui/card"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "~/components/ui/field"
import { Input } from "~/components/ui/input"
import { Spinner } from "~/components/ui/spinner"
import { ApiError, apiErrorMessage } from "~/lib/api"
import { createFeature, featureError } from "~/lib/features"
import { getProject, projectId } from "~/lib/projects"
import type { Route } from "./+types/feature-new"

export function meta() {
  return [{ title: "New feature" }]
}

type LoaderData =
  | { notFound: true }
  | { notFound: false; error: string }
  | {
      notFound: false
      error: null
      project: { id: number; name: string; archived: boolean }
    }

export async function clientLoader({
  params,
}: Route.ClientLoaderArgs): Promise<LoaderData> {
  const id = projectId(params.id)
  if (id === null) return { notFound: true }
  try {
    const project = await getProject(id)
    return {
      notFound: false,
      error: null,
      project: {
        id: project.id,
        name: project.name,
        archived: project.archived,
      },
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return { notFound: true }
    }
    return { notFound: false, error: apiErrorMessage(error) }
  }
}
clientLoader.hydrate = true as const

export function HydrateFallback() {
  return (
    <div className="flex flex-1 items-center justify-center py-16">
      <Spinner className="size-6 text-muted-foreground" />
    </div>
  )
}

type ActionData = {
  values: { name: string }
  errors?: { name?: string }
  formError?: string
  retryable?: boolean
  code?: string | null
}

export async function clientAction({
  request,
  params,
}: Route.ClientActionArgs): Promise<ActionData | Response> {
  const id = Number(params.id)
  const name = String((await request.formData()).get("name") ?? "")
  if (!name.trim()) {
    return { values: { name }, errors: { name: "Enter a name." } }
  }
  try {
    const feature = await createFeature(id, name)
    toast.success("Feature created")
    return redirect(`/projects/${id}/features/${feature.id}`)
  } catch (error) {
    const problem = featureError(error)
    if (problem.code === "invalid_name") {
      return { values: { name }, errors: { name: problem.message } }
    }
    return {
      values: { name },
      formError: problem.message,
      retryable: problem.retryable,
      code: problem.code,
    }
  }
}

export default function NewFeature({
  loaderData,
  actionData,
}: Route.ComponentProps) {
  const navigation = useNavigation()
  const revalidator = useRevalidator()
  const submitting = navigation.state === "submitting"

  if (loaderData.notFound) return <ProjectNotFound />
  if (loaderData.error !== null) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn't load the project</AlertTitle>
        <AlertDescription>
          <p>{loaderData.error}</p>
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

  const { project } = loaderData
  const backToProject = (
    <Button
      variant="outline"
      size="sm"
      className="mt-2"
      render={<Link to={`/projects/${project.id}`} />}
      nativeButton={false}
    >
      Back to project
    </Button>
  )

  if (project.archived) {
    return (
      <>
        <ProjectBreadcrumb project={project} name="New feature" />
        <PageHeader title="New feature" />
        <Alert>
          <AlertTitle>Archived projects can't start features.</AlertTitle>
          <AlertDescription>{backToProject}</AlertDescription>
        </Alert>
      </>
    )
  }

  const result = actionData as ActionData | undefined
  const nameError = result?.errors?.name
  const blockedCode =
    result?.code === "checkout_not_ready" || result?.code === "project_archived"

  return (
    <>
      <ProjectBreadcrumb project={project} name="New feature" />
      <PageHeader title="New feature" />
      <Card>
        <CardContent>
          <Form method="post" className="flex flex-col gap-6">
            {result?.formError && (
              <Alert variant="destructive">
                <AlertTitle>Couldn't create the feature</AlertTitle>
                <AlertDescription>
                  <p className="break-words">
                    {result.formError}
                    {result.retryable ? " Try again." : ""}
                  </p>
                  {blockedCode && backToProject}
                </AlertDescription>
              </Alert>
            )}
            <FieldGroup>
              <Field data-invalid={nameError ? true : undefined}>
                <FieldLabel htmlFor="name">Name</FieldLabel>
                <Input
                  id="name"
                  name="name"
                  defaultValue={result?.values.name}
                  maxLength={100}
                  required
                  autoFocus
                  autoComplete="off"
                  aria-invalid={nameError ? true : undefined}
                  aria-describedby={nameError ? "name-error" : undefined}
                />
                <FieldDescription>
                  Used for the folder and branch, for example
                  feature/005-feature-records.
                </FieldDescription>
                <FieldError id="name-error">{nameError}</FieldError>
              </Field>
            </FieldGroup>
            <div className="flex flex-wrap gap-2">
              <Button type="submit" disabled={submitting}>
                {submitting && <Spinner aria-hidden />}
                Create feature
              </Button>
              <Button
                variant="outline"
                render={<Link to={`/projects/${project.id}`} />}
                nativeButton={false}
              >
                Cancel
              </Button>
            </div>
            {submitting && (
              <p className="text-sm text-muted-foreground">
                Creating the branch and pushing it to GitHub…
              </p>
            )}
          </Form>
        </CardContent>
      </Card>
    </>
  )
}
