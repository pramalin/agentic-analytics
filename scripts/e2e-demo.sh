#!/usr/bin/env bash
set -Eeuo pipefail

# Interactive end-to-end demonstration for agentic-analytics + llmsim.
#
# Unlike scripts/e2e-test.sh, this script deliberately leaves the environment
# running after verification so the complete call journal can be inspected in
# the llmsim console.
#
# Demonstrated cases:
#   1. Successful real PostgreSQL/MCP workflow.
#   2. Real tool/database failure from deliberately invalid SQL.
#   3. Deterministic llmsim script exhaustion.
#
# Usage:
#   ./scripts/e2e-demo.sh
#
# Requires:
#   docker with the Compose v2 plugin, curl, jq

readonly COMPOSE_FILES=(
  -f compose.yaml
  -f compose.llmsim.yaml
  -f compose.llmsim-demo.yaml
)

compose() {
  docker compose "${COMPOSE_FILES[@]}" "$@"
}

readonly APP_URL="${APP_URL:-http://localhost:8080}"
readonly LLMSIM_URL="${LLMSIM_URL:-http://localhost:8089}"
readonly CONSOLE_URL="${LLMSIM_URL}/_llmsim/console"

readonly SUCCESS_QUESTION="[llmsim demo: success] How many merchants do we have?"
readonly TOOL_ERROR_QUESTION="[llmsim demo: tool error] Count rows in the missing merchant archive."
readonly OVERRUN_QUESTION="[llmsim demo: overrun] Run one more analytics request."

ARTIFACT_DIR="${ARTIFACT_DIR:-artifacts/e2e-demo}"
mkdir -p "$ARTIFACT_DIR"

for tool in docker curl jq grep sed; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "FAIL: '$tool' is required but was not found on PATH." >&2
    exit 1
  }
done

docker compose version >/dev/null 2>&1 || {
  echo "FAIL: Docker Compose v2 is required." >&2
  exit 1
}

dump_diagnostics() {
  echo
  echo "--- compose status ---"
  compose ps -a || true

  echo
  echo "--- llmsim journal ---"
  curl -sS "${LLMSIM_URL}/_llmsim/calls" | jq . || true

  echo
  echo "--- recent container logs ---"
  compose logs --no-color --tail=250 || true

  compose ps -a >"$ARTIFACT_DIR/compose-ps.txt" 2>&1 || true
  compose logs --no-color >"$ARTIFACT_DIR/compose-logs.txt" 2>&1 || true
  curl -sS "${LLMSIM_URL}/_llmsim/calls" \
    >"$ARTIFACT_DIR/llmsim-calls.json" 2>/dev/null || true
}

fail() {
  echo
  echo "DEMO FAILED: $*" >&2
  dump_diagnostics
  echo
  echo "The stack has been left running for diagnosis."
  echo "llmsim console: $CONSOLE_URL"
  echo "Stop it with:"
  printf '  docker compose'
  printf ' %q' "${COMPOSE_FILES[@]}"
  printf ' down -v --remove-orphans\n'
  exit 1
}

wait_for_url() {
  local name="$1"
  local url="$2"
  local attempts="${3:-90}"

  echo "--- waiting for $name ---"
  for i in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "$name ready after ${i}s"
      return 0
    fi
    sleep 1
  done

  fail "$name did not become ready at $url"
}

request_question() {
  local scenario="$1"
  local question="$2"
  local body_file="$ARTIFACT_DIR/${scenario}-response.json"
  local status_file="$ARTIFACT_DIR/${scenario}-status.txt"
  local payload

  payload="$(jq -cn --arg question "$question" '{question: $question}')"

  local status
  status="$(
    curl -sS \
      -o "$body_file" \
      -w '%{http_code}' \
      -X POST "${APP_URL}/api/questions" \
      -H 'Content-Type: application/json' \
      --data "$payload"
  )"

  printf '%s\n' "$status" >"$status_file"

  echo
  echo "--- $scenario: HTTP $status ---"
  jq . "$body_file" 2>/dev/null || cat "$body_file"

  printf '%s\n' "$status"
}

assert_success_response() {
  local response="$1"

  local answer
  answer="$(jq -r '.answer // empty' "$response")"
  echo "$answer" | grep -qE '^There are [0-9]+ merchants\.$' ||
    fail "success answer had an unexpected shape: '$answer'"

  local trace_count
  trace_count="$(jq '.traces | length' "$response")"
  [[ "$trace_count" -eq 3 ]] ||
    fail "success scenario expected 3 tool calls, got $trace_count"

  local actual_tools
  actual_tools="$(jq -r '[.traces[].toolName] | join(" -> ")' "$response")"
  [[ "$actual_tools" == "list_tables -> describe_table -> execute_sql" ]] ||
    fail "success tool order was '$actual_tools'"

  jq -r '.traces[1].arguments' "$response" |
    grep -qi 'merchant' ||
    fail "success describe_table did not target merchant"

  jq -r '.traces[2].arguments' "$response" |
    tr '[:upper:]' '[:lower:]' |
    tr -s ' ' |
    grep -qE 'select count\(\*\) from merchant' ||
    fail "success execute_sql did not contain the merchant count query"
}

