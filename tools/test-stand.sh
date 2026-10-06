#!/usr/bin/env bash
# Tests of tools/stand.sh that need neither docker nor a network (`make test-tools`): the reachability check after `up` asks
# every venue the server prices from — Hyperliquid, Coinbase, Kraken — from inside the server container, says so for each,
# warns (never fails) for one that does not answer, and leaves out a venue that HERD_PRICES_DISABLED_VENUES switches off; and the
# host port of the web: a second `up` on a stand that is already running keeps the port its own web container holds (a public
# tunnel is pointed at it), where it used to call it busy and move the web to the next one; and the `.env` with the stand's
# secrets, which is created owner-only (mode 600, whatever the umask) and, when an earlier run or an editor left it readable by
# others, tightened.
# A fake `docker` stands in for the compose project and records what it would have been asked, a fake `lsof` for the
# processes that listen.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=stand.sh
source "${HERE}/stand.sh" # defines the functions, runs nothing; it also sets `set -e` and moves to the repository root
set +e

SANDBOX="$(mktemp -d)"
trap 'rm -rf "${SANDBOX}"' EXIT
ROOT_DIR="${SANDBOX}" # no .env of the real checkout is read by `setting`
CALLS="${SANDBOX}/calls"
FAILS=0

# `docker compose -p herd -f FILE exec -T server curl <args> URL`: the last argument is the URL. HERD_FAKE_DOWN lists the URLs
# the container cannot reach (curl then says why on stderr and prints 000, as `curl -sS -w %{http_code}` does).
# The stand itself: HERD_FAKE_WEB_PORT is the host port the web container of the running stand publishes (unset: no stand is
# running, `docker compose port` finds no container and fails), HERD_FAKE_WEB_V6ONLY=1 has it published on [::] only.
docker() {
  local url="${!#}"
  echo "$*" >>"${CALLS}"
  case "$*" in
    *" port web 80")
      if [[ -n "${HERD_FAKE_WEB_PORT:-}" ]]; then
        [[ -n "${HERD_FAKE_WEB_V6ONLY:-}" ]] || printf '0.0.0.0:%s\n' "${HERD_FAKE_WEB_PORT}"
        printf '[::]:%s\n' "${HERD_FAKE_WEB_PORT}"
        return 0
      fi
      echo "no container found for service web" >&2
      return 1
      ;;
    "info") return 0 ;;
    "volume inspect "*) return 1 ;; # no archive volume
    "ps -a --format {{.Names}}")
      [[ -z "${HERD_FAKE_WEB_PORT:-}" ]] || printf 'herd-db-1\nherd-chainsvc-1\nherd-server-1\nherd-web-1\n'
      return 0
      ;;
    "inspect -f "*) echo herd; return 0 ;; # every herd-* container is this compose project's
    *" up -d --build") echo "UP HERD_WEB_PORT=${HERD_WEB_PORT:-}" >>"${CALLS}"; return 0 ;;
    *" ps --format "*) printf 'db healthy\nchainsvc healthy\nserver healthy\nweb healthy\n'; return 0 ;;
  esac
  case "${HERD_FAKE_DOWN:-}" in
    *"${url}"*)
      echo "curl: (7) Failed to connect to ${url}" >&2
      printf '000'
      return 7
      ;;
  esac
  printf '200'
}

# `lsof -nP -iTCP:<port> -sTCP:LISTEN`: succeeds when something listens on the port — HERD_FAKE_BUSY lists the busy ones.
lsof() {
  local arg port=""
  for arg in "$@"; do
    [[ "${arg}" == -iTCP:* ]] && port="${arg#-iTCP:}"
  done
  [[ " ${HERD_FAKE_BUSY:-} " == *" ${port} "* ]]
}

ok() { # description, status (0 = holds)
  if [[ "$2" == "0" ]]; then
    echo "ok   - $1"
  else
    echo "FAIL - $1"
    FAILS=$((FAILS + 1))
  fi
}

run() { # the log of check_price_source under the environment of the caller; what the container was asked is in ${CALLS}
  : >"${CALLS}"
  check_price_source 2>&1
}

asked() { grep -c -- "$1" "${CALLS}"; }

