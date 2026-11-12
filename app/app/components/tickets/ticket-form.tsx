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
import { Label } from "~/components/ui/label"
import { RadioGroup, RadioGroupItem } from "~/components/ui/radio-group"
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
  PRIORITY_LABEL,
  TICKET_PRIORITIES,
  TICKET_TYPES,
  TYPE_LABEL,
  type TicketInput,
} from "~/lib/tickets"

/** What the create action returns when the ticket isn't created. */
export type TicketFormResult = {
  values: TicketInput
  errors?: { type?: string; title?: string }
  formError?: string
  retryable?: boolean
}

const PRIORITY_ITEMS = TICKET_PRIORITIES.map((value) => ({
  value,
  label: PRIORITY_LABEL[value],
}))

const TYPE_HINT = {
  bug: "Something is broken.",
  improvement: "Something works but could be better.",
} as const

export function TicketForm({
  defaultValues,
  result,
  submitting,
  cancelTo,
}: {
  defaultValues: TicketInput
  result?: TicketFormResult
  submitting: boolean
  cancelTo: string
}) {
  const values = result?.values ?? defaultValues
  const errors = result?.errors ?? {}

  return (
    <Form method="post" className="flex max-w-2xl flex-col gap-6">
      {result?.formError && (
        <Alert variant="destructive">
          <AlertDescription>
            {result.formError}
            {result.retryable ? " Try again." : ""}
          </AlertDescription>
        </Alert>
      )}
      <FieldGroup>
        <Field data-invalid={errors.type ? true : undefined}>
          <FieldLabel id="type-label">Type</FieldLabel>
          <RadioGroup
            name="type"
            defaultValue={values.type}
            aria-labelledby="type-label"
            aria-describedby={errors.type ? "type-error" : undefined}
            className="gap-3"
          >
            {TICKET_TYPES.map((type) => (
              <div key={type} className="flex items-start gap-2">
                <RadioGroupItem value={type} id={`type-${type}`} />
                <div className="flex flex-col">
                  <Label htmlFor={`type-${type}`}>{TYPE_LABEL[type]}</Label>
                  <span className="text-sm text-muted-foreground">
                    {TYPE_HINT[type]}
                  </span>
                </div>
              </div>
            ))}
          </RadioGroup>
          {errors.type && (
            <FieldError id="type-error">{errors.type}</FieldError>
          )}
        </Field>
        <Field data-invalid={errors.title ? true : undefined}>
          <FieldLabel htmlFor="title">Title</FieldLabel>
          <Input
            id="title"
            name="title"
            defaultValue={values.title}
            maxLength={200}
            required
            autoComplete="off"
            aria-invalid={errors.title ? true : undefined}
            aria-describedby={errors.title ? "title-error" : undefined}
          />
          {errors.title && (
            <FieldError id="title-error">{errors.title}</FieldError>
          )}
        </Field>
        <Field>
          <FieldLabel htmlFor="description">Description</FieldLabel>
          <Textarea
            id="description"
            name="description"
            defaultValue={values.description}
            maxLength={10000}
            rows={6}
          />
          <FieldDescription>
            What happens, what you expected, and how to see it. Optional.
          </FieldDescription>
        </Field>
        <Field>
          <FieldLabel htmlFor="priority">Priority</FieldLabel>
          <Select
            name="priority"
            items={PRIORITY_ITEMS}
            defaultValue={values.priority}
          >
            <SelectTrigger id="priority" className="w-48">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PRIORITY_ITEMS.map((item) => (
                <SelectItem key={item.value} value={item.value}>
                  {item.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Field>
      </FieldGroup>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" disabled={submitting}>
          {submitting && <Spinner aria-hidden />}
          Create ticket
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
