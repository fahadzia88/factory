import { redirect } from "react-router"

// The dashboard sends unauthenticated users on to /login.
export function loader() {
  return redirect("/dashboard")
}
