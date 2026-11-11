import * as React from "react"

import {
  Tabs as TabsPrimitive,
  TabsList as TabsListPrimitive,
  TabsTab as TabsTabPrimitive,
  TabsPanel as TabsPanelPrimitive,
  TabsPanels as TabsPanelsPrimitive,
  TabsHighlight as TabsHighlightPrimitive,
  TabsHighlightItem as TabsHighlightItemPrimitive,
  type TabsProps as TabsPrimitiveProps,
  type TabsListProps as TabsListPrimitiveProps,
  type TabsTabProps as TabsTabPrimitiveProps,
  type TabsPanelProps as TabsPanelPrimitiveProps,
  type TabsPanelsProps as TabsPanelsPrimitiveProps,
} from "~/components/animate-ui/primitives/base/tabs"
import { cn } from "~/lib/utils"

type TabsProps = TabsPrimitiveProps

function Tabs({ className, ...props }: TabsProps) {
  return (
    <TabsPrimitive
      className={cn("flex flex-col gap-2", className)}
      {...props}
    />
  )
}

type TabsListProps = TabsListPrimitiveProps

function TabsList({ className, ...props }: TabsListProps) {
  return (
    <TabsHighlightPrimitive className="absolute inset-0 z-0 rounded-md border border-transparent bg-background shadow-sm dark:border-input dark:bg-input/30">
      <TabsListPrimitive
        className={cn(
          "inline-flex h-9 w-fit items-center justify-center rounded-lg bg-muted p-[3px] text-muted-foreground",
          className
        )}
        {...props}
      />
    </TabsHighlightPrimitive>
  )
}

type TabsTabProps = TabsTabPrimitiveProps

function TabsTab({ className, ...props }: TabsTabProps) {
  return (
    <TabsHighlightItemPrimitive value={props.value} className="flex-1">
      <TabsTabPrimitive
        className={cn(
          "inline-flex h-[calc(100%-1px)] w-full flex-1 items-center justify-center gap-1.5 rounded-md px-2 py-1 text-sm font-medium whitespace-nowrap text-muted-foreground transition-colors duration-500 ease-in-out focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-1 focus-visible:outline-ring disabled:pointer-events-none disabled:opacity-50 data-[selected]:text-foreground [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
          className
        )}
        {...props}
      />
    </TabsHighlightItemPrimitive>
  )
}

type TabsPanelsProps = TabsPanelsPrimitiveProps

function TabsPanels(props: TabsPanelsProps) {
  return <TabsPanelsPrimitive {...props} />
}

type TabsPanelProps = TabsPanelPrimitiveProps

function TabsPanel({ className, ...props }: TabsPanelProps) {
  return (
    <TabsPanelPrimitive
      className={cn("flex-1 outline-none", className)}
      {...props}
    />
  )
}

export {
  Tabs,
  TabsList,
  TabsTab,
  TabsPanels,
  TabsPanel,
  type TabsProps,
  type TabsListProps,
  type TabsTabProps,
  type TabsPanelsProps,
  type TabsPanelProps,
}
