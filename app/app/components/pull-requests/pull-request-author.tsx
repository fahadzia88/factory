import { Avatar, AvatarFallback, AvatarImage } from "~/components/ui/avatar"
import type { PullRequestAuthor as Author } from "~/lib/pull-requests"

export function PullRequestAuthor({ author }: { author: Author | null }) {
  if (!author) return <span className="text-muted-foreground">Unknown</span>
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5">
      <Avatar size="sm" className="size-5">
        <AvatarImage src={author.avatarUrl} alt="" />
        <AvatarFallback className="text-[0.625rem]">
          {author.login.slice(0, 2).toUpperCase()}
        </AvatarFallback>
      </Avatar>
      <span className="truncate">{author.login}</span>
    </span>
  )
}
