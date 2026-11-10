import {
  ChevronRightIcon,
  FileIcon,
  FileTextIcon,
  FolderIcon,
  FolderOpenIcon,
} from "lucide-react"
import { useEffect, useMemo, useRef, useState } from "react"
import type { KeyboardEvent } from "react"

import type { FeatureFileEntry } from "~/lib/feature-files"
import { cn } from "~/lib/utils"

type TreeNode = {
  entry: FeatureFileEntry
  level: number
  parent: string | null
  posinset: number
  setsize: number
}

/** Every node by path, in display order, whether visible or not. */
function indexTree(entries: FeatureFileEntry[]) {
  const nodes = new Map<string, TreeNode>()
  const walk = (
    list: FeatureFileEntry[],
    level: number,
    parent: string | null
  ) => {
    list.forEach((entry, index) => {
      nodes.set(entry.path, {
        entry,
        level,
        parent,
        posinset: index + 1,
        setsize: list.length,
      })
      if (entry.type === "directory")
        walk(entry.children, level + 1, entry.path)
    })
  }
  walk(entries, 1, null)
  return nodes
}

function visibleNodes(entries: FeatureFileEntry[], expanded: Set<string>) {
  const nodes = indexTree(entries)
  const visible: TreeNode[] = []
  const walk = (list: FeatureFileEntry[]) => {
    for (const entry of list) {
      visible.push(nodes.get(entry.path)!)
      if (entry.type === "directory" && expanded.has(entry.path)) {
        walk(entry.children)
      }
    }
  }
  walk(entries)
  return { nodes, visible }
}

const isDisabled = (entry: FeatureFileEntry) =>
  entry.type === "file" && !entry.markdown

export function FileTree({
  entries,
  selectedPath,
  onSelect,
  label = "Feature documents",
}: {
  entries: FeatureFileEntry[]
  selectedPath: string | null
  onSelect: (path: string) => void
  label?: string
}) {
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())
  const [focusedPath, setFocusedPath] = useState<string | null>(null)
  const rows = useRef(new Map<string, HTMLLIElement>())

  const { nodes, visible } = useMemo(
    () => visibleNodes(entries, expanded),
    [entries, expanded]
  )

  // A refresh keeps the state, minus paths that are gone
  useEffect(() => {
    const index = indexTree(entries)
    setExpanded((current) => {
      const kept = [...current].filter(
        (path) => index.get(path)?.entry.type === "directory"
      )
      return kept.length === current.size ? current : new Set(kept)
    })
    setFocusedPath((current) =>
      current !== null && !index.has(current) ? null : current
    )
  }, [entries])

  // Open the folders above a file selected elsewhere so it's visible, once per
  // selection, so a refresh doesn't undo a folder the user closed
  const revealed = useRef<string | null>(null)
  useEffect(() => {
    if (selectedPath === null || selectedPath === revealed.current) return
    const index = indexTree(entries)
    if (!index.has(selectedPath)) return
    revealed.current = selectedPath
    const ancestors: string[] = []
    for (
      let parent = index.get(selectedPath)?.parent ?? null;
      parent !== null;
      parent = index.get(parent)?.parent ?? null
    ) {
      ancestors.push(parent)
    }
    if (ancestors.length === 0) return
    setExpanded((current) =>
      ancestors.every((path) => current.has(path))
        ? current
        : new Set([...current, ...ancestors])
    )
  }, [selectedPath, entries])

  const focused =
    visible.find((node) => node.entry.path === focusedPath) ??
    visible[0] ??
    null

  const focus = (path: string) => {
    setFocusedPath(path)
    rows.current.get(path)?.focus()
  }

  const toggle = (path: string, open?: boolean) =>
    setExpanded((current) => {
      const next = new Set(current)
      if (open ?? !next.has(path)) next.add(path)
      else next.delete(path)
      return next
    })

  const activate = (entry: FeatureFileEntry) => {
    if (entry.type === "directory") toggle(entry.path)
    else if (entry.markdown) onSelect(entry.path)
  }

  const onKeyDown = (event: KeyboardEvent<HTMLUListElement>) => {
    if (!focused) return
    const { entry } = focused
    const at = visible.indexOf(focused)
    const isOpen = entry.type === "directory" && expanded.has(entry.path)
    switch (event.key) {
      case "ArrowDown":
        if (at < visible.length - 1) focus(visible[at + 1].entry.path)
        break
      case "ArrowUp":
        if (at > 0) focus(visible[at - 1].entry.path)
        break
      case "Home":
        focus(visible[0].entry.path)
        break
      case "End":
        focus(visible[visible.length - 1].entry.path)
        break
      case "ArrowRight":
        if (entry.type !== "directory") break
        if (!isOpen) toggle(entry.path, true)
        else if (entry.children.length > 0) focus(entry.children[0].path)
        break
      case "ArrowLeft":
        if (isOpen) toggle(entry.path, false)
        else if (focused.parent !== null) focus(focused.parent)
        break
      case "Enter":
      case " ":
        activate(entry)
        break
      default:
        return
    }
    event.preventDefault()
  }

  return (
    <ul
      role="tree"
      aria-label={label}
      className="flex min-w-0 flex-col gap-0.5"
      onKeyDown={onKeyDown}
    >
      {visible.map(({ entry, level, posinset, setsize }) => {
        const isDirectory = entry.type === "directory"
        const isOpen = isDirectory && expanded.has(entry.path)
        const disabled = isDisabled(entry)
        const selected = entry.path === selectedPath
        const Icon = isDirectory
          ? isOpen
            ? FolderOpenIcon
            : FolderIcon
          : entry.name.toLowerCase().endsWith(".md")
            ? FileTextIcon
            : FileIcon
        return (
          <li
            key={entry.path}
            ref={(element) => {
              if (element) rows.current.set(entry.path, element)
              else rows.current.delete(entry.path)
            }}
            role="treeitem"
            aria-level={level}
            aria-posinset={posinset}
            aria-setsize={setsize}
            aria-expanded={isDirectory ? isOpen : undefined}
            aria-selected={selected}
            aria-disabled={disabled ? true : undefined}
            tabIndex={entry.path === focused?.entry.path ? 0 : -1}
            title={entry.path}
            style={{ paddingLeft: `${level * 0.75}rem` }}
            className={cn(
              "flex min-w-0 cursor-pointer items-center gap-1.5 rounded-md py-1 pr-2 text-sm outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/50",
              selected
                ? "bg-accent text-accent-foreground"
                : !disabled && "hover:bg-muted",
              disabled && "cursor-not-allowed text-muted-foreground opacity-60"
            )}
            onClick={() => {
              focus(entry.path)
              activate(entry)
            }}
          >
            {isDirectory ? (
              <ChevronRightIcon
                aria-hidden
                className={cn(
                  "size-4 shrink-0 text-muted-foreground transition-transform",
                  isOpen && "rotate-90"
                )}
              />
            ) : (
              <span aria-hidden className="size-4 shrink-0" />
            )}
            <Icon aria-hidden className="size-4 shrink-0" />
            <span className="min-w-0 truncate">{entry.name}</span>
          </li>
        )
      })}
    </ul>
  )
}
