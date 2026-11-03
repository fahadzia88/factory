import { HospitalIcon, LogOutIcon } from "lucide-react"
import {
  Link,
  NavLink,
  Outlet,
  redirect,
  useNavigate,
  useNavigation,
} from "react-router"

import { Button } from "~/components/ui/button"
import { Spinner } from "~/components/ui/spinner"
import { isAuthenticated, logout } from "~/lib/auth"
import { cn } from "~/lib/utils"

// Every page rendered inside this layout requires a validated password.
export async function clientLoader() {
  if (!(await isAuthenticated())) return redirect("/login")
  return null
}
clientLoader.hydrate = true as const

export function HydrateFallback() {
  return (
    <div className="flex min-h-svh items-center justify-center">
      <Spinner className="size-6 text-muted-foreground" />
    </div>
  )
}

const NAV_ITEMS = [
  { to: "/dashboard", label: "Dashboard" },
  { to: "/assistant", label: "Assistant" },
]

export default function AppLayout() {
  const navigate = useNavigate()
  const navigation = useNavigation()

  function handleSignOut() {
    logout()
    navigate("/login", { replace: true })
  }

  return (
    <div className="flex min-h-svh flex-col">
      <header className="sticky top-0 z-40 border-b bg-background/95 backdrop-blur supports-backdrop-filter:bg-background/80">
        <div className="mx-auto flex h-14 w-full max-w-6xl items-center gap-6 px-4 sm:px-6">
          <Link
            to="/dashboard"
            className="flex items-center gap-2 font-heading font-medium"
          >
            <HospitalIcon className="size-5 text-primary" aria-hidden />
            <span className="hidden sm:inline">Skeleton</span>
          </Link>
          <nav className="flex items-center gap-1" aria-label="Main">
            {NAV_ITEMS.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  cn(
                    "rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground",
                    isActive && "bg-muted text-foreground"
                  )
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <Button
            variant="ghost"
            size="sm"
            className="ml-auto"
            onClick={handleSignOut}
          >
            <LogOutIcon aria-hidden />
            Sign out
          </Button>
        </div>
        {/* Route data is loading */}
        <div
          className={cn(
            "absolute inset-x-0 bottom-0 h-0.5 origin-left bg-primary transition-transform duration-500",
            navigation.state === "loading"
              ? "scale-x-75"
              : "scale-x-0 duration-0"
          )}
          aria-hidden
        />
      </header>
      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-6 sm:px-6">
        <Outlet />
      </main>
    </div>
  )
}
