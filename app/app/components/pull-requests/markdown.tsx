import ReactMarkdown, { type Components } from "react-markdown"
import remarkGfm from "remark-gfm"

// Pull request descriptions are untrusted: react-markdown shows raw HTML as text and drops
// javascript: URLs by default, so no HTML plugin is added here.
const components: Components = {
  h1: (props) => (
    <h2 className="mt-6 mb-2 text-xl font-semibold first:mt-0" {...props} />
  ),
  h2: (props) => (
    <h3 className="mt-6 mb-2 text-lg font-semibold first:mt-0" {...props} />
  ),
  h3: (props) => (
    <h4 className="mt-5 mb-2 text-base font-semibold first:mt-0" {...props} />
  ),
  h4: (props) => <h5 className="mt-4 mb-2 font-semibold" {...props} />,
  h5: (props) => <h6 className="mt-4 mb-2 font-semibold" {...props} />,
  h6: (props) => (
    <h6 className="mt-4 mb-2 font-semibold text-muted-foreground" {...props} />
  ),
  p: (props) => <p className="my-3 first:mt-0 last:mb-0" {...props} />,
  ul: ({ className, ...props }) => (
    <ul
      className={
        className?.includes("contains-task-list")
          ? "my-3 flex flex-col gap-1"
          : "my-3 list-disc pl-6"
      }
      {...props}
    />
  ),
  ol: (props) => <ol className="my-3 list-decimal pl-6" {...props} />,
  li: (props) => <li className="my-1 break-words" {...props} />,
  input: (props) => (
    <input {...props} disabled className="mr-1.5 align-middle" />
  ),
  a: (props) => (
    <a
      {...props}
      target="_blank"
      rel="noreferrer"
      className="font-medium break-words text-primary underline-offset-4 hover:underline"
    />
  ),
  blockquote: (props) => (
    <blockquote
      className="my-3 border-l-2 pl-4 text-muted-foreground"
      {...props}
    />
  ),
  code: ({ className, ...props }) => (
    <code
      className={
        className
          ? "font-mono text-sm"
          : "rounded bg-muted px-1 py-0.5 font-mono text-sm break-words"
      }
      {...props}
    />
  ),
  pre: (props) => (
    <pre
      className="my-3 overflow-x-auto rounded-lg bg-muted p-3 text-sm"
      {...props}
    />
  ),
  table: (props) => (
    <div className="my-3 overflow-x-auto">
      <table className="w-full border-collapse text-sm" {...props} />
    </div>
  ),
  th: (props) => (
    <th className="border px-3 py-1.5 text-left font-medium" {...props} />
  ),
  td: (props) => <td className="border px-3 py-1.5" {...props} />,
  hr: () => <hr className="my-4" />,
  img: (props) => <img {...props} className="my-2 max-w-full rounded" />,
}

/** GitHub-flavored Markdown from GitHub, rendered without raw HTML. */
export function Markdown({ children }: { children: string }) {
  return (
    <div className="min-w-0 text-sm leading-relaxed">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  )
}
