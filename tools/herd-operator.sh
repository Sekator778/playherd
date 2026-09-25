#!/usr/bin/env bash
# Р-43 operator CLI — a thin curl wrapper over server/'s admin endpoints
# (crowdvsmarket.server.api.AdminResource). "CLI с подписью ключом оператора" is not
# implemented yet: today this posts a bearer token (HERD_OPERATOR_TOKEN, same value the server
# reads from its own env — see server/src/main/resources/application.properties
# "herd.operator.token") as a stand-in until real operator-key signing exists — see
# docs/research/server_design.md, section "Operator (Р-43)".
#
# Usage:
#   HERD_OPERATOR_TOKEN=... tools/herd-operator.sh status
#   HERD_OPERATOR_TOKEN=... tools/herd-operator.sh cancel <roundId> ["reason"]
#
# Env:
#   HERD_OPERATOR_TOKEN  required — must match the running server's HERD_OPERATOR_TOKEN.
#   HERD_SERVER_URL      optional — default http://localhost:8081.

set -euo pipefail

SERVER_URL="${HERD_SERVER_URL:-http://localhost:8081}"

if [[ -z "${HERD_OPERATOR_TOKEN:-}" ]]; then
  echo "error: HERD_OPERATOR_TOKEN is not set" >&2
  exit 1
fi

usage() {
  cat >&2 <<EOF
Usage:
  $(basename "$0") status
  $(basename "$0") cancel <roundId> ["reason"]

Env:
  HERD_OPERATOR_TOKEN  required — bearer token, must match the server's own.
  HERD_SERVER_URL       optional — default ${SERVER_URL}.
EOF
  exit 1
}

require_curl() {
  command -v curl >/dev/null 2>&1 || { echo "error: curl not found" >&2; exit 1; }
}

cmd="${1:-}"
require_curl

case "$cmd" in
  status)
    curl -sS -X GET "${SERVER_URL}/api/admin/status" \
      -H "Authorization: Bearer ${HERD_OPERATOR_TOKEN}"
    echo
    ;;
  cancel)
    round_id="${2:-}"
    reason="${3:-cancelled by operator}"
    if [[ -z "$round_id" ]]; then
      echo "error: cancel requires <roundId>" >&2
      usage
    fi
    # Minimal JSON string escaping (backslash and double-quote only — this CLI never needs to
    # send control characters in a reason).
    escaped_reason=$(printf '%s' "$reason" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')
    curl -sS -X POST "${SERVER_URL}/api/admin/rounds/${round_id}/cancel" \
      -H "Authorization: Bearer ${HERD_OPERATOR_TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"reason\":\"${escaped_reason}\"}"
    echo
    ;;
  *)
    usage
    ;;
esac
