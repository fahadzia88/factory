import { useId, useState } from "react"

import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import { Card, CardContent, CardFooter } from "~/components/ui/card"
import { Checkbox } from "~/components/ui/checkbox"
import { Input } from "~/components/ui/input"
import { RadioGroup, RadioGroupItem } from "~/components/ui/radio-group"
import type { Question, QuestionAnswer, QuestionSet } from "~/lib/features"

const OTHER = "\u0000other"

/** The agent's questions as cards: pick options (or "Other") and send them as one answer. */
export function QuestionCards({
  set,
  disabled,
  onSubmit,
}: {
  set: QuestionSet
  disabled: boolean
  onSubmit: (answers: QuestionAnswer[]) => void
}) {
  if (set.status === "superseded") {
    return (
      <p className="self-start text-xs text-muted-foreground">
        Replaced by newer questions
      </p>
    )
  }
  if (set.status === "answered") return <AnsweredCards set={set} />
  return <PendingCards set={set} disabled={disabled} onSubmit={onSubmit} />
}

function PendingCards({
  set,
  disabled,
  onSubmit,
}: {
  set: QuestionSet
  disabled: boolean
  onSubmit: (answers: QuestionAnswer[]) => void
}) {
  const [chosen, setChosen] = useState<string[][]>(() =>
    set.questions.map(() => [])
  )
  const [others, setOthers] = useState<string[]>(() =>
    set.questions.map(() => "")
  )

  const answers: QuestionAnswer[] = set.questions.map((q, i) => ({
    header: q.header,
    selected: chosen[i].filter((c) => c !== OTHER),
    other: chosen[i].includes(OTHER) ? others[i].trim() || null : null,
  }))
  const complete = answers.every((a) => a.selected.length > 0 || a.other)

  const choose = (i: number, values: string[]) =>
    setChosen((all) => all.map((c, j) => (j === i ? values : c)))

  return (
    <Card className="w-full min-w-0 shrink-0">
      <CardContent className="flex flex-col gap-6">
        {set.questions.map((question, i) => (
          <QuestionField
            key={`${i}-${question.header}`}
            question={question}
            chosen={chosen[i]}
            other={others[i]}
            disabled={disabled}
            onChoose={(values) => choose(i, values)}
            onOther={(text) =>
              setOthers((all) => all.map((o, j) => (j === i ? text : o)))
            }
          />
        ))}
      </CardContent>
      <CardFooter className="gap-3">
        <Button
          disabled={disabled || !complete}
          onClick={() => onSubmit(answers)}
        >
          Send answers
        </Button>
        {disabled && (
          <span className="text-xs text-muted-foreground">
            Waiting for the agent to finish…
          </span>
        )}
      </CardFooter>
    </Card>
  )
}

function QuestionField({
  question,
  chosen,
  other,
  disabled,
  onChoose,
  onOther,
}: {
  question: Question
  chosen: string[]
  other: string
  disabled: boolean
  onChoose: (values: string[]) => void
  onOther: (text: string) => void
}) {
  const id = useId()
  const options = [
    ...question.options.map((o, i) => ({
      ...o,
      value: o.label,
      recommended: i === 0,
    })),
    { label: "Other", description: "", value: OTHER, recommended: false },
  ]
  const row = (option: (typeof options)[number], control: React.ReactNode) => (
    <label
      key={option.value}
      className="flex min-w-0 cursor-pointer items-start gap-3 rounded-md p-1"
    >
      <span className="pt-0.5">{control}</span>
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="flex flex-wrap items-center gap-2 text-sm font-medium break-words">
          {option.label}
          {option.recommended && <Badge variant="secondary">Recommended</Badge>}
        </span>
        {option.description && (
          <span className="text-sm break-words text-muted-foreground">
            {option.description}
          </span>
        )}
      </span>
    </label>
  )

  return (
    <fieldset className="flex min-w-0 flex-col gap-3" disabled={disabled}>
      <legend className="flex min-w-0 flex-col gap-2">
        <Badge variant="outline">{question.header}</Badge>
        <span className="text-sm break-words">{question.question}</span>
      </legend>
      {question.multiSelect ? (
        <div className="flex flex-col gap-2">
          {options.map((option) =>
            row(
              option,
              <Checkbox
                checked={chosen.includes(option.value)}
                disabled={disabled}
                onCheckedChange={(checked) =>
                  onChoose(
                    checked
                      ? [...chosen, option.value]
                      : chosen.filter((c) => c !== option.value)
                  )
                }
              />
            )
          )}
        </div>
      ) : (
        <RadioGroup
          value={chosen[0] ?? null}
          disabled={disabled}
          onValueChange={(value) => onChoose([value as string])}
        >
          {options.map((option) =>
            row(option, <RadioGroupItem value={option.value} />)
          )}
        </RadioGroup>
      )}
      {chosen.includes(OTHER) && (
        <Input
          id={`${id}-other`}
          aria-label="Other answer"
          value={other}
          maxLength={500}
          disabled={disabled}
          onChange={(event) => onOther(event.target.value)}
        />
      )}
    </fieldset>
  )
}

function AnsweredCards({ set }: { set: QuestionSet }) {
  return (
    <Card className="w-full min-w-0 shrink-0">
      <CardContent className="flex flex-col gap-3">
        {set.answers === null ? (
          <p className="text-sm text-muted-foreground">Answered in the chat</p>
        ) : (
          set.questions.map((question, i) => {
            const answer = set.answers?.[i]
            const parts = [
              ...(answer?.selected ?? []),
              ...(answer?.other ? [`Other: ${answer.other}`] : []),
            ]
            return (
              <div
                key={`${i}-${question.header}`}
                className="flex min-w-0 flex-col gap-1"
              >
                <Badge variant="outline">{question.header}</Badge>
                <span className="text-sm break-words text-muted-foreground">
                  {question.question}
                </span>
                <span className="text-sm font-medium break-words">
                  {parts.join(", ")}
                </span>
              </div>
            )
          })
        )}
      </CardContent>
    </Card>
  )
}
