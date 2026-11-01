#!/usr/bin/env bash
# D-43 operator CLI — a thin curl wrapper over server/'s admin endpoints
# (crowdvsmarket.server.api.AdminResource). "CLI signed with the operator key" is not
# implemented yet: today this posts a bearer token (HERD_OPERATOR_TOKEN, same value the server
# reads from its own env — see server/src/main/resources/application.properties
# "herd.operator.token") as a stand-in until real operator-key signing exists — see
# docs/research/server_design.md, section "Operator (D-43)".
#
# Usage:
#   tools/herd-operator.sh [--token-file <path>] status
#   tools/herd-operator.sh [--token-file <path>] cancel <roundId> ["reason"]
#   tools/herd-operator.sh [--token-file <path>] reverse-withdrawal <requestId> "reason"
#   tools/herd-operator.sh [--token-file <path>] reverse-deposit <requestId> "reason"
#   tools/herd-operator.sh [--token-file <path>] feedback [--since YYYY-MM-DD] [--limit N] [--json]
#
# `feedback` (D-81) reads what visitors left through the in-app form, newest first: a table with the UTC time, the country (derived by the
# server from the IP address, which is not stored), the browser's time zone and language, the client, the page, the sender's nickname
# and the number of rounds they have played (a dash: the sender was not signed in), and the message; then a count by country.
# --since is a date in UTC (00:00 of that day), --limit at most 1000 (the server's default is 200), --json prints the server's answer
# as it is. The table needs jq (the answer is JSON); --json does not.
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
  $(basename "$0") [--token-file <path>] feedback [--since YYYY-MM-DD] [--limit N] [--json]

feedback: what visitors left through the in-app form, newest first, as a table (UTC time, country, time zone,
  language, client, page, nickname, rounds played, message) and a count by country. --since: only messages from
  that day (UTC) on; --limit: at most N (default 200, at most 1000); --json: the server's answer as it is (the
  table needs jq, --json does not).

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

# api_body METHOD PATH — like api, but the answer is printed as it is, without the line feed api adds (for a caller that parses it).
api_body() {
  local method="$1" path="$2"
  printf 'Authorization: Bearer %s\n' "${TOKEN}" | curl -sS -X "${method}" "${SERVER_URL}${path}" -H @-
}

# 00:00 UTC of a day (YYYY-MM-DD) as epoch seconds: GNU date first, BSD/macOS date as the fallback.
day_to_epoch() {
  date -u -d "$1 00:00:00" +%s 2>/dev/null || date -u -j -f '%Y-%m-%d %H:%M:%S' "$1 00:00:00" +%s 2>/dev/null
}

# The day an epoch second falls on (UTC); the round trip tells a real date from one a date command rolls over (2026-02-30).
epoch_to_day() {
  date -u -d "@$1" +%Y-%m-%d 2>/dev/null || date -u -r "$1" +%Y-%m-%d 2>/dev/null
}

# The table of `feedback`, from the server's answer on stdin (jq --arg since DAY --argjson limit N). Whatever a visitor typed is
# shown as text: every control character, an escape sequence's ESC among them, becomes a space and a line break " / ", so that
# a message cannot move the operator's cursor or rewrite their screen.
# shellcheck disable=SC2016 # the jq program is single-quoted on purpose: its $variables are jq's, not the shell's
FEEDBACK_JQ='
def clean:
  (if . == null then "-" else tostring end)
  | gsub("[\r\n]+"; " / ")
  | gsub("[[:cntrl:]]"; " ")
  | gsub("^ +| +$"; "")
  | if . == "" then "-" else . end;
def cut(n): if length > n then .[0:n-1] + "~" else . end;
def spaces(n): if n > 0 then ([range(0; n)] | map(" ") | join("")) else "" end;
def pad(n): . + spaces(n - length);
def line($w): [ (.t | pad($w["t"])), (.c | pad($w["c"])), (.z | pad($w["z"])), (.l | pad($w["l"])),
                (.k | pad($w["k"])), (.p | pad($w["p"])), (.n | pad($w["n"])), (.r | pad($w["r"])), .m ] | join("  ");

