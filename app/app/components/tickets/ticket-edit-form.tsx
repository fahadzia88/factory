import { useState } from "react"

import { Alert, AlertDescription } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Field,
  FieldDescription,
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
  editTicket,
  PRIORITY_LABEL,
  ticketError,
  TICKET_PRIORITIES,
  type Ticket,
  type TicketChanges,
  type TicketPriority,
} from "~/lib/tickets"

const PRIORITY_ITEMS = TICKET_PRIORITIES.map((value) => ({
  value,
  label: PRIORITY_LABEL[value],
}))

/** Edits a ticket's title, description, priority and pull request link; only the changed fields are sent. */
export function TicketEditForm({
  ticket,
  onSaved,
  onCancel,
}: {
  ticket: Ticket
  onSaved: () => void
  onCancel: () => void
}) {
  const [title, setTitle] = useState(ticket.title)
  const [description, setDescription] = useState(ticket.description ?? "")
  const [priority, setPriority] = useState<TicketPriority>(ticket.priority)
  const [link, setLink] = useState(ticket.pullRequestUrl ?? "")
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    const changes: TicketChanges = {}
    if (title !== ticket.title) changes.title = title
    if (description !== (ticket.description ?? "")) {
      changes.description = description
    }
    if (priority !== ticket.priority) changes.priority = priority
    if (link !== (ticket.pullRequestUrl ?? "")) changes.pullRequestUrl = link
    if (Object.keys(changes).length === 0) {
      onCancel()
      return
    }
    setSaving(true)
    setError(null)
    try {
      await editTicket(ticket.projectId, ticket.id, changes)
      onSaved()
    } catch (reason) {
      const problem = ticketError(reason)
      setError(
        problem.retryable ? `${problem.message} Try again.` : problem.message
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      {error && (
        <Alert variant="destructive">
          <AlertDescription className="break-words">{error}</AlertDescription>
        </Alert>
      )}
      <FieldGroup>
        <Field>
          <FieldLabel htmlFor="edit-title">Title</FieldLabel>
          <Input
            id="edit-title"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            maxLength={200}
            required
            autoComplete="off"
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="edit-description">Description</FieldLabel>
          <Textarea
            id="edit-description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={10000}
            rows={6}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="edit-priority">Priority</FieldLabel>
          <Select
            items={PRIORITY_ITEMS}
            value={priority}
            onValueChange={(value) => setPriority(value as TicketPriority)}
          >
            <SelectTrigger id="edit-priority" className="w-48">
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
        <Field>
          <FieldLabel htmlFor="edit-link">Pull request link</FieldLabel>
          <Input
            id="edit-link"
            type="url"
            value={link}
            onChange={(event) => setLink(event.target.value)}
            placeholder="https://github.com/owner/repo/pull/1"
            autoComplete="off"
          />
          <FieldDescription>
            Optional. Creating a pull request from this page fills it in.
          </FieldDescription>
        </Field>
      </FieldGroup>
      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={saving}>
          {saving && <Spinner aria-hidden />}
          Save
        </Button>
      </div>
    </form>
  )
}
