import { Form, Link } from "react-router"

import { Alert, AlertDescription } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "~/components/ui/field"
import { Input } from "~/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "~/components/ui/select"
import { Spinner } from "~/components/ui/spinner"
import { Textarea } from "~/components/ui/textarea"
import {
  isProjectStatus,
  PROJECT_STATUSES,
  STATUS_LABEL,
  type ProjectInput,
} from "~/lib/projects"

export type ProjectFormValues = Partial<ProjectInput>

/** What a create or edit action returns when the save is rejected. */
export type ProjectFormResult = {
  errors: Record<string, string>
  formError?: string
  values: ProjectFormValues
}

/** Reads the form's fields; the API validates them. */
export function readProjectForm(formData: FormData): ProjectInput {
  const text = (name: string) => String(formData.get(name) ?? "")
  const status = text("status")
  return {
    name: text("name"),
    repositoryUrl: text("repositoryUrl"),
    description: text("description"),
    status: isProjectStatus(status) ? status : "planning",
  }
}

const STATUS_ITEMS = PROJECT_STATUSES.map((status) => ({
  value: status,
  label: STATUS_LABEL[status],
}))

export function ProjectForm({
  defaultValues,
  result,
  submitting,
  submitLabel,
  cancelTo,
  autoFocus = false,
}: {
  defaultValues: ProjectFormValues
  result?: ProjectFormResult
  submitting: boolean
  submitLabel: string
  cancelTo: string
  autoFocus?: boolean
}) {
  const values = result?.values ?? defaultValues
  const errors = result?.errors ?? {}

  function fieldProps(name: keyof ProjectInput) {
    return {
      id: name,
      name,
      "aria-invalid": errors[name] ? true : undefined,
      "aria-describedby": errors[name] ? `${name}-error` : undefined,
    }
  }

  function error(name: keyof ProjectInput) {
    return errors[name] ? (
      <FieldError id={`${name}-error`}>{errors[name]}</FieldError>
    ) : null
  }

  return (
    <Form method="post" className="flex max-w-2xl flex-col gap-6">
      {result?.formError && (
        <Alert variant="destructive">
          <AlertDescription>{result.formError}</AlertDescription>
        </Alert>
      )}
      <FieldGroup>
        <Field data-invalid={errors.name ? true : undefined}>
          <FieldLabel htmlFor="name">Name</FieldLabel>
          <Input
            {...fieldProps("name")}
            defaultValue={values.name}
            maxLength={100}
            required
            autoFocus={autoFocus}
            autoComplete="off"
          />
          {error("name")}
        </Field>
        <Field data-invalid={errors.repositoryUrl ? true : undefined}>
          <FieldLabel htmlFor="repositoryUrl">Repository URL</FieldLabel>
          <Input
            {...fieldProps("repositoryUrl")}
            type="url"
            defaultValue={values.repositoryUrl}
            placeholder="https://github.com/owner/repo"
            required
            autoComplete="off"
          />
          <FieldDescription>
            A GitHub repository. Private repositories work if the server's token
            can see them.
          </FieldDescription>
          {error("repositoryUrl")}
        </Field>
        <Field data-invalid={errors.status ? true : undefined}>
          <FieldLabel htmlFor="status">Status</FieldLabel>
          <Select
            name="status"
            items={STATUS_ITEMS}
            defaultValue={values.status ?? "planning"}
          >
            <SelectTrigger
              id="status"
              className="w-48"
              aria-invalid={errors.status ? true : undefined}
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {STATUS_ITEMS.map((item) => (
                <SelectItem key={item.value} value={item.value}>
                  {item.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {error("status")}
        </Field>
        <Field data-invalid={errors.description ? true : undefined}>
          <FieldLabel htmlFor="description">Description</FieldLabel>
          <Textarea
            {...fieldProps("description")}
            defaultValue={values.description}
            maxLength={2000}
            rows={4}
          />
          {error("description")}
        </Field>
      </FieldGroup>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" disabled={submitting}>
          {submitting && <Spinner />}
          {submitLabel}
        </Button>
        <Button
          variant="ghost"
          render={<Link to={cancelTo} />}
          nativeButton={false}
        >
          Cancel
        </Button>
      </div>
    </Form>
  )
}

/** Turns a failed save into what the form shows: field errors, or one message when there are none. */
export function formResult(
  fieldErrors: Record<string, string>,
  message: string,
  values: ProjectFormValues
): ProjectFormResult {
  const hasFieldErrors = Object.keys(fieldErrors).length > 0
  return {
    errors: fieldErrors,
    formError: hasFieldErrors ? undefined : message,
    values,
  }
}
