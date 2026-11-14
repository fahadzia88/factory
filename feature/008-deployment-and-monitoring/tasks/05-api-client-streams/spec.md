---
feature: 008-deployment-and-monitoring
task: 05-api-client-streams
layer: api
status: draft
approved:
branch: feat/008-deployment-and-monitoring/05-api-client-streams
base: feat/008-deployment-and-monitoring/04-api-client-calls
covers: [AC-16, AC-17, AC-37]
---

# 05 — Client streams: watch and logs

## Goal

Stream the controller's `/api/watch` and `/logs` through the api: an SSE parser, a watch that reconnects with backoff and tells the consumer to reset, a log passthrough, and cancellation that aborts the upstream request.

## Contract

- Watch output (consumed by task 08): the events `pod` (`Pod & { type }`) and `error` (`{ message }`) pass through unchanged; keep-alive comments pass through; before each replay after a reconnect the stream emits `event: reset` with data `{}`.
- Logs: text passes through as it arrives; the `X-Container` value is returned to the caller.
- The token goes as a header; no `access_token` query parameter, ever.

## Files

Package `com.toptier.custom.homelab.service`:

- `SseParser.kt` — new: incremental parser over an `InputStream` of UTF-8 lines. Emits `SseFrame.Event(event, data, raw)` for each blank-line-terminated event (multi-line `data:` joined with `\n`, default event name `message`), `SseFrame.Comment(raw)` for `:` lines. `raw` is the original text, so a proxy can forward frames unchanged.
- `HomelabWatch.kt` — new: `watch(address, namespace?, sink: (SseFrame) -> Unit, isCancelled: () -> Boolean)`: opens `GET /api/watch` with the stream client (no read timeout), parses frames, calls `sink`. When the upstream ends or fails while not cancelled, waits with backoff (`watchBackoffInitial`, doubling up to `watchBackoffMax`, reset after a connection that delivered a frame) and reconnects, calling `sink(Reset)` first. A 401, 403 or 400 stops the loop and throws (no retry); 502 and I/O errors retry. When cancelled, closes the upstream input stream at once. Uses `Thread.sleep` through an injectable `Sleeper` so tests don't wait.
- `HomelabLogs.kt` — new: `logs(address, namespace, pod, options, out: OutputStream)` calls `GET /api/pods/{ns}/{pod}/logs` with `container`, `tail`, `since`, `previous`, `follow`, `timestamps`, copies bytes to `out` as they arrive (flushing each chunk), and closes the upstream when writing fails (client left). Returns the container from `X-Container`. A non-follow request keeps the 10 s timeout; `follow=true` uses the stream client. Because headers must reach the caller before the body, expose `openLogs(...)` returning `LogStream(container, input)` the controller copies; closing it aborts the upstream request.
- `SseFrame.kt` — new: sealed type `Event`, `Comment`, `Reset`.

## Steps

1. Write `SseParser` and test it in isolation.
2. Write `HomelabLogs.openLogs` using `RestClient.exchange` to hand back the open body and header; the caller closes it.
3. Write `HomelabWatch` with the loop above; keep it free of Spring web types so the controller in task 08 only supplies the sink.
4. Never log frame data (pods may carry sensitive messages beyond names); log only connects, reconnects and stops with the cluster address.

## Tests

- `SseParserTests` — event name and data; multi-line data; keep-alive comments are returned as comments and don't end events; `\r\n` line ends; a frame split across reads; unknown fields ignored (AC-37).
- `HomelabWatchTests` — with a local `com.sun.net.httpserver.HttpServer` (the api tests forbid real services, a local fake is fine): frames forwarded unchanged (AC-17); the upstream closing triggers a reconnect with `Reset` first and increasing backoff delays via a fake `Sleeper` (AC-17, AC-37); a 401 stops with no retry; `isCancelled` closes the connection, verified by the server seeing the socket close (AC-37); the request carries the bearer header and no `access_token` (AC-37).
- `HomelabLogsTests` — options become the right query; `X-Container` is returned; closing the stream aborts the upstream (AC-16).

## Checks

- `docker exec -w /workspace factory-api ./gradlew test --no-daemon -q`

## Out of scope

- The project endpoints that expose these streams and the namespace filter (task 08).