assert_tool_error_response() {
  local response="$1"

  local answer
  answer="$(jq -r '.answer // empty' "$response")"
  [[ "$answer" == \
    "I could not complete the query because the database tool returned an error." ]] ||
    fail "tool-error scenario returned an unexpected answer: '$answer'"

  local trace_count
  trace_count="$(jq '.traces | length' "$response")"
  [[ "$trace_count" -eq 3 ]] ||
    fail "tool-error scenario expected 3 tool calls, got $trace_count"

  local actual_tools
  actual_tools="$(jq -r '[.traces[].toolName] | join(" -> ")' "$response")"
  [[ "$actual_tools" == "list_tables -> describe_table -> execute_sql" ]] ||
    fail "tool-error tool order was '$actual_tools'"

  jq -r '.traces[2].arguments' "$response" |
    grep -q 'merchant_missing_for_llmsim_demo' ||
    fail "tool-error execute_sql did not contain the deliberately missing table"

  # Different MCP/database-server releases phrase SQL failures differently.
  # Check broad, meaningful indicators rather than one exact message.
  jq -r '.traces[2].result // empty' "$response" |
    grep -Eqi 'error|does not exist|relation|failed|exception' ||
    fail "tool-error trace did not expose a recognizable database error"
}

echo "--- starting demo stack from a clean slate ---"
compose down -v --remove-orphans
compose up --build -d

wait_for_url \
  "application readiness" \
  "${APP_URL}/actuator/health/readiness"

wait_for_url \
  "llmsim management API" \
  "${LLMSIM_URL}/_llmsim/calls"

echo
echo "=== Scenario 1: successful database workflow ==="
success_status="$(
  request_question success "$SUCCESS_QUESTION" | tail -n 1
)"
[[ "$success_status" == "200" ]] ||
  fail "success scenario returned HTTP $success_status"
assert_success_response "$ARTIFACT_DIR/success-response.json"
echo "PASS: successful database workflow"

echo
echo "=== Scenario 2: real PostgreSQL/MCP tool failure ==="
tool_error_status="$(
  request_question tool-error "$TOOL_ERROR_QUESTION" | tail -n 1
)"
[[ "$tool_error_status" == "200" ]] ||
  fail "tool-error scenario returned HTTP $tool_error_status"
assert_tool_error_response "$ARTIFACT_DIR/tool-error-response.json"
echo "PASS: real tool/database failure was surfaced and not invented away"

echo
echo "=== Scenario 3: deterministic script exhaustion ==="
overrun_status="$(
  request_question overrun "$OVERRUN_QUESTION" | tail -n 1
)"

# The application should surface the exhausted upstream model as a non-2xx
# response. Do not use curl -f here: this is an expected failure.
if [[ "$overrun_status" =~ ^2 ]]; then
  fail "script-overrun scenario unexpectedly succeeded with HTTP $overrun_status"
fi
echo "PASS: exhausted llmsim script produced an application failure"

echo
echo "--- checking llmsim call journal ---"
curl -fsS "${LLMSIM_URL}/_llmsim/calls" \
  >"$ARTIFACT_DIR/llmsim-calls.json"

journal_count="$(jq 'length' "$ARTIFACT_DIR/llmsim-calls.json")"

# Eight script steps are consumed by the first two questions. The overrun
# request contributes at least one more journal entry. Spring AI may retry an
# upstream failure depending on application configuration, so require >= 9
# rather than assuming exactly 9.
[[ "$journal_count" -ge 9 ]] ||
  fail "expected at least 9 llmsim calls, found $journal_count"

jq -e '
  any(.[];
    (tostring | contains("merchant_missing_for_llmsim_demo"))
  )
' "$ARTIFACT_DIR/llmsim-calls.json" >/dev/null ||
  fail "the llmsim journal did not retain the missing-table scenario"

echo "PASS: llmsim journal contains $journal_count calls"

cat <<EOF

============================================================
Agentic analytics llmsim demonstration completed successfully
============================================================

Scenarios:
  PASS  Successful real PostgreSQL/MCP workflow
  PASS  Real tool/database failure
  PASS  Deterministic llmsim script exhaustion

The stack has deliberately been left running for inspection.

llmsim console:
  $CONSOLE_URL

Application UI:
  http://localhost:3000

Application engineering console:
  http://localhost:4200

Saved responses and diagnostics:
  $ARTIFACT_DIR

Stop the demo:
  docker compose -f compose.yaml -f compose.llmsim.yaml \
    -f compose.llmsim-demo.yaml down -v --remove-orphans

EOF
