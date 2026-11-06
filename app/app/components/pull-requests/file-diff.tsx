import { cn } from "~/lib/utils"

function lineClass(line: string) {
  if (line.startsWith("@@")) return "bg-muted text-muted-foreground"
  if (line.startsWith("+")) return "bg-diff-added"
  if (line.startsWith("-")) return "bg-diff-removed"
  return undefined
}

/** A unified diff as GitHub sends it. The leading +, - or space stays, so it reads without colour. */
export function FileDiff({ patch }: { patch: string }) {
  return (
    <div className="max-h-[32rem] overflow-auto border-t">
      <pre className="min-w-max font-mono text-xs leading-5">
        {patch.split("\n").map((line, i) => (
          <div key={i} className={cn("px-3", lineClass(line))}>
            {line || " "}
          </div>
        ))}
      </pre>
    </div>
  )
}
