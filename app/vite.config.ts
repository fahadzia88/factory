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
    proxy: { "/api": process.env.API_URL ?? "http://localhost:8080" },
    // File change events from a macOS host don't reach the container through the bind mount
    watch: { usePolling: true, interval: 300 },
    allowedHosts: ["cirqaepic.fahadzia.dev"],
  },
})
