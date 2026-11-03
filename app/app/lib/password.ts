const STORAGE_KEY = "auth.password"

// localStorage can be missing (SSR) or throw (private mode, blocked storage), so every access is guarded.

export function getPassword(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

export function setPassword(password: string) {
  try {
    localStorage.setItem(STORAGE_KEY, password)
  } catch {
    // Not persisted; the user will be asked again on next load.
  }
}

export function clearPassword() {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing stored.
  }
}