HL=https://api.hyperliquid.xyz/info
CB=https://api.exchange.coinbase.com/products/BTC-USD/ticker
KR=https://api.kraken.com/0/public/Time

# --- everything reachable
OUT=$(run)
ok "all three venues are asked, once each" "$([[ "$(asked "${HL}")" == 1 && "$(asked "${CB}")" == 1 && "$(asked "${KR}")" == 1 ]]; echo $?)"
ok "each is said to be reached" "$([[ "$(grep -c 'the server container reaches' <<<"${OUT}")" == 3 ]]; echo $?)"
ok "and there is no warning" "$(grep -q WARNING <<<"${OUT}"; [[ $? -ne 0 ]]; echo $?)"
ok "Hyperliquid is asked with a POST of its meta request, the exchanges with a GET" \
  "$([[ "$(grep -- "${HL}" "${CALLS}")" == *'-X POST'*'{"type":"meta"}'* && "$(grep -- "${CB}" "${CALLS}")" != *POST* && "$(grep -- "${KR}" "${CALLS}")" != *POST* ]]; echo $?)"

# --- one venue does not answer: a warning that names it, the others still reported, never a failure
for venue in "hyperliquid:${HL}" "coinbase:${CB}" "kraken:${KR}"; do
  name="${venue%%:*}"
  url="${venue#*:}"
  OUT=$(HERD_FAKE_DOWN="${url}" run)
  STATUS=$?
  ok "${name} down: the check does not fail" "${STATUS}"
  ok "${name} down: a warning names it and its address" "$(grep -q "WARNING live prices: the server container cannot reach ${url} (${name}:" <<<"${OUT}"; echo $?)"
  ok "${name} down: exactly one warning, the other two venues reached" \
    "$([[ "$(grep -c WARNING <<<"${OUT}")" == 1 && "$(grep -c 'the server container reaches' <<<"${OUT}")" == 2 ]]; echo $?)"
done

# --- a venue that is switched off is not asked (case and blanks as the server reads the list)
OUT=$(HERD_PRICES_DISABLED_VENUES=" Kraken , coinbase" run)
ok "switched off: only Hyperliquid is asked" "$([[ "$(asked "${HL}")" == 1 && "$(asked "${CB}")" == 0 && "$(asked "${KR}")" == 0 ]]; echo $?)"
ok "switched off: it says so for each" \
  "$([[ "$(grep -c 'is switched off (HERD_PRICES_DISABLED_VENUES), not probed' <<<"${OUT}")" == 2 ]]; echo $?)"
OUT=$(HERD_PRICES_DISABLED_VENUES=hyperliquid HERD_FAKE_DOWN="${HL}" run)
ok "a venue that is off and unreachable is no warning" "$(grep -q WARNING <<<"${OUT}"; [[ $? -ne 0 ]]; echo $?)"

# --- no live prices, no check
OUT=$(HERD_PRICES_SOURCE=synthetic run)
ok "synthetic prices: nothing is asked and nothing is said" "$([[ ! -s "${CALLS}" && -z "${OUT}" ]]; echo $?)"
OUT=$(HERD_MODE=replay run)
ok "replay mode: nothing is asked" "$([[ ! -s "${CALLS}" ]]; echo $?)"

# --- the host port of the web: a second `up` keeps the port the stand's own web container holds
pick() { # the port pick_web_port chooses, under the errexit the script itself runs with; what it says goes to ${PICK_LOG}
  ( set -e; pick_web_port ) 2>"${PICK_LOG}"
}
PICK_LOG="${SANDBOX}/pick.log"

PORT=$(pick)
ok "no stand, 8480 free: 8480" "$([[ "${PORT}" == 8480 ]]; echo $?)"
PORT=$(HERD_FAKE_BUSY="8480" pick)
ok "no stand, 8480 held by another process: the next free port, 8481" "$([[ "${PORT}" == 8481 ]]; echo $?)"
ok "... and it says why" "$(grep -q 'port 8480 is in use — looking for the next free one' "${PICK_LOG}"; echo $?)"
PORT=$(HERD_FAKE_BUSY="8480 8481 8482" pick)
ok "no stand, three ports held by others: the first free one, 8483" "$([[ "${PORT}" == 8483 ]]; echo $?)"

PORT=$(HERD_FAKE_BUSY="8480" HERD_FAKE_WEB_PORT=8480 pick)
ok "the stand runs on 8480 (its own web holds the port): kept, not moved to 8481" "$([[ "${PORT}" == 8480 ]]; echo $?)"
ok "... and it says it is the stand's own" "$(grep -q "port 8480 is held by this stand's own web container — kept" "${PICK_LOG}"; echo $?)"
ok "... and does not go looking for another port" "$(grep -q 'looking for the next free one' "${PICK_LOG}"; [[ $? -ne 0 ]]; echo $?)"
PORT=$(HERD_FAKE_BUSY="8480 8481" HERD_FAKE_WEB_PORT=8480 pick)
ok "the stand runs on 8480 and 8481 is held by another process too: 8480" "$([[ "${PORT}" == 8480 ]]; echo $?)"
PORT=$(HERD_FAKE_BUSY="8480" HERD_FAKE_WEB_PORT=8480 HERD_FAKE_WEB_V6ONLY=1 pick)
ok "the stand's web published on [::] only: kept as well" "$([[ "${PORT}" == 8480 ]]; echo $?)"
PORT=$(HERD_FAKE_BUSY="8480 8481" HERD_FAKE_WEB_PORT=8481 pick)
ok "the stand runs on 8481 (8480 is another process's): it stays on 8481" "$([[ "${PORT}" == 8481 ]]; echo $?)"
ok "... and says so" "$(grep -q "this stand's web container stays on its own port 8481" "${PICK_LOG}"; echo $?)"
PORT=$(HERD_FAKE_BUSY="8481" HERD_FAKE_WEB_PORT=8481 pick)
ok "the stand runs on 8481 and 8480 is free again: back to the default, 8480" "$([[ "${PORT}" == 8480 ]]; echo $?)"
PORT=$(HERD_FAKE_BUSY="8480" HERD_FAKE_WEB_PORT=8480 HERD_WEB_PORT=9000 pick)
ok "a free port asked for with HERD_WEB_PORT is honoured, the stand moves to it" "$([[ "${PORT}" == 9000 ]]; echo $?)"
PORT=$(HERD_FAKE_BUSY="8480 9000" HERD_FAKE_WEB_PORT=8480 HERD_WEB_PORT=9000 pick)
ok "a port asked for that another process holds does not move a running stand" "$([[ "${PORT}" == 8480 ]]; echo $?)"
PORT=$(HERD_FAKE_BUSY="9000" HERD_WEB_PORT=9000 pick)
ok "no stand and the port asked for is busy: the search starts at 8480's neighbour, 8481" "$([[ "${PORT}" == 8481 ]]; echo $?)"
BUSY_ALL="$(seq -s ' ' 8480 8529)"
OUT=$(HERD_FAKE_BUSY="${BUSY_ALL}" pick)
STATUS=$?
ok "no stand and no free port in 8480..8529: it refuses" "$([[ "${STATUS}" -ne 0 && -z "${OUT}" ]]; echo $?)"

up_run() { # `stand.sh up` end to end against the fake docker: what the compose project is started with, and what is printed
  : >"${CALLS}"
  ( set -e; cmd_up ) 2>&1
}
OUT=$(HERD_FAKE_BUSY="8480" HERD_FAKE_WEB_PORT=8480 up_run)
STATUS=$?
ok "a second up on a running stand: succeeds" "${STATUS}"
ok "... compose is started with the web on 8480 again" "$(grep -q '^UP HERD_WEB_PORT=8480$' "${CALLS}"; echo $?)"
ok "... and the stand says it is on 8480" "$(grep -q 'http://localhost:8480' <<<"${OUT}"; echo $?)"
ok "... and never on 8481" "$(grep -q 8481 "${CALLS}"; [[ $? -ne 0 ]]; echo $?)"
OUT=$(HERD_FAKE_BUSY="8480" up_run)
ok "the first up with another process on 8480: compose gets 8481" "$(grep -q '^UP HERD_WEB_PORT=8481$' "${CALLS}"; echo $?)"
OUT=$(up_run)
ok "the first up on a free machine: compose gets 8480" "$(grep -q '^UP HERD_WEB_PORT=8480$' "${CALLS}"; echo $?)"

# --- .env: it holds the stand's secrets, so it is owner-only from its first byte, and a looser one is tightened
mode_of() { file_mode "$1"; }
rm -f "${ROOT_DIR}/.env"
OUT=$( (umask 022; ensure_env_file) 2>&1 )
ok "a new .env is created even under a permissive umask (022): mode 600" "$([[ "$(mode_of "${ROOT_DIR}/.env")" == 600 ]]; echo $?)"
ok "... with the four secrets in it" "$([[ "$(grep -cE '^(HERD_OPERATOR_TOKEN|HERD_DB_PASSWORD|C2_SVC_TOKEN|HERD_CHAINSVC_TOKEN)=.' "${ROOT_DIR}/.env")" == 4 ]]; echo $?)"
ok "... and no line about tightening anything: it was never looser" "$(grep -q 'now mode 600' <<<"${OUT}"; [[ $? -ne 0 ]]; echo $?)"
SECRETS_BEFORE=$(cat "${ROOT_DIR}/.env")
OUT=$(ensure_env_file 2>&1)
ok "a second call changes nothing in the file" "$([[ "$(cat "${ROOT_DIR}/.env")" == "${SECRETS_BEFORE}" ]]; echo $?)"
ok "... and says nothing about the mode" "$(grep -q 'mode' <<<"${OUT}"; [[ $? -ne 0 ]]; echo $?)"

chmod 644 "${ROOT_DIR}/.env"
OUT=$(ensure_env_file 2>&1)
ok "an existing .env of mode 644 is set to 600" "$([[ "$(mode_of "${ROOT_DIR}/.env")" == 600 ]]; echo $?)"
ok "... with a line that says what it was" "$(grep -q '.env had mode 644; it holds the stand.s secrets, now mode 600' <<<"${OUT}"; echo $?)"
ok "... and its secrets are the ones it had" "$([[ "$(cat "${ROOT_DIR}/.env")" == "${SECRETS_BEFORE}" ]]; echo $?)"
for loose in 640 604 666 755; do
  chmod "${loose}" "${ROOT_DIR}/.env"
  ensure_env_file >/dev/null 2>&1
  ok "mode ${loose} (group or others can get at it) is set to 600" "$([[ "$(mode_of "${ROOT_DIR}/.env")" == 600 ]]; echo $?)"
done
chmod 400 "${ROOT_DIR}/.env"
OUT=$(ensure_env_file 2>&1)
ok "an owner-only .env (400) is left as it is" "$([[ "$(mode_of "${ROOT_DIR}/.env")" == 400 ]]; echo $?)"
ok "... without a word" "$(grep -q 'mode' <<<"${OUT}"; [[ $? -ne 0 ]]; echo $?)"
chmod 600 "${ROOT_DIR}/.env"

# a .env that only has some of the secrets: the rest is added, and a loose file is tightened in the same call
printf '# mine\nHERD_OPERATOR_TOKEN=keep-me\n' >"${ROOT_DIR}/.env"
chmod 644 "${ROOT_DIR}/.env"
ensure_env_file >/dev/null 2>&1
ok "a partial .env: the secret it had is kept" "$(grep -q '^HERD_OPERATOR_TOKEN=keep-me$' "${ROOT_DIR}/.env"; echo $?)"
ok "... the missing ones are added" "$([[ "$(grep -cE '^(HERD_DB_PASSWORD|C2_SVC_TOKEN|HERD_CHAINSVC_TOKEN)=.' "${ROOT_DIR}/.env")" == 3 ]]; echo $?)"
ok "... and the file ends owner-only" "$([[ "$(mode_of "${ROOT_DIR}/.env")" == 600 ]]; echo $?)"

# `up` itself goes through it
rm -f "${ROOT_DIR}/.env"
OUT=$(up_run)
ok "stand.sh up creates the .env with mode 600" "$([[ "$(mode_of "${ROOT_DIR}/.env")" == 600 ]]; echo $?)"

if [[ "${FAILS}" -ne 0 ]]; then
  echo "${FAILS} check(s) failed" >&2
  exit 1
fi
echo "all checks passed"
