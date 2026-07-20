#!/usr/bin/env bash
set -euo pipefail

# End-to-end regression test for the llmsim-backed agent flow.
#
# Brings up the REAL stack (compose.yaml + compose.llmsim.yaml -- real
# Postgres, real mcp-gateway, real Spring AI ChatClient, only the model
# provider itself is llmsim), asks the agent the one question
# llmsim/AnalyticsFlow.scala is scripted to handle, and asserts on both
# the final answer and the full tool-call trace: list_tables ->
# describe_table -> execute_sql, in order, with the right arguments --
# not just "did it answer something."
#
# Deliberately a shell script driving the real `docker compose` CLI,
# rather than a JUnit/Testcontainers IT test: mcp-gateway dynamically
# starts its own sibling containers via a Docker-socket mount
# (use_api_socket: true), which the real compose setup already handles
# correctly -- re-deriving that in Testcontainers risked introducing new
# failure modes unrelated to what's actually being tested, on top of a
# currently-open regression in ComposeContainer for this project's pinned
# Testcontainers version (testcontainers-java#11415).
#
# Usage: ./scripts/e2e-test.sh
# Requires: docker compose (v2 plugin), curl, jq

COMPOSE="docker compose -f compose.yaml -f compose.llmsim.yaml"
QUESTION="How many merchants do we have?"

for tool in docker curl jq; do
  command -v "$tool" > /dev/null 2>&1 || { echo "FAIL: '$tool' is required but not found on PATH"; exit 1; }
done

cleanup() {
  echo "--- tearing down ---"
  $COMPOSE down -v --remove-orphans
}
trap cleanup EXIT

echo "--- starting stack (clean slate) ---"
$COMPOSE down -v --remove-orphans
$COMPOSE up --build -d

echo "--- waiting for application readiness ---"
READY=false
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/actuator/health/readiness > /dev/null 2>&1; then
    echo "application ready after ${i}s"
    READY=true
    break
  fi
  sleep 1
done
if [ "$READY" != "true" ]; then
  echo "FAIL: application did not become ready in time"
  mkdir -p artifacts
  $COMPOSE ps -a > artifacts/compose-ps.txt 2>&1 || true
  $COMPOSE logs --no-color > artifacts/compose-logs.txt 2>&1 || true
  cat artifacts/compose-logs.txt
  exit 1
fi

echo "--- asking: $QUESTION ---"
RESPONSE=$(curl -sf -X POST http://localhost:8080/api/questions \
  -H "Content-Type: application/json" \
  -d "{\"question\": \"$QUESTION\"}")

echo "$RESPONSE" | jq .

fail() {
  echo "FAIL: $1"
  mkdir -p artifacts
  echo "$RESPONSE" | jq . > artifacts/response.json 2>/dev/null || echo "$RESPONSE" > artifacts/response.json
  curl -s http://localhost:8089/_llmsim/calls | jq . > artifacts/llmsim-calls.json 2>/dev/null || true
  $COMPOSE ps -a > artifacts/compose-ps.txt 2>&1 || true
  $COMPOSE logs --no-color > artifacts/compose-logs.txt 2>&1 || true
  # Also echo to the job log directly, for a quick look without downloading
  # the artifact -- the files above are the durable copy, captured here
  # (before the EXIT trap tears the stack down) rather than in a separate
  # workflow step, which would find nothing: by the time a later step ran,
  # `down -v` would have already removed everything.
  echo "--- full response ---"
  cat artifacts/response.json
  echo "--- llmsim call journal ---"
  cat artifacts/llmsim-calls.json
  echo "--- container logs (see artifacts/compose-logs.txt for the full copy) ---"
  tail -c 4000 artifacts/compose-logs.txt
  exit 1
}

# --- assertions -------------------------------------------------------

# Pattern match, not an exact hardcoded count -- protects against the seed
# data changing over time while still verifying the answer's SHAPE is
# actually correct, not just present.
ANSWER=$(echo "$RESPONSE" | jq -r '.answer')
echo "$ANSWER" | grep -qE '^There are [0-9]+ merchants\.$' \
  || fail "answer didn't match the expected shape: $ANSWER"

TRACE_COUNT=$(echo "$RESPONSE" | jq '.traces | length')
[ "$TRACE_COUNT" -eq 3 ] || fail "expected 3 tool calls, got $TRACE_COUNT"

TOOL_0=$(echo "$RESPONSE" | jq -r '.traces[0].toolName')
TOOL_1=$(echo "$RESPONSE" | jq -r '.traces[1].toolName')
TOOL_2=$(echo "$RESPONSE" | jq -r '.traces[2].toolName')
[ "$TOOL_0" = "list_tables" ]    || fail "traces[0] should be list_tables, was $TOOL_0"
[ "$TOOL_1" = "describe_table" ] || fail "traces[1] should be describe_table, was $TOOL_1"
[ "$TOOL_2" = "execute_sql" ]    || fail "traces[2] should be execute_sql, was $TOOL_2"

DESCRIBE_ARGS=$(echo "$RESPONSE" | jq -r '.traces[1].arguments')
echo "$DESCRIBE_ARGS" | grep -qi 'merchant' \
  || fail "describe_table wasn't called on the merchant table: $DESCRIBE_ARGS"

# Tolerant SQL check -- normalize whitespace/case rather than an exact
# string match, since a model could plausibly phrase the same query
# slightly differently between runs.
SQL_ARGS=$(echo "$RESPONSE" | jq -r '.traces[2].arguments' | tr '[:upper:]' '[:lower:]' | tr -s ' ')
echo "$SQL_ARGS" | grep -qE 'select count\(\*\) from merchant' \
  || fail "execute_sql wasn't the expected query: $SQL_ARGS"

echo "--- PASS ---"
