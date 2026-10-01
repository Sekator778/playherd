#!/usr/bin/env bash
# Р-43 operator CLI — a thin curl wrapper over server/'s admin endpoints
# (crowdvsmarket.server.api.AdminResource). "CLI с подписью ключом оператора" is not
# implemented yet: today this posts a bearer token (HERD_OPERATOR_TOKEN, same value the server
# reads from its own env — see server/src/main/resources/application.properties
# "herd.operator.token") as a stand-in until real operator-key signing exists — see
# docs/research/server_design.md, section "Operator (Р-43)".
#
# Usage:
#   tools/herd-operator.sh [--token-file <path>] status
#   tools/herd-operator.sh [--token-file <path>] cancel <roundId> ["reason"]
#   tools/herd-operator.sh [--token-file <path>] reverse-withdrawal <requestId> "reason"
#   tools/herd-operator.sh [--token-file <path>] reverse-deposit <requestId> "reason"
#
# Operator token (bearer, must match the running server's HERD_OPERATOR_TOKEN) — first match wins:
#   --token-file <path>        a file with the token on its first line, or a dotenv file with a
#                              HERD_OPERATOR_TOKEN=... line (the stand's .env works as is)
#   HERD_OPERATOR_TOKEN_FILE   the same file path, from the environment
#   HERD_OPERATOR_TOKEN        the token itself
# Other env:
#   HERD_SERVER_URL            optional — default http://localhost:8081.
#
# Keeping the token out of the shell history (see usage() for the recipe): put it in a file and use
# --token-file / HERD_OPERATOR_TOKEN_FILE. The script also hands the token to curl on stdin
# (`-H @-`, curl >= 7.55) instead of in an argument, so it does not show up in `ps` either.

set -euo pipefail

SERVER_URL="${HERD_SERVER_URL:-http://localhost:8081}"
TOKEN_FILE="${HERD_OPERATOR_TOKEN_FILE:-}"
TOKEN=""

usage() {
  cat >&2 <<EOF
Usage:
  $(basename "$0") [--token-file <path>] status
  $(basename "$0") [--token-file <path>] cancel <roundId> ["reason"]
  $(basename "$0") [--token-file <path>] reverse-withdrawal <requestId> "reason"
  $(basename "$0") [--token-file <path>] reverse-deposit <requestId> "reason"

Operator token (bearer, must match the server's HERD_OPERATOR_TOKEN) — first match wins:
  --token-file <path>        file with the token on its first line, or a dotenv file with a
                             HERD_OPERATOR_TOKEN=... line (the stand's .env works as is)
  HERD_OPERATOR_TOKEN_FILE   the same file path, from the environment
  HERD_OPERATOR_TOKEN        the token itself

Env:
  HERD_SERVER_URL            optional — default ${SERVER_URL}.

Keep the token out of the shell history:
  - write it to a file only you can read (this line reads it from the stand's .env, so the token
    itself is never typed):
      (umask 077; grep '^HERD_OPERATOR_TOKEN=' .env | cut -d= -f2- > ~/.herd-operator-token)
  - then run   $(basename "$0") --token-file ~/.herd-operator-token status
    or set HERD_OPERATOR_TOKEN_FILE=~/.herd-operator-token once (e.g. in your shell profile).
  - do not put HERD_OPERATOR_TOKEN=<token> in front of the command: that line lands in the history
    (a leading space avoids it only with HISTCONTROL=ignorespace, zsh: HIST_IGNORE_SPACE).
EOF
  exit "${1:-1}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --token-file)
      [[ $# -ge 2 ]] || { echo "error: --token-file needs a path" >&2; exit 1; }
      TOKEN_FILE="$2"
      shift 2
      ;;
    --token-file=*)
      TOKEN_FILE="${1#--token-file=}"
      shift
      ;;
    -h|--help) usage 0 ;;
    --) shift; break ;;
    -*) echo "error: unknown option: $1" >&2; usage ;;
    *) break ;;
  esac
done

# Octal permission bits of a file — GNU stat first, BSD/macOS stat as the fallback.
file_mode() {
  stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null || true
}

