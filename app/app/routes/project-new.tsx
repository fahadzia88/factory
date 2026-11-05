import { redirect, useNavigation } from "react-router"
import { toast } from "sonner"

import { PageHeader } from "~/components/page-header"
import {
  formResult,
  ProjectForm,
  readProjectForm,
} from "~/components/projects/project-form"
import { ProjectBreadcrumb } from "~/components/projects/project-page"
import { apiErrorMessage } from "~/lib/api"
import { createProject, projectFieldErrors } from "~/lib/projects"
import type { Route } from "./+types/project-new"

export function meta() {
  return [{ title: "New project" }]
}

export async function clientAction({ request }: Route.ClientActionArgs) {
  const input = readProjectForm(await request.formData())
  try {
    const project = await createProject(input)
    toast.success("Project created")
    return redirect(`/projects/${project.id}`)
  } catch (error) {
    return formResult(projectFieldErrors(error), apiErrorMessage(error), input)
  }
}

export default function NewProject({ actionData }: Route.ComponentProps) {
  const navigation = useNavigation()
  return (
    <>
      <ProjectBreadcrumb name="New project" />
      <PageHeader title="New project" />
      <ProjectForm
        defaultValues={{ status: "planning" }}
        result={actionData}
        submitting={navigation.state === "submitting"}
        submitLabel="Create project"
        cancelTo="/projects"
        autoFocus
      />
    </>
  )
}
