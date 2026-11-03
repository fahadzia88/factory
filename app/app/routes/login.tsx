import { HospitalIcon } from "lucide-react"
import { Form, redirect, useNavigation } from "react-router"

import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { isAuthenticated, login } from "~/lib/auth"
import type { Route } from "./+types/login"

export function meta() {
  return [{ title: "Sign in" }]
}

export async function clientLoader() {
  try {
    if (await isAuthenticated()) return redirect("/dashboard")
  } catch {
    // Backend unreachable: show the form; submitting will report the error.
  }
  return null
}
clientLoader.hydrate = true as const

export async function clientAction({ request }: Route.ClientActionArgs) {
  const password = String((await request.formData()).get("password") ?? "")
  if (!password) return { error: "Enter the password." }

  try {
    if (await login(password)) return redirect("/dashboard")
    return { error: "Incorrect password." }
  } catch {
    return { error: "Couldn't reach the server. Try again." }
  }
}

export function HydrateFallback() {
  return null
}

export default function Login({ actionData }: Route.ComponentProps) {
  const navigation = useNavigation()
  const submitting = navigation.state !== "idle"
  const error = actionData?.error

  return (
    <main className="flex min-h-svh items-center justify-center p-6">
      {/* A single-field form submits on Enter, so no button is needed */}
      <Form method="post" className="flex w-full max-w-xs flex-col gap-2">
        <h1 className="mb-4 flex items-center justify-center gap-2 font-heading text-2xl font-medium tracking-tight">
          <HospitalIcon className="size-6 text-primary" aria-hidden />
          Cirqa Care
        </h1>
        <Label htmlFor="password" className="sr-only">
          Password
        </Label>
        <Input
          id="password"
          name="password"
          type="password"
          placeholder="Password"
          autoComplete="current-password"
          autoFocus
          required
          disabled={submitting}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? "password-error" : undefined}
        />
        {error && (
          <p
            id="password-error"
            role="alert"
            className="text-sm text-destructive"
          >
            {error}
          </p>
        )}
      </Form>
    </main>
  )
}
