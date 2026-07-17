# MCP Gateway

As of this change, the agent's tools (`list_tables`, `describe_table`,
`execute_sql`) are sourced from a Docker MCP Gateway container, not from an
in-process `@Tool` class. This mirrors
[java-ai-reference-arch](https://github.com/pramalin/java-ai-reference-arch),
which itself models the pattern from
[docker/compose-for-agents/langgraph](https://github.com/docker/compose-for-agents/tree/main/langgraph).

## Why this instead of Step 5's in-process tools

The in-process approach (`AgentTools`, still in the codebase, just no longer
wired into `ChatClientConfig`) is simpler and has no extra moving parts. The
gateway approach trades that simplicity for:

- **Tool reuse across clients.** Any MCP client — not just this Spring app
  — can connect to the gateway and get the same database tools. That's the
  actual point of MCP: a shared surface, not an app-specific abstraction.
- **Enforcement that can't be coded around.** `QueryGuard` (Step 4) is an
  application-layer check — technically, a bug in this codebase could bypass
  it. The gateway's database-server connects as `mcp_reader`, a genuinely
  read-only Postgres role (see `db-init/01_init_datamart.sql`). No SQL that
  role runs can write, full stop, regardless of what any client sends.
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
   not a bug.
5. If you'd rather not debug the gateway right now, `AgentTools` still works
   as an in-process fallback — swap `ChatClientConfig`'s tool source back to
   `.defaultTools(agentTools)` (Step 5's original approach) to unblock
   yourself, then come back to the gateway later.

## Read-only enforcement, now in two places

`QueryGuard` still exists and is still tested (`QueryGuardTest`,
`DataMartQueryServiceIT`), but it's no longer in the live request path for
the primary agent flow — the gateway's `database-server` talks to Postgres
directly, bypassing this Spring app entirely for tool calls. The DB-level
`mcp_reader` role is what actually enforces read-only access now. Keeping
`QueryGuard` around is still worthwhile: it's a second, independent layer if
this project ever adds a tool that goes through the Spring app again, and
it's a reasonable thing to point to in an interview as "I understand
defense in depth, and I know which layer is actually doing the enforcing
in the current architecture."