. as $items
| ($items | length) as $count
| ($items | map({
    t: ((.receivedAtMs / 1000 | floor) | strftime("%Y-%m-%d %H:%M:%S")),
    c: (.country | clean),
    z: (.timeZone | clean | cut(24)),
    l: (.language | clean | cut(12)),
    k: (.client | clean),
    p: (.page | clean | cut(28)),
    n: (.nickname | clean | cut(20)),
    r: (if .roundsPlayed == null then "-" else (.roundsPlayed | tostring) end),
    m: (.message | clean)
  })) as $rows
| {t: "time (UTC)", c: "country", z: "time zone", l: "language", k: "client", p: "page", n: "nickname", r: "rounds", m: "message"} as $head
| ([$head] + $rows) as $all
| (["t", "c", "z", "l", "k", "p", "n", "r"] | map({key: ., value: (. as $col | [$all[] | .[$col] | length] | max)}) | from_entries) as $w
| ($items | map(.country // "-") | group_by(.) | map({c: .[0], n: length}) | sort_by(-.n, .c)) as $by
| "Feedback " + (if $since == "" then "(all)" else "since " + $since + " (UTC)" end) + ", newest first: " + ($count | tostring)
    + (if $count == 1 then " message" else " messages" end)
    + (if $count >= $limit then " — the newest " + ($limit | tostring) + " only; --limit (at most 1000) or --since shows more" else "" end),
  "",
  ($all[] | line($w)),
  "",
  "By country:",
  ($by[] | "  " + ((if .c == "-" then "unknown" else .c end) | pad(9)) + (.n | tostring))
'

run_feedback() {
  local since="" limit="" raw=0 day secs path body
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --since) [[ $# -ge 2 ]] || { echo "error: --since needs a date (YYYY-MM-DD)" >&2; exit 1; }; since="$2"; shift 2 ;;
      --since=*) since="${1#--since=}"; shift ;;
      --limit) [[ $# -ge 2 ]] || { echo "error: --limit needs a number" >&2; exit 1; }; limit="$2"; shift 2 ;;
      --limit=*) limit="${1#--limit=}"; shift ;;
      --json) raw=1; shift ;;
      -h|--help) usage 0 ;;
      *) echo "error: feedback: unknown argument: $1" >&2; usage ;;
    esac
  done
  path="/api/admin/feedback"
  local -a query=()
  if [[ -n "${since}" ]]; then
    day="${since}"
    secs=""
    if [[ "${day}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
      secs="$(day_to_epoch "${day}" || true)"
    fi
    if [[ -z "${secs}" || "$(epoch_to_day "${secs}" || true)" != "${day}" ]]; then
      echo "error: --since must be a date, YYYY-MM-DD (UTC), got: ${since}" >&2
      exit 1
    fi
    query+=("sinceMs=$((secs * 1000))")
  fi
  if [[ -n "${limit}" ]]; then
    if ! [[ "${limit}" =~ ^[1-9][0-9]{0,3}$ ]] || (( limit > 1000 )); then
      echo "error: --limit must be a whole number from 1 to 1000, got: ${limit}" >&2
      exit 1
    fi
    query+=("limit=${limit}")
  fi
  if (( ${#query[@]} > 0 )); then
    path="${path}?$(IFS='&'; echo "${query[*]}")"
  fi
  if (( raw )); then
    api GET "${path}"
    return
  fi
  command -v jq >/dev/null 2>&1 || { echo "error: jq not found — the table is made with it; install jq, or use --json" >&2; exit 1; }
  body="$(api_body GET "${path}")"
  if ! printf '%s' "${body}" | jq -e 'type == "array"' >/dev/null 2>&1; then
    echo "error: the server did not answer with a list:" >&2
    printf '%s\n' "${body}" >&2
    exit 1
  fi
  if [[ "$(printf '%s' "${body}" | jq 'length')" == 0 ]]; then
    echo "No feedback${since:+ since ${since} (UTC)}."
    return
  fi
  printf '%s' "${body}" | jq -r --arg since "${since}" --argjson limit "${limit:-200}" "${FEEDBACK_JQ}"
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
    # Wave-2 security fix (D-32/D-43 pattern): reverses a withdrawal request's already-recorded
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
    # Task item 1 (BLOCKER security fix, an internal security review):
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
  feedback)
    # D-81: the in-app feedback, newest first (GET /api/admin/feedback).
    shift
    run_feedback "$@"
    ;;
  *)
    usage
    ;;
esac
