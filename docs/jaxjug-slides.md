# Building an Agentic AI Application with Spring AI
### A debugging-driven tour of MCP, RAG, and multi-provider tool-calling agents

*JaxJUG — 20 min talk + 10 min demo*

---

## Who / What / Why

- Lead Java engineer exploring agentic AI as a hands-on portfolio project
- Goal: build something real enough to demonstrate judgment, not just a chatbot demo
- **The actual thesis of this talk:** the interesting engineering in agentic systems
  isn't wiring an LLM to an API — it's everything that goes wrong once the model
  starts making its own decisions, and how you find and fix it
- Every bug in this talk is a real bug I hit, not a hypothetical

---

## What we're building

A natural-language analytics assistant over a payments/transactions data mart:

> "How many total transactions are declined, across all time?"
> → *"279 transactions have been declined across all time."*

No SQL from the user. The agent:
1. Discovers the schema itself (`list_tables`, `describe_table`)
2. Writes and runs its own query (`execute_sql`)
3. Explains the result in plain English

Read-only, by construction, not by promise — more on that later.

---

## Architecture overview

```mermaid
flowchart LR
    User["React UI"] -->|"POST /api/questions"| App["Spring Boot\n(Spring AI ChatClient)"]
    App -->|MCP / SSE| Gateway["Docker MCP Gateway"]
    Gateway -->|stdio| Tool["Third-party MCP\ndatabase-server"]
    Tool -->|"read-only role"| DB[("Postgres\ndata mart")]
    App -->|"RAG lookup"| Vector[("pgvector\nschema docs")]
    App <-->|"chat + tools"| Model["Anthropic / OpenAI /\nlocal model"]
```

Four moving pieces most tutorials skip: a **real MCP gateway** (not a toy),
**RAG grounding**, **three swappable model providers**, and a frontend that
actually talks to all of it.

---

## Tech stack

- **Backend:** Spring Boot 4, Spring AI 2.0, Java 26
- **Agent:** `ChatClient` + advisor chain (memory, tool-calling, RAG)
- **Tools:** Docker MCP Gateway → third-party `mcp-db-server` (Python) → Postgres
- **Data:** Postgres + pgvector, seeded transaction/merchant/region data
- **Models:** Anthropic (Claude), OpenAI (cloud), Docker Model Runner / Ollama (local)
- **Frontend:** React + TypeScript, Vite, markdown-rendered answers
- **Infra:** Docker Compose, one base file + provider-specific overlays

---

## Spring AI's ChatClient, briefly

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder,
                              ChatMemory chatMemory,
                              ObjectProvider<ToolCallbackProvider> mcpTools) {
    ToolCallback[] toolCallbacks = mcpTools.stream()
            .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
            .toArray(ToolCallback[]::new);

    return builder
            .defaultSystem(SYSTEM_PROMPT)
            .defaultTools((Object[]) toolCallbacks)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
}
```

`ObjectProvider` here is deliberate: if the MCP client can't connect, the app
still starts — degraded (no tools), not crashed.

---

## Why MCP, not just "call a function"

Spring AI has native `@Tool` calling built in — no gateway required.
So why add one?

- **Tool reuse across clients** — not just this app; any MCP client can
  connect to the same gateway
- **Enforcement that can't be coded around** — an app-layer check is only
  as good as the code path that calls it
- **Matches how this actually gets built at a company** — a shared
  data-tooling team exposes tools once; product teams' agents consume them

Trade-off: more moving parts, another container, another thing to debug.

---

## The MCP gateway, for real

```yaml
mcp-gateway:
  image: docker/mcp-gateway:latest
  use_api_socket: true
  command:
    - --transport=sse
    - --allow-unauthenticated
    - --config=/mcp-config.yaml
    - --servers=database-server
    - --tools=execute_sql,list_tables,describe_table
```

```yaml
# mcp-config.yaml
database-server:
  database_url: postgresql+asyncpg://mcp_reader:***@postgres:5432/datamart
