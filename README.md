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
- [ ] **Step 3** — Wire backend into docker-compose; verify `docker compose up` end to end.
- [ ] **Step 4** — Data mart schema + Flyway migration + seed data, with tests.
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
docker compose up postgres     # infra
cd backend
mvn test                       # runs HealthControllerTest + AgenticAnalyticsApplicationTests
mvn spring-boot:run             # then: curl localhost:8080/api/health
```

## Repo layout (grows as we go)

```
agentic-analytics/
├── docker-compose.yaml
├── README.md
└── backend/
    ├── pom.xml
    └── src/
        ├── main/java/com/example/agenticanalytics/
        │   ├── AgenticAnalyticsApplication.java
        │   └── web/HealthController.java
        ├── main/resources/application.yml
        └── test/java/com/example/agenticanalytics/
            ├── AgenticAnalyticsApplicationTests.java
            └── web/HealthControllerTest.java
```
