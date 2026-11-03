# api/ — Spring Boot backend

Rules for working in this folder. `app/` (React frontend) and `agent/` (eve agent) have their own
owners: don't edit them or `docker-compose.yml` from here; ask for the change instead.

## Running things

- The host has no JDK. Gradle runs in Docker on the `gradle:jdk25` image, in either:
  - the `skeleton-api` container, when it's up: `docker exec -w /workspace skeleton-api ./gradlew test --no-daemon -q`;
  - a throwaway container: `docker run --rm -v "$PWD":/workspace -w /workspace -v skeleton-gradle-cache:/home/gradle/.gradle gradle:jdk25 ./gradlew test --no-daemon -q`.
  Only one Gradle process can hold the cache lock; stop a running `bootRun` before testing.
- Docker can't mount `/private/tmp`; mount paths under the repo.
- Compose is the standalone `docker-compose`, not `docker compose`. The `api` service runs
  `sleep infinity`; start the app inside it with `./gradlew bootRun`. With devtools,
  `./gradlew classes` hot-restarts a running app.
- The app needs MySQL (`docker-compose up -d --no-deps mysql`, host `skeleton-mysql` on
  `skeleton_default`). Stop what you started.
- Before calling work done: `./gradlew test` passes, and for config or wiring changes the app
  starts against MySQL.

## Stack

- Spring Boot 4, Kotlin 2.3, Java 25, Gradle (Groovy DSL).
- Jackson 3: the classes are in `tools.jackson.*`, but annotations are still
  `com.fasterxml.jackson.annotation.*`. Strings come from `JsonNode.asString()`.
- JPA with Hibernate `ddl-auto=update` (no migration tool yet), MySQL 8.4; tests use H2.
- HTTP clients: Spring `RestClient`, one bean per upstream, built in that feature's `config/`.

## Patterns

- **Packages.** One package per feature under `com.toptier.custom`, split into `config/`,
  `controller/`, `service/`, `dto/`, `domain/` (entities and repositories) and `exception/`
  as needed. Shared beans go in `config/`.
- **Auth.** Every request needs `Authorization: Bearer <app.security.api-token>`
  (`StaticTokenAuthenticationFilter`). Public routes must be listed in `SecurityConfig`; add
  them only with a reason in a comment.
- **Config.** Typed `@ConfigurationProperties` data classes (picked up by
  `@ConfigurationPropertiesScan`) that validate in `init`. Properties live in
  `application.properties` as `${ENV_VAR:default}` with a comment saying what they're for.
  Secrets never go in the repo; `secrets/` is gitignored.
- **Errors.** A feature throws its own exceptions and maps them in a
  `@RestControllerAdvice(basePackageClasses = [...])` to `ProblemDetail`: bad input → 400,
  missing → 404, an upstream failure → 502, an upstream timeout → 504. Details say what
  happened in plain words.
- **Time.** Inject the `Clock` bean, never call `Instant.now()`; tests use `MutableClock`.
- **Scheduling.** `@Scheduled` jobs run only when `app.scheduling.enabled=true`; tests turn it
  off.
- **Agent.** Server-side LLM calls go through `agent.service.AgentService.ask(...)`. The eve
  protocol is in `agent/README.md` ("HTTP API") and `agent/node_modules/eve/docs/`.

## Tests

- JUnit 5 with `kotlin-test`. Mock beans with `@MockitoBean` and BDDMockito `given`; there is
  no mockito-kotlin.
- Controllers: `@WebMvcTest(XController::class)` importing `SecurityConfig` and the feature's
  exception handler, with `app.security.api-token=test-token`.
- Upstream HTTP: `MockRestServiceServer.bindTo(builder)` on a `RestClient.Builder`, with
  fixtures shaped like real responses. Tests never call real services.
- Repositories: `@DataJpaTest` on H2.

## Style

- Tabs for indentation, as in the existing files.
- Match the surrounding code: KDoc on all classes and functions but few inline
  comments, and only ones that say why.
- Keep API contracts stable; tell the frontend session before changing one.
- Don't commit or push unless asked.