```

**Story:** I built this from Docker's *documentation* first — got the server
name wrong (`postgres` instead of `database-server`) and the config schema
wrong (guessed a structured block; the real format is one DSN string).
Fixed by pulling real, working files from a reference project instead of
guessing twice. **Docs describe the general case; a working example shows
you the one that's actually true for a given version.**

---

## Read-only, enforced where it can't be bypassed

```sql
CREATE ROLE mcp_reader WITH LOGIN PASSWORD '***';
GRANT CONNECT ON DATABASE datamart TO mcp_reader;
GRANT USAGE ON SCHEMA public TO mcp_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO mcp_reader;
```

Earlier version of this project had an **application-layer** SQL guard
(`QueryGuard`) — a regex tripwire rejecting non-`SELECT` statements.

Once the MCP gateway path went live, that guard was never actually in the
request path again. I found this by asking myself directly: *"is this still
doing anything?"* Answer: no. Deleted it — dead code, not defense in depth,
once nothing calls it.

**Lesson:** revisit early architectural decisions after later ones change
the actual request path. Code that *used to* matter can become theater.

---

## RAG: why it exists (a real bug, not a feature checkbox)

Asked: *"How many total transactions are declined, across all time?"*

Got: *"There are no declined transactions recorded."*

That's not an empty result — it's a **bug**. The agent generated:

```sql
WHERE status = 'declined'
```

The column actually stores `'DECLINED'` (uppercase). Postgres string
comparison is case-sensitive. Silent zero-row match, confidently reported
as fact.

---

## The fix that didn't fully work

**Attempt 1 — prompt engineering:**
> "Before every WHERE clause filtering on a text column, run SELECT DISTINCT
> first. Not optional, not a judgment call."

Rebuilt. Retested. **Same bug reappeared on a later run** — unchanged,
verified-correct prompt, different outcome. GPT-4o isn't run at
temperature 0. An instruction is a *nudge*, not a guarantee.

**Attempt 2 — RAG:**
Embed the actual fact (`status` values are uppercase) into a vector store;
retrieve and inject it into context on every request, so the model never
has to *remember* to check — the fact is just there.

---

## RAG broke something else

Four questions in one conversation, with RAG enabled:

| Turn | Result |
|---|---|
| Q1 | ✅ Correct, real tool calls, ~17s |
| Q2 | ❌ "I don't know" — instantly, 0.8s, **no tool calls at all** |
| Q3 | ❌ Same |
| Q4 | ❌ Same |

100% reproducible. Every time. Regardless of which questions.

---

## Finding it — not guessing, reading the actual logs

```
Turn 1: RAG fires → list_tables → RAG fires → describe_table →
        RAG fires → execute_sql → RAG fires → [answer]
Turn 2: RAG fires → [answer]        ← nothing else. No tool call. At all.
```

Ruled out with evidence, not assumption:
- **Not memory truncation** — raised the window 20→100 messages, no change
- **Not dropped tools** — DEBUG logs showed tools were still offered

**Isolating test:** commented out the RAG advisor. Four questions, four
correct answers, every time.

**Conclusion:** RAG + multi-turn tool-call history in memory interact badly —
best guess is the model reads retrieved *documentation* alongside a prior
turn's tool-call *results* and concludes the question is already answered.

---

## What I did about it

Disabled RAG. Documented the finding — not swept under the rug:

> **Status: currently disabled.** Broke multi-turn tool-calling — see
> below. Until fixed, the only defense against the status-casing bug is
> the (known-unreliable) prompt instruction.

This is the actual point of the talk: **a real system has real, sometimes
contradictory constraints, and the honest move is to document the trade-off
and ship, not to hide the seam.**

Next steps written down, not solved tonight: scope RAG to turn 1 only, or
strip tool-call transcripts from memory before they accumulate.

---

## Three model providers, one selector property

```yaml
spring:
  ai:
    model:
      chat: ${AI_MODEL_CHAT:openai}   # anthropic | openai | ollama
