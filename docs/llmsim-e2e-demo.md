# llmsim end-to-end demonstration

This demonstration runs `agentic-analytics` against a deterministic llmsim
script and leaves the stack running so the resulting model-call journal can be
inspected in the llmsim console.

It is separate from `scripts/e2e-test.sh`:

| Command | Purpose | Cleanup |
|---|---|---|
| `./scripts/e2e-test.sh` | Normal deterministic regression test and CI | Always tears down |
| `./scripts/e2e-demo.sh` | Interactive success-and-failure demonstration | Leaves the stack running |

## What remains real

The demonstration uses the production-shaped application path:

- Spring AI `ChatClient`
- MCP gateway
- database MCP server
- PostgreSQL data mart
- application tool tracing
- frontend applications

Only the external language model is replaced by llmsim.

## Demonstrated scenarios

### 1. Successful database workflow

The first question consumes four scripted model turns:

1. `list_tables`
2. `describe_table` for `merchant`
3. `execute_sql` with `select count(*) from merchant`
4. a final answer derived from the real SQL tool result

The demo checks the final answer, tool count, tool order, table argument, and SQL
shape.

### 2. Real tool/database failure

The second question also executes a complete tool-calling sequence, but the
script deliberately sends this SQL:

```sql
select count(*) from merchant_missing_for_llmsim_demo
```

That query travels through the real MCP gateway and reaches the real PostgreSQL
database. PostgreSQL rejects it because the relation does not exist.

The scripted final model turn acknowledges the tool error rather than inventing
a count. The demo verifies that:

- all three tools were invoked in the intended order;
- the deliberately missing table appears in `execute_sql` arguments;
- the trace contains a recognizable database error;
- the final answer reports that the query could not be completed.

This case illustrates an important diagnostic distinction: the model protocol
can work correctly while a tool invoked by the agent fails.

### 3. Deterministic script exhaustion

`AnalyticsDemoFlow` uses `Script.exactly` and contains exactly eight responses.
After the first two questions consume those responses, the demo submits a third
question.

No scripted response remains, so llmsim rejects the request deterministically.
The application should surface this as a non-successful HTTP response.

Spring AI may retry an upstream failure depending on application configuration.
For that reason, the demo accepts one or more overrun journal entries instead of
requiring one exact final call count.

## Install the overlay

Extract the overlay at the repository root:

```bash
tar -xzf agentic-analytics-e2e-demo-overlay.tar.gz
chmod +x scripts/e2e-demo.sh
```

The archive adds these files without replacing the normal CI flow:

```text
compose.llmsim-demo.yaml
llmsim/AnalyticsDemoFlow.scala
llmsim/Dockerfile.demo
scripts/e2e-demo.sh
docs/llmsim-e2e-demo.md
```

Review the changes before committing:

```bash
git status
git diff --no-index /dev/null scripts/e2e-demo.sh
```

## Run

From the `agentic-analytics` repository root:

```bash
./scripts/e2e-demo.sh
```

The command builds and starts:

```text
compose.yaml
+ compose.llmsim.yaml
+ compose.llmsim-demo.yaml
```

It then runs and verifies all three scenarios. On success it leaves the
containers running.

Open the llmsim diagnostic console:

```text
http://localhost:8089/_llmsim/console
```

Other useful addresses:

```text
Application UI:                  http://localhost:3000
Application engineering console: http://localhost:4200
Application health:              http://localhost:8080/actuator/health/readiness
```

The response payloads, journal, and any captured diagnostics are written to:

```text
artifacts/e2e-demo/
```

## What to inspect in the llmsim console

The journal should show:

- four calls for the successful workflow;
- four calls for the real database-error workflow;
- at least one failed/rejected call for script exhaustion.

Select calls from both workflows and compare:

- Messages
- Raw Request
- Outcome
- Headers
- streaming/provider metadata
- script position and exhaustion state

For the database failure, inspect the model request after
`failure-query-missing-table`. It should contain the real failed tool result
returned to Spring AI.

![Screenshot of the `failure-query-missing-table` step](docs/images/e2e-demo-llmsim.png)
## Stop the environment

```bash
docker compose \
  -f compose.yaml \
  -f compose.llmsim.yaml \
  -f compose.llmsim-demo.yaml \
  down -v --remove-orphans
```

The `-v` option removes the demo database volume and ensures the next run starts
from the repository's seed data.

## Troubleshooting

### Build cannot resolve `llmsim-build:0.10.1`

Confirm that the published image is available and that Docker can authenticate
to GHCR when necessary:

```bash
docker pull ghcr.io/pramalin/llmsim-build:0.10.1
```

### Compose reports that llmsim has no healthcheck

The demo Dockerfile includes a health check because `compose.llmsim.yaml` waits
for llmsim with `condition: service_healthy`.

After updating the overlay, rebuild without reusing the old llmsim image:

```bash
docker compose \
  -f compose.yaml \
  -f compose.llmsim.yaml \
  -f compose.llmsim-demo.yaml \
  build --no-cache llmsim
```

Then rerun:

```bash
./scripts/e2e-demo.sh
```

### Application does not become ready

Inspect the generated diagnostics:

```bash
cat artifacts/e2e-demo/compose-ps.txt
less artifacts/e2e-demo/compose-logs.txt
```

Also check the current stack directly:

```bash
docker compose \
  -f compose.yaml \
  -f compose.llmsim.yaml \
  -f compose.llmsim-demo.yaml \
  ps -a
```

### Tool-error scenario unexpectedly fails at the HTTP layer

The intended behavior is for the agent to receive the failed tool result and
produce the scripted explanatory final answer. If the application aborts the
request immediately instead, its Spring AI/MCP error policy differs from the
behavior assumed by this demo.

In that case, keep the scenario but change `scripts/e2e-demo.sh` to accept the
application's real non-2xx contract and assert on the saved response and traces.
Do not weaken the key assertion that the invalid SQL reached the real tool.

### Overrun produces multiple journal entries

That normally indicates an upstream retry. It is expected by the demo, which
requires at least nine calls rather than exactly nine.
