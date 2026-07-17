# agentic-analytics

A natural-language analytics agent built with Spring AI: ask a plain-English
question, the agent grounds itself in a data dictionary (RAG), queries a
Postgres data mart through tools it can call — some in-process, some exposed
over MCP for other clients — and returns an answer.

Built as a portfolio project, developed step by step, with each step
documented as we go rather than dropped in as one large scaffold.

## Roadmap

- [x] **Step 1** — Empty repo, `docker-compose.yaml` (Postgres only), this README.
- [x] **Step 2** — Spring Boot backend, REST starter only: health endpoint + two tests.
  - *Fix applied:* Spring Boot 4 modularized starters/test-autoconfiguration.
    Using `spring-boot-starter-webmvc` (not `-web`) + `spring-boot-starter-webmvc-test`,
    and bumped to **4.0.1** — 4.0.0 shipped with `spring-boot-test-autoconfigure`
    missing the web package entirely (spring-projects/spring-boot#48286).
- [x] **Step 3** — Wire backend into docker-compose; verify `docker compose up` end to end.
  - *Note:* Dockerfile and `pom.xml` `java.version` aligned to Java 26 (matches dev machine).
- [x] **Step 4** — Data mart schema + seed data, with tests.
  - *Design choice:* schema/seed data provisioned via a plain SQL script mounted
    into Postgres's `docker-entrypoint-initdb.d`, not an app-level migration
    framework — matches the pattern in
    [docker/compose-for-agents/langgraph](https://github.com/docker/compose-for-agents/tree/main/langgraph)
    and models the data mart as infra the agent *consumes*, not owns/migrates.
    (Considered Flyway; reasonable either way — see conversation history for
    the tradeoffs. This project's narrative fit the init-script approach better.)
  - The same script (`db-init/01_init_datamart.sql`) is used both by
    docker-compose (bind-mounted) and by the integration test (via Testcontainers'
    `withInitScript`, loaded from the classpath) — one source of truth.
  - Unit test (`QueryGuardTest`) — fast, no DB.
  - Integration test (`DataMartQueryServiceIT`) — real Postgres via Testcontainers,
    includes a regression check for the status/decline_reason seed-data bug we
    hit and fixed in an earlier draft of this project.
- [ ] **Step 5** — Spring AI: chat model, ChatClient, first tool-calling agent.
- [ ] **Step 6** — RAG: schema docs into pgvector, retrieval advisor.
- [ ] **Step 7** — MCP server exposing the same tools externally.
- [ ] **Step 8** — React frontend.
- [ ] **Step 9** — Angular frontend.
- [ ] **Step 10** — Both frontends switchable via compose profiles.

This list will get more granular as we go — some of these steps will split
into two or three once we're actually inside them.

## Running what exists so far

```bash
docker compose up --build
# then: curl localhost:8080/api/health
```

> If you change `db-init/01_init_datamart.sql`, run `docker compose down -v`
> first — Postgres only runs `docker-entrypoint-initdb.d` scripts on a fresh
> volume, so a stale `pgdata` volume will silently ignore your edit.

Or without Docker Compose, for faster local iteration while developing:
```bash
docker compose up postgres
cd backend && mvn spring-boot:run
```

Run the tests (Docker must be running — `DataMartQueryServiceIT` spins up its
own Postgres container via Testcontainers, separate from the compose one):
```bash
cd backend && mvn test
```

## Repo layout (grows as we go)

```
agentic-analytics/
├── docker-compose.yaml
├── README.md
└── backend/
    ├── Dockerfile
    ├── pom.xml
    └── src/
        ├── main/java/com/example/agenticanalytics/
        │   ├── AgenticAnalyticsApplication.java
        │   ├── web/HealthController.java
        │   └── datamart/
        │       ├── QueryGuard.java
        │       └── DataMartQueryService.java
        ├── main/resources/
        │   ├── application.yml
        │   └── db-init/01_init_datamart.sql
        └── test/java/com/example/agenticanalytics/
            ├── AgenticAnalyticsApplicationTests.java
            ├── web/HealthControllerTest.java
            └── datamart/
                ├── QueryGuardTest.java
                └── DataMartQueryServiceIT.java
```