```

- `compose.anthropic.yaml` — Claude, cloud
- `compose.openai.yaml` — GPT-4o, cloud, most reliable in testing
- `compose.docker-model-runner.yaml` / `compose.ollama.yaml` — fully local,
  no API key, no cost

**Finding:** local models (`llama3.2`) skipped schema-discovery tools and
hallucinated table names outright. Same prompt, same tools, worse
tool-calling discipline. Real, demonstrable reliability gap between local
and cloud models for agentic tasks — not a theoretical concern.

---

## Docker Compose as a local-dev tool, not just deployment

One command brings up the whole stack — Postgres, MCP gateway, Spring app,
React frontend — wired together, healthchecked, in the right start order:

```bash
docker compose -f compose.yaml -f compose.openai.yaml up --build
```

The split that made this actually pleasant to work with:

- **`compose.yaml`** — everything provider-agnostic: Postgres, the MCP
  gateway, the app, the frontend
- **`compose.<provider>.yaml`** — just the env vars/services that differ:
  `compose.anthropic.yaml`, `compose.openai.yaml`,
  `compose.docker-model-runner.yaml`, `compose.ollama.yaml`

Switch providers without touching shared config, and without four
half-duplicated compose files drifting out of sync with each other.

---

## Compose in practice — the parts that actually mattered

```yaml
depends_on:
  mcp-gateway:
    condition: service_healthy   # not just "container started"
```

- Real `healthcheck:` blocks + `condition: service_healthy` — the app
  container used to start racing the MCP gateway before it was actually
  ready to serve tools. "Started" and "ready" are different things.
- `docker compose down -v --remove-orphans` became a standing ritual, not
  an afterthought — a stale Postgres volume with an old schema, or an
  orphaned container from a previous provider overlay, produced confusing
  failures that looked like application bugs and weren't.
- **Testcontainers ≠ Compose, on purpose:** ephemeral, isolated containers
  for automated tests; Compose for the actual full-stack local run. Same
  Postgres image, two different jobs.
- Disk space is a real local-dev cost: repeated `--build` cycles pile up
  images and build cache. `docker system df` / `docker builder prune -af`
  became genuine maintenance, not hypothetical advice.

---

## Local embeddings: another upstream surprise

Local ONNX embeddings (no API cost) hit a **documented Spring AI bug**
([spring-ai#1391](https://github.com/spring-projects/spring-ai/issues/1391)):
the default model-fetch URL serves a broken Git-LFS pointer (133 bytes,
not the real ~90MB file) instead of the actual model.

```
content-length: 133   ← should be tens of millions
```

Switched to OpenAI's `text-embedding-3-small`. Trade-off: couples RAG
(when re-enabled) to the OpenAI overlay specifically. Documented, not
hidden.

---

## The frontend, briefly

React + TypeScript, markdown-rendered answers (tables render as real
tables, not text blobs) — three full design iterations based on direct
visual feedback, landing on a clean cool-gray/teal "enterprise data tool"
look rather than a generic chat-bubble UI.

Real bugs here too: a disabled-button contrast bug, a CORS misconfiguration,
a `erasableSyntaxOnly` TypeScript error from a newer compiler than I'd
assumed. Same theme as the backend: verify, don't assume.

---

## Lessons for the room

1. **Docs describe the general case. A working example shows you the one
   that's true for your version.** (MCP gateway, twice.)
2. **An LLM instruction is a nudge, not a guarantee** — even when it says
   "not optional." Build the guarantee somewhere deterministic if you need one.
3. **Revisit old architecture decisions when new ones change the request
   path.** Dead defenses aren't defense in depth.
4. **A confidently wrong answer is more dangerous than an error.** Silent
   zero-row matches don't look like bugs.
5. **When two features fight each other, disabling one and documenting why
   is a legitimate engineering decision** — not a failure to ship.
6. **"Container started" and "container ready" are different claims.**
   Healthchecks + `condition: service_healthy` exist because `depends_on`
   alone doesn't know the difference.

---

## Demo (10 min)

- Live question against the real stack (Postgres + MCP gateway + GPT-4o)
- Show the MCP gateway logs mid-request — tool calls happening live
- Ask something that triggers a markdown table
- Show the multi-turn regression live, if time allows — the actual bug,
  not a slide about it

---

## Questions?

- Code: `github.com/pramalin/agentic-analytics`
- Docs referenced tonight: `docs/mcp-gateway.md`, `docs/rag.md`
- Happy to talk about anything skipped — Docker Model Runner, the read-only
  role design, the React design iterations, testing strategy (Testcontainers
  throughout), or the frontend build process
