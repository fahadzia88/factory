import { ApiError, api } from "~/lib/api"
import { clearPassword, getPassword, setPassword } from "~/lib/password"

// The password last confirmed by the backend in this page load, so navigation doesn't re-check it.
let verifiedPassword: string | null = null

/** Resolves false for a rejected password; throws if the backend can't be reached. */
export async function verifyPassword(password: string) {
  try {
    await api.get("/api/auth/verify", { token: password })
    return true
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return false
    throw error
  }
}

/** Checks the stored password with the backend, clearing it if it's been rejected. */
export async function isAuthenticated() {
  const password = getPassword()
  if (!password) return false
  if (password === verifiedPassword) return true

  const valid = await verifyPassword(password)
  if (valid) verifiedPassword = password
  else clearPassword()
  return valid
}

export async function login(password: string) {
  const valid = await verifyPassword(password)
  if (valid) {
    setPassword(password)
    verifiedPassword = password
  }
  return valid
}

export function logout() {
  clearPassword()
  verifiedPassword = null
}
