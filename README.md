# agentic-analytics

A natural-language analytics agent built with Spring AI: ask a plain-English
question, the agent grounds itself in a data dictionary (RAG, Step 6),
queries a Postgres data mart through tools exposed over MCP, and returns
an answer.

Built as a portfolio project, developed step by step, with each step
documented as we go. Structure and conventions are aligned with
[java-ai-reference-arch](https://github.com/pramalin/java-ai-reference-arch)
as of Step 5b — see that step's roadmap entry for what changed and why.

## Roadmap

- [x] **Step 1** — Empty repo, base compose file (Postgres only), this README.
- [x] **Step 2** — Spring Boot application, REST starter only: health endpoint + two tests.
  - *Fix applied:* Spring Boot 4 modularized starters/test-autoconfiguration.
    Using `spring-boot-starter-webmvc` (not `-web`) + `spring-boot-starter-webmvc-test`,
    and bumped to **4.0.1** — 4.0.0 shipped with `spring-boot-test-autoconfigure`
    missing the web package entirely (spring-projects/spring-boot#48286).
- [x] **Step 3** — Wire application into compose; verify `docker compose up` end to end.
  - *Note:* Dockerfile and `pom.xml` `java.version` aligned to Java 26 (matches dev machine).
- [x] **Step 4** — Data mart schema + seed data, with tests.
  - *Design choice:* schema/seed data provisioned via a plain SQL script mounted
    into Postgres's `docker-entrypoint-initdb.d`, not an app-level migration
    framework — matches the pattern in
    [docker/compose-for-agents/langgraph](https://github.com/docker/compose-for-agents/tree/main/langgraph).
    (Considered Flyway; reasonable either way — this project's narrative fit
    the init-script approach better.)
  - Unit test (`QueryGuardTest`) — fast, no DB.
  - Integration test (`DataMartQueryServiceIT`) — real Postgres via Testcontainers,
    includes a regression check for a status/decline_reason seed-data bug we
    hit and fixed in an earlier draft of this project.
- [x] **Step 5** — Spring AI: chat model, ChatClient, first tool-calling agent.
  - `AgentTools` (`@Tool`-annotated) let the agent call `listTables` /
    `describeTable` / `runQuery` in-process. *(Superseded by Step 5b below —
    kept in the codebase as a working example of the simpler alternative.)*
  - *Gotcha hit:* a missing/blank `ANTHROPIC_API_KEY` doesn't fail quietly —
    Spring AI's Anthropic autoconfiguration throws at bean-creation time
    (`Assert.hasText`), which would break full-context tests for anyone
    running `mvn test` without a real key. Fixed with a placeholder key via
    `@SpringBootTest(properties = ...)` on tests that only check wiring.
- [x] **Step 5b** — Aligned with `java-ai-reference-arch`: structure, MCP
  Gateway, API shape.
  - `backend/` → `application/`; `docker-compose.yaml` → `compose.yaml` +
    `compose.anthropic.yaml` provider overlay (a future `compose.ollama.yaml`
    would add a provider without touching the base file); secrets via `.env`.
  - **Tools now come from a Docker MCP Gateway, not in-process `@Tool`
    methods.** The gateway's database-server connects as `mcp_reader`, a
    genuinely read-only DB role — enforcement moved from app-layer
    (`QueryGuard`) to DB-layer, which can't be coded around. Full rationale
    and — importantly — an honest confidence note on what's verified vs.
    inferred in `mcp-config.yaml`/`compose.yaml`: [`docs/mcp-gateway.md`](docs/mcp-gateway.md).
  - `POST /api/chat` → `POST /api/questions`, `{message,reply}` →
    `{question,answer}`; added `/api/info`; custom `HealthController` removed
    in favor of Spring Actuator (`/actuator/health`, `/actuator/health/readiness`).
- [x] **Step 5c** — Local Ollama option (fallback path), no API key or cloud cost.
  - `compose.ollama.yaml` overlay, parallel to `compose.anthropic.yaml`.
    Three model starters end up on the classpath (Step 5d added a fourth
    provider), so exactly one is selected via **`spring.ai.model.chat`**
    (values `anthropic` / `openai` / `ollama`) — Spring AI 2.0's actual
    multi-provider selector, set via `AI_MODEL_CHAT` per overlay.
  - *Fix applied:* first attempt used `spring.ai.<provider>.chat.enabled`
    toggles, based on Spring AI 1.x docs — wrong for 2.0.1. Every provider
    autoconfig actually keys off `spring.ai.model.chat` with
    `matchIfMissing=true`, so an unset/wrong property silently activated
    multiple providers at once (`ChatClientAutoConfiguration` then fails:
    "expected single matching bean but found N"). Confirmed by reading the
    actual Spring Boot condition evaluation report from a failed test run,
    not by searching docs a second time.
  - Model pulled automatically on first startup
    (`spring.ai.ollama.init.pull-model-strategy: when_missing`) — no
    separate `ollama pull` step or init container.
  - Default model `llama3.2` — chosen specifically because it supports tool
    calling, which this agent depends on; not every small local model does.
- [x] **Step 5d** — Docker Model Runner (now the primary local path).
  - What Step 5c actually should have been: the model itself now starts
    automatically as part of `docker compose up` — no `docker model run` (or
    `ollama serve`) left running in a separate terminal session.
  - `compose.docker-model-runner.yaml` uses Compose's native `models:`
    top-level element (Compose v2.38+), which starts the model runner and
    injects `LLM_URL` / `LLM_MODEL` into the `application` container
    automatically — nothing to wire by hand.
  - Docker Model Runner speaks an **OpenAI-compatible** API, not Ollama's —
    so this path uses `spring-ai-starter-model-openai` pointed at the
    runner, not the Ollama starter. `spring.ai.model.chat=openai` selects
    it (confusing name, given it's not OpenAI's actual cloud API — that's
    just which Spring AI client the wire protocol matches).
  - `compose.ollama.yaml` (Step 5c) kept as a documented fallback — Model
    Runner needs Docker Desktop or a recent Docker Engine build; not
    guaranteed on every platform, and I couldn't verify it against this
    project's actual dev machine.
  - *Confidence note, explicitly:* the Compose `models:` mechanism and the
    exact env var names it injects, plus the Spring AI OpenAI-client
    base-url Model Runner expects, are things I verified against Docker's
    and Spring AI's docs but could not run myself. Full troubleshooting
    steps are in `compose.docker-model-runner.yaml`'s own comments.
- [x] **Step 5e** — MCP gateway config corrected against real files from
  `java-ai-reference-arch`.
  - Step 5b's `mcp-gateway` service and `mcp-config.yaml` were built from
    Docker's *docs* without being able to run them, and got two things
    wrong: `--servers=postgres` should have been `--servers=database-server`,
    and `mcp-config.yaml`'s schema was a guessed host/port/user/password
    block instead of the real single `database_url` (SQLAlchemy/asyncpg-style
    DSN) key. Both fixed by copying and adapting real, working
    `compose.yaml`/`mcp-config.yaml` files from that repo, not by guessing
    again.
  - Also adopted from those files: `use_api_socket: true` (cleaner than the
    raw `/var/run/docker.sock` bind mount used before), `--allow-unauthenticated`
    (the gateway requires auth by default otherwise), an explicit
    `--tools=execute_sql,list_tables,describe_table` allowlist, and a real
    healthcheck against `/sse` — `application`'s dependency on `mcp-gateway`
    upgraded from `service_started` to `service_healthy` accordingly.
  - Updated `docs/mcp-gateway.md` to reflect verified-not-guessed status.
- [x] **Step 5f** — Fixed a runtime crash: unrelated to the MCP gateway
  work, but found by actually running `docker compose up` with it.
  `spring-ai-starter-model-ollama`'s **embedding** autoconfiguration (a
  separate class from its chat one) isn't gated by `spring.ai.model.chat`
  at all — it has its own `spring.ai.model.embedding` selector, also
  `matchIfMissing=true`. Worse than the chat case: it calls the Ollama API
  *at bean-creation time* to check model availability, so an unreachable
  `localhost:11434` crashed the whole app on startup — not a silent
  no-op, and not specific to the Docker Model Runner overlay (would have
  broken `compose.anthropic.yaml` too). Fixed with
  `spring.ai.model.embedding: none` — verified as the documented disable
  value (spring-projects/spring-ai#4240), not another guess. We don't need
  embeddings until Step 6 anyway.
- [x] **Step 5g** — First real end-to-end test surfaced a genuine model
  reliability gap, not a plumbing bug.
  - Full stack verified working: MCP client → gateway → third-party
    `database-server` → Postgres → error → back through the agent, with the
    agent explaining the failure honestly rather than fabricating an answer
    (exactly what the system prompt asked for).
  - The actual failure: `ai/llama3.2` via Docker Model Runner called
    `execute_sql` directly, skipping `list_tables`/`describe_table`
    entirely, and produced SQL referencing a table that doesn't exist
    (`declined_transactions` — we have `transaction` with a `status`
    column) using MySQL interval syntax instead of Postgres's. This is the
    specific local-model tool-calling discipline gap flagged as an open
    question back in Step 5c/5d.
  - *Fix attempted:* rewrote the system prompt from advisory ("always call
    X before Y") to an explicit numbered, mandatory sequence, plus a
    Postgres-vs-MySQL syntax reminder. Not yet re-verified against a live
    run — smaller/quantized local models don't always respond to stricter
    prompting as reliably as larger ones; if this doesn't fully fix it,
    the next real lever is a bigger/less-quantized model tag, not more
    prompt tuning. Worth testing the same question against
    `compose.anthropic.yaml` too, as a baseline for how much of this gap is
    model capability vs. prompt engineering.
- [x] **Step 5h** — Real OpenAI cloud overlay, plus better error handling.
  - `compose.openai.yaml`, modeled on `java-ai-reference-arch`'s overlay of
    the same name — for testing with a more capable hosted model
    (`gpt-4o` by default) than either local option provides.
  - Real cloud OpenAI and Docker Model Runner both need
    `spring.ai.model.chat=openai` (same Spring AI selector — Model Runner
    speaks the OpenAI-compatible API), but need different `base-url`/
    `api-key`/model-name defaults. Rather than cram conflicting defaults
    into one block, DMR's overrides moved into a Spring profile
    (`application-docker-model-runner.yml`, activated by
    `SPRING_PROFILES_ACTIVE=docker-model-runner` in that compose overlay);
    real cloud OpenAI is now the *default* behavior of `spring.ai.openai.*`
    in the base `application.yml` — no base-url override, so Spring AI's
    own `api.openai.com` default applies, and a real key is required with
    no fallback (fails fast if missing, same as Anthropic).
  - `QuestionController` now has an `@ExceptionHandler` — provider auth
    failures, rate limits, etc. return a readable `502` with the actual
    error message instead of a bare framework `500` (this is what surfaced
    the confusing empty 500 body when testing Anthropic with an invalid
    key). New test: `askEndpointReturnsReadableErrorWhenProviderCallFails`.
  - Clarified in `.env.example`: an OpenAI API key (platform.openai.com)
    is a separate account/billing from a ChatGPT Plus/Pro subscription —
    same distinction as Claude Pro vs. the Anthropic API that caused
    confusion in Step 5g's testing.
- [x] **Step 5i** — Found a real correctness bug via testing with `gpt-4o`,
  not a plumbing issue: concrete motivation for Step 6.
  - "How many total transactions are declined, across all time?" came back
    "There are no declined transactions" — looked like a legitimate empty
    result, wasn't. The generated SQL used `status = 'declined'`
    (lowercase); the seed data stores `'DECLINED'` (uppercase). Postgres
    string comparison is case-sensitive, so the filter silently matched
    zero rows. A separate attempt in the same session also failed outright
    with `INTERVAL '1 quarter'` — not valid Postgres syntax — before the
    model self-corrected on retry.
  - Root cause: `describe_table` only returns column names and types, never
    the actual values stored in a column — nothing tells the model that
    `status` is an uppercase enum-style field. This is precisely the gap
    Step 6's RAG-over-schema-docs is meant to close, discovered empirically
    rather than just asserted as a reason to build it.
  - *Interim fix, ahead of Step 6:* added explicit guidance to the system
    prompt — sample/check distinct values before filtering on a text
    column whose value set isn't obvious, treat an unexpectedly empty
    result as a signal to double-check filter values rather than accept it
    at face value, and a note that Postgres has no `quarter` INTERVAL unit.
    Not yet re-verified against a live run.
- [x] **Step 5j** — Same bug recurred with an unchanged, verified-correct
  codebase — confirmed as genuine LLM non-determinism, not a deployment
  issue, and a stronger case for Step 6 than Step 5i alone was.
  - After a local git mixup (deleted and re-cloned the repo), the exact
    same "0 declined transactions" wrong answer came back. Traced it all
    the way down: single source file, `grep` confirmed the Step 5i fix was
    present, Docker's builder cache was legitimately valid (content
    genuinely unchanged) — not a stale build. Same prompt, same code,
    different outcome on a re-run. That's what it looks like when GPT-4o
    (not run at temperature 0) treats "check distinct values on a column
    whose value set isn't obvious" as a judgment call and decides `status`
    is obvious enough to skip checking.
  - *Fix:* rewrote the instruction to remove the judgment call entirely —
    "before EVERY WHERE clause on a text column, run SELECT DISTINCT
    first, not optional, not a judgment call" — rather than leaving
    "obvious" for the model to decide. Whether this is *reliably* better
    or just shifts where the model's judgment sneaks back in is genuinely
    unverified — advisory prompt instructions are probabilistic nudges,
    not guarantees, no matter how forcefully worded. The durable fix is
    Step 6: hand the model the actual value convention as grounded
    context, so it never has to decide whether to double-check at all.
- [x] **Step 6** — RAG: schema docs into pgvector, retrieval advisor.
  - `SchemaDocIngestor` embeds `schema-docs/*.md` into pgvector on startup
    using a local ONNX embedding model (`spring-ai-starter-model-transformers`,
    `all-MiniLM-L6-v2` bundled in the jar — no download, no API cost,
    deliberately independent of which chat provider is active).
  - `ChatClientConfig` adds a `RetrievalAugmentationAdvisor` ahead of the
    memory advisor — retrieved schema facts get injected into every request.
  - `transaction.md` explicitly documents the uppercase `status` convention
    that caused the Step 5i/5j bug — this is the direct fix for that bug,
    not just a generic RAG demo. Full rationale: [`docs/rag.md`](docs/rag.md).
  - `spring.ai.model.embedding` changed from `none` (Step 5f) to
    `transformers` — the Ollama-embedding eager-connect crash that Step 5f
    worked around doesn't apply here, since transformers embeddings never
    make a network call.
  - New test: `SchemaDocIngestorIT` — confirms ingestion actually runs and
    that a status-casing question retrieves the doc that documents it, not
    just that the app doesn't crash on startup.
  - `AgenticAnalyticsApplicationTests` now uses Testcontainers (previously
    it didn't need a real database at all; the new `VectorStore` bean does).
- [x] **Step 6a** — Removed `AgentTools`, `QueryGuard`, and
  `DataMartQueryService` as dead code.
  - Prompted by a direct question about whether `QueryGuard` was still
    doing anything, given the Step 5b MCP pivot moved real enforcement to
    the `mcp_reader` DB role. Answer: no — nothing had called `AgentTools`
    (and therefore `QueryGuard`/`DataMartQueryService`, which it was the
    only caller of) since Step 5b. Kept as a "documented fallback" for a
    few steps, but a fallback nobody had ever exercised is just clutter
    with extra steps.
  - `docs/query-guard.md` removed with it (the code it documented no longer
    exists); `docs/mcp-gateway.md` updated to stop describing "two layers
    of enforcement" — there's one now, the DB role, on purpose.
  - One thing preserved deliberately rather than deleted along with
    everything else: the `declinedRowsAlwaysHaveADeclineReason` regression
    check from the removed `DataMartQueryServiceIT` — a real bug in
    `db-init/01_init_datamart.sql` from Step 4 (status and decline_reason
    drawn from independent `RANDOM()` calls) that this test would have
    caught. Moved to a new, smaller `SeedDataIT` that only checks the seed
    data's self-consistency via plain `JdbcTemplate`, with no dependency on
    the application-layer class that got removed.
- [ ] **Step 7** — React frontend (or Angular console, matching the
  reference repo — TBD when we get there).
- [ ] **Step 8** — Both frontends switchable via compose profiles, if we
  end up building both.

This list will get more granular as we go.

## Running what exists so far

Three ways to run this, depending on what you want:

### Option A — fully local, no API key, no cloud cost, model starts automatically (Docker Model Runner)

The recommended local option. Just Docker — nothing to sign up for, pay
for, or start manually in another terminal.

```bash
docker compose down -v --remove-orphans      # clean slate; --remove-orphans clears containers left over from switching overlays (e.g. ollama, if you previously used a different one)
docker compose -f compose.yaml -f compose.docker-model-runner.yaml up --build
```

Needs Docker Model Runner support on your machine — check first with
`docker model version`. If that's not a recognized command, use Option A2
below instead.

First run pulls the model automatically as part of `docker compose up` (no
`docker model run` or `ollama serve` left running separately) — several
minutes depending on your connection. Slower to respond than a cloud model,
especially without a GPU; that's expected.

### Option A2 — fully local fallback, no API key (plain Ollama container)

Use this if `docker model version` isn't available on your machine.

```bash
docker compose down -v --remove-orphans
docker compose -f compose.yaml -f compose.ollama.yaml up --build
```

Same idea as Option A, minus Compose's native model management — Ollama
runs as an ordinary container instead.

### Option B — Anthropic (Claude)

Requires an API key from **console.anthropic.com** — a separate account and
billing from a Claude Pro/Max subscription; Pro doesn't include API access.

```bash
cp .env.example .env
# edit .env, set ANTHROPIC_API_KEY

docker compose down -v --remove-orphans
docker compose -f compose.yaml -f compose.anthropic.yaml up --build
```

### Option C — OpenAI (cloud, more capable than the local options)

Requires an API key from **platform.openai.com** — same separation as
above: a ChatGPT Plus/Pro subscription is not the same account or billing
as API access.

```bash
cp .env.example .env
# edit .env, set OPENAI_API_KEY

docker compose down -v --remove-orphans
docker compose -f compose.yaml -f compose.openai.yaml up --build
```

### Whichever option, once it's up

```bash
curl http://localhost:8080/api/info
curl http://localhost:8080/actuator/health

curl -X POST localhost:8080/api/questions \
  -H "Content-Type: application/json" \
  -d '{"question": "What were declined transactions by region last quarter?"}'
```

If the agent responds but says it has no tools available, the MCP gateway
likely isn't wired up correctly on your machine yet — see
[`docs/mcp-gateway.md`](docs/mcp-gateway.md) for the most probable cause and
how to check it; that piece carries an explicit confidence caveat since I
couldn't run Docker myself to verify it end to end.

Or without Docker Compose, for faster local iteration on the application
alone (no MCP gateway): the base `application.yml` now defaults to real
cloud OpenAI (Step 5h), which requires `OPENAI_API_KEY` with no fallback —
so plain `mvn spring-boot:run` needs that env var set (or activate the
`docker-model-runner` profile instead, which doesn't). Useful for working
on the REST/data-mart layer, not for exercising the agent without one of
the two:
```bash
docker compose up postgres
cd application
OPENAI_API_KEY=sk-... mvn spring-boot:run
# or, to skip needing any key:
SPRING_PROFILES_ACTIVE=docker-model-runner mvn spring-boot:run
```

Run the tests (Docker must be running — several test classes spin up their
own Postgres container via Testcontainers; no real API key, local model, or
MCP gateway is needed — all disabled/placeholder'd for the full-context tests):
```bash
cd application && mvn test
```

## Docs

- [`docs/mcp-gateway.md`](docs/mcp-gateway.md) — why tools moved to the MCP
  gateway, what's verified vs. inferred, and troubleshooting steps.
- [`docs/rag.md`](docs/rag.md) — why schema-doc RAG exists (directly tied to
  the Step 5i/5j status-casing bug), how it works, and what it doesn't solve.

## Repo layout (grows as we go)

```
agentic-analytics/
├── compose.yaml               # base: postgres, mcp-gateway, application
├── compose.anthropic.yaml     # provider overlay: Anthropic API key/model
├── compose.docker-model-runner.yaml  # provider overlay: local, auto-started model
├── compose.ollama.yaml        # provider overlay: local Ollama fallback, no API key
├── compose.openai.yaml        # provider overlay: real OpenAI cloud API key/model
├── mcp-config.yaml            # MCP gateway's database-server connection config
├── .env.example
├── README.md
├── docs/
│   ├── mcp-gateway.md
│   └── rag.md
└── application/
    ├── Dockerfile
    ├── pom.xml
    └── src/
        ├── main/java/com/example/agenticanalytics/
        │   ├── AgenticAnalyticsApplication.java
        │   ├── web/
        │   │   ├── ApplicationInfoController.java
        │   │   └── QuestionController.java
        │   ├── config/ChatClientConfig.java
        │   └── rag/SchemaDocIngestor.java
        ├── main/resources/
        │   ├── application.yml
        │   ├── application-docker-model-runner.yml
        │   ├── db-init/01_init_datamart.sql
        │   └── schema-docs/
        │       ├── transaction.md
        │       └── merchant_and_region.md
        └── test/java/com/example/agenticanalytics/
            ├── AgenticAnalyticsApplicationTests.java
            ├── web/QuestionControllerTest.java
            ├── rag/SchemaDocIngestorIT.java
            └── seed/SeedDataIT.java
```
