import {
  type RouteConfig,
  index,
  layout,
  route,
} from "@react-router/dev/routes"

export default [
  index("routes/home.tsx"),
  route("login", "routes/login.tsx"),
  layout("routes/app-layout.tsx", [
    route("dashboard", "routes/dashboard.tsx"),
    route("projects", "routes/projects.tsx"),
    route("projects/new", "routes/project-new.tsx"),
    route("projects/:id", "routes/project-detail.tsx"),
    route("projects/:id/edit", "routes/project-edit.tsx"),
    route("projects/:id/pulls", "routes/project-pulls.ts"),
    route("projects/:id/pulls/:number", "routes/pull-request-detail.tsx"),
    route("projects/:id/features", "routes/project-features.ts"),
    route("projects/:id/features/new", "routes/feature-new.tsx"),
    route("projects/:id/features/:featureId", "routes/feature-detail.tsx"),
    route("projects/:id/tickets", "routes/ticket-list.tsx"),
    route("projects/:id/tickets/new", "routes/ticket-new.tsx"),
    route("projects/:id/tickets/:ticketId", "routes/ticket-detail.tsx"),
    route("assistant", "routes/assistant.tsx"),
  ]),
] satisfies RouteConfig
