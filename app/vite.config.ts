import { reactRouter } from "@react-router/dev/vite"
import tailwindcss from "@tailwindcss/vite"
import { defineConfig } from "vite"

export default defineConfig({
  resolve: { tsconfigPaths: true },
  plugins: [tailwindcss(), reactRouter()],
  server: {
    host: "0.0.0.0",
    port: 3000,
    // Same-origin API calls in dev; API_URL is set in docker-compose.yml.
    // /eve reaches the eve agent (AGENT_URL in docker-compose.yml); its NDJSON streams pass
    // through unbuffered because the agent doesn't compress them
    proxy: {
      "/api": process.env.API_URL ?? "http://localhost:8080",
      "/eve": process.env.AGENT_URL ?? "http://localhost:3001",
    },
    // File change events from a macOS host don't reach the container through the bind mount
    watch: { usePolling: true, interval: 300 },
    // Extra hostnames the dev server answers to (e.g. a tunnel), comma-separated
    allowedHosts: process.env.ALLOWED_HOSTS?.split(",")
      .map((host) => host.trim())
      .filter(Boolean),
  },
})
