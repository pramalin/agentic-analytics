# Applying this to agentic-analytics

Copy into the root of your `agentic-analytics` checkout:

```
agentic-analytics/
  scripts/
    e2e-test.sh
  .github/
    workflows/
      e2e-test.yml
```

## Try it locally first

```bash
cd ~/work/agentic-analytics
chmod +x scripts/e2e-test.sh
./scripts/e2e-test.sh
```

Requires `docker`, `docker compose` (v2 plugin), and `jq` on your PATH --
the script checks for all three up front and fails fast with a clear
message if any are missing, rather than partway through.

It brings the whole real stack up from a clean slate (`down -v` first, in
case a prior run didn't tear down cleanly), waits for `application`'s
actuator readiness endpoint, asks "How many merchants do we have?",
and asserts on:

- the final answer matches the expected shape (`There are N merchants.`
  -- pattern-matched, not a hardcoded count, so it survives the seed
  data changing over time)
- exactly 3 tool calls, in order: `list_tables`, `describe_table`,
  `execute_sql`
- `describe_table` was called on the `merchant` table
- `execute_sql`'s query was semantically `select count(*) from merchant`
  (case/whitespace-tolerant, not an exact string match)

On failure it prints the full response AND llmsim's call journal before
exiting non-zero, so a CI failure has enough context to debug without
needing to reproduce it locally first. It always tears the stack down on
exit, pass or fail.

## Then let CI run it automatically

`e2e-test.yml` runs the same script on every push to `main` and every PR.
GitHub-hosted runners already have Docker, the compose v2 plugin, and
`jq` preinstalled, and `llmsim-build` is a public image, so there's
nothing else to configure -- commit both files and push, and the next
push or PR triggers it.

## Why a shell script instead of a JUnit `IT` test

`mcp-gateway` dynamically starts its own sibling containers (the actual
Postgres MCP tool server) via a Docker-socket mount
(`use_api_socket: true`) -- it's not a fixed, pre-declared container the
way `SeedDataIT`'s Postgres container is. Re-deriving that orchestration
by hand in Testcontainers risked getting the socket-mount/lifecycle
details wrong in ways unrelated to what's actually being tested, on top
of a currently-open regression in `ComposeContainer` for the
`testcontainers-bom` version this project is pinned to
([testcontainers-java#11415](https://github.com/testcontainers/testcontainers-java/issues/11415)).
Driving the real, already-working `docker compose` CLI sidesteps both
problems entirely -- it exercises the exact same setup a real user runs,
with nothing hand-reimplemented.
