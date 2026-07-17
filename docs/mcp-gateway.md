# MCP Gateway

As of this change, the agent's tools (`list_tables`, `describe_table`,
`execute_sql`) are sourced from a Docker MCP Gateway container, not from an
in-process `@Tool` class. This mirrors
[java-ai-reference-arch](https://github.com/pramalin/java-ai-reference-arch),
which itself models the pattern from
[docker/compose-for-agents/langgraph](https://github.com/docker/compose-for-agents/tree/main/langgraph).

## Why this instead of in-process tools

Step 5 originally had two tool sources: an in-process `AgentTools` class
(`@Tool`-annotated, calling the data mart directly via JDBC) and this MCP
gateway. `AgentTools` — along with `QueryGuard` and `DataMartQueryService`,
the app-layer read-only check and JDBC service it depended on — was removed
in Step 6 once it was clearly established as dead code: nothing wired it
into `ChatClientConfig` after the Step 5b MCP pivot, and it added
maintenance surface without being reachable by any real request. If you're
reading this after that removal and wondering why there's no in-process
fallback anymore, that's why — it's not an oversight, it just stopped
earning its keep once the gateway path was proven out.

The gateway approach's actual advantages, for the record:

- **Tool reuse across clients.** Any MCP client — not just this Spring app
  — can connect to the gateway and get the same database tools. That's the
  actual point of MCP: a shared surface, not an app-specific abstraction.
- **Enforcement that can't be coded around.** An application-layer check
  like the removed `QueryGuard` is only as good as the code path that calls
  it — a bug in this codebase could theoretically bypass it. The gateway's
  `database-server` connects as `mcp_reader`, a genuinely read-only Postgres
  role (see `db-init/01_init_datamart.sql`). No SQL that role runs can
  write, full stop, regardless of what any client sends — enforcement lives
  in Postgres itself, not in application code.
- **A closer match to how this gets built at a company like the one in the
  original job posting this project is aimed at** — a shared data-tooling
  team would expose MCP tools once; individual product teams' agents would
  consume them, not reimplement query logic per-agent.

## What's verified vs. inferred

**Update:** `compose.yaml`'s `mcp-gateway` service and `mcp-config.yaml`
were originally built from Docker's documented patterns without being able
to run them — real risk of guessing something wrong, and it showed:
`--servers=postgres` (guessed) turned out to actually need to be
`--servers=database-server`, and `mcp-config.yaml`'s schema was a
structured host/port/user/password block (guessed) when the real format is
a single `database_url` key with a SQLAlchemy/asyncpg-style DSN. Both are
now fixed, copied and adapted from a working `compose.yaml`/`mcp-config.yaml`
pair pulled directly from
[java-ai-reference-arch](https://github.com/pramalin/java-ai-reference-arch),
not re-inferred. Also picked up from that same file: `use_api_socket: true`
(cleaner than the raw `/var/run/docker.sock` bind mount I'd used),
`--allow-unauthenticated` (needed for local dev — the gateway requires auth
by default otherwise and nothing here is configured to supply it), and an
explicit `--tools=execute_sql,list_tables,describe_table` allowlist.

Remaining lower-confidence surface, now much smaller: whether
`postgresql+asyncpg://` — a Python/SQLAlchemy DSN convention — behaves
identically when the underlying `database-server` MCP server is written in
something else, and whether every flag combination here still matches
whatever gateway image version you pull (`:latest`, so it can drift). If
`docker compose up` doesn't bring the gateway up cleanly, or the agent
reports it has no tools available, start here:

1. Check the `mcp-gateway` container logs first — `docker compose logs mcp-gateway`.
2. Confirm the gateway is actually healthy: `docker compose ps mcp-gateway`
   — its healthcheck hits `/sse` internally, so a container stuck
   "starting" means the SSE endpoint isn't responding yet or at all.
3. Confirm the gateway's SSE endpoint is reachable from the `application`
   container specifically (they share a Compose network, but the gateway's
   port isn't published to the host — you can't `curl localhost:8811` from
   your own machine, only from inside another container on the same network):
   `docker compose exec application curl -sv http://mcp-gateway:8811/sse`.
4. As a fallback while you sort out the gateway, `ChatClientConfig` is
   written so the app still starts with zero tools if the MCP client can't
   connect — you'd see the agent apologizing that it has no way to query
   the data mart, not a crash. That's a deliberate degrade-gracefully choice,
   not a bug. (There's no in-process tool fallback to swap in anymore —
   `AgentTools` was removed in Step 6 as dead code; see below.)

## Read-only enforcement lives in one place now, deliberately

Step 5 briefly had two layers: an application-layer `QueryGuard` (a regex
tripwire on generated SQL) and the DB-level `mcp_reader` role. `QueryGuard`,
`DataMartQueryService`, and the `AgentTools` that used them were removed in
Step 6 — they were never in the live request path after the Step 5b MCP
pivot (nothing called them), so keeping them around as an inert "second
layer" was more clutter than defense in depth. The DB-level `mcp_reader`
role is the actual, sole enforcement mechanism now: no SQL that role
executes can write, regardless of what generates it or which layer of this
codebase does or doesn't check it first. If a future tool ever goes through
the Spring app directly again (bypassing the gateway), an app-layer check
would be worth reintroducing at that point — not as standing insurance for
a code path that doesn't currently exist.