load_token() {
  if [[ -n "${TOKEN_FILE}" ]]; then
    if [[ ! -f "${TOKEN_FILE}" || ! -r "${TOKEN_FILE}" ]]; then
      echo "error: token file is not a readable file: ${TOKEN_FILE}" >&2
      exit 1
    fi
    local mode line
    mode=$(file_mode "${TOKEN_FILE}")
    if [[ "${mode}" =~ ^[0-7]*[0-7][0-7]$ && "${mode: -2}" != "00" ]]; then
      echo "warning: ${TOKEN_FILE} is readable by other users (mode ${mode}) — chmod 600 it" >&2
    fi
    line=$(grep -m1 '^HERD_OPERATOR_TOKEN=' "${TOKEN_FILE}" || true)
    if [[ -n "${line}" ]]; then
      TOKEN="${line#HERD_OPERATOR_TOKEN=}"
      TOKEN="${TOKEN%\"}"
      TOKEN="${TOKEN#\"}"
    else
      IFS= read -r TOKEN <"${TOKEN_FILE}" || true # last line without a trailing newline: read fails but fills TOKEN
    fi
    TOKEN="${TOKEN//[[:space:]]/}"
    if [[ -z "${TOKEN}" ]]; then
      echo "error: no token in ${TOKEN_FILE}" >&2
      exit 1
    fi
  elif [[ -n "${HERD_OPERATOR_TOKEN:-}" ]]; then
    TOKEN="${HERD_OPERATOR_TOKEN}"
  else
    echo "error: no operator token — use --token-file <path>, or set HERD_OPERATOR_TOKEN_FILE or HERD_OPERATOR_TOKEN" >&2
    exit 1
  fi
}

require_curl() {
  command -v curl >/dev/null 2>&1 || { echo "error: curl not found" >&2; exit 1; }
}

# api METHOD PATH [JSON_BODY] — the bearer header goes to curl on stdin (-H @-), never into the
# argument list.
api() {
  local method="$1" path="$2" body="${3:-}"
  local -a args=(-sS -X "${method}" "${SERVER_URL}${path}" -H @-)
  if [[ -n "${body}" ]]; then
    args+=(-H "Content-Type: application/json" -d "${body}")
  fi
  printf 'Authorization: Bearer %s\n' "${TOKEN}" | curl "${args[@]}"
  echo
}

cmd="${1:-}"
[[ -n "${cmd}" ]] || usage
require_curl
load_token

case "$cmd" in
  status)
    api GET "/api/admin/status"
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
    api POST "/api/admin/rounds/${round_id}/cancel" "{\"reason\":\"${escaped_reason}\"}"
    ;;
  reverse-withdrawal)
    # Wave-2 security fix (Р-32/Р-43 pattern): reverses a withdrawal request's already-recorded
    # balance/season effect (e.g. the requestWithdrawal("-5000.000") exploit) without altering or
    # deleting the original event — see crowdvsmarket.engine.ledger.LedgerBook#reverseWithdrawalEffect
    # and GameService#reverseWithdrawal. Listed publicly afterwards via the "ledgerCorrections" RPC
    # method (no auth required), the same way a round cancellation is public via "cancellations".
    request_id="${2:-}"
    reason="${3:-}"
    if [[ -z "$request_id" || -z "$reason" ]]; then
      echo "error: reverse-withdrawal requires <requestId> and a non-empty \"reason\"" >&2
      usage
    fi
    escaped_reason=$(printf '%s' "$reason" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')
    api POST "/api/admin/withdrawals/${request_id}/reverse" "{\"reason\":\"${escaped_reason}\"}"
    ;;
  reverse-deposit)
    # Task item 1 (BLOCKER security fix, docs/reviews/2026-09-26-independent-critique.md):
    # reverses a deposit request's already-recorded (fraudulent) balance/season effect — e.g. the
    # self-reported deposits d-2/d-4/d-7/d-10, auto-confirmed with zero on-chain verification
    # before requestDeposit started rejecting new deposits outright — without altering or deleting
    # the original event. Same "public log via ledgerCorrections" pattern as reverse-withdrawal.
    request_id="${2:-}"
    reason="${3:-}"
    if [[ -z "$request_id" || -z "$reason" ]]; then
      echo "error: reverse-deposit requires <requestId> and a non-empty \"reason\"" >&2
      usage
    fi
    escaped_reason=$(printf '%s' "$reason" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')
    api POST "/api/admin/deposits/${request_id}/reverse" "{\"reason\":\"${escaped_reason}\"}"
    ;;
  *)
    usage
    ;;
esac
