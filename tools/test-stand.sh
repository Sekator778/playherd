#!/usr/bin/env bash
# Tests of tools/stand.sh that need neither docker nor a network (`make test-tools`): the reachability check after `up` asks
# every venue the server prices from — Hyperliquid, Coinbase, Kraken — from inside the server container, says so for each,
# warns (never fails) for one that does not answer, and leaves out a venue that HERD_PRICES_DISABLED_VENUES switches off.
# A fake `docker` stands in for the compose project and records what the container would have been asked.

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
docker() {
  local url="${!#}"
  echo "$*" >>"${CALLS}"
  case "${HERD_FAKE_DOWN:-}" in
    *"${url}"*)
      echo "curl: (7) Failed to connect to ${url}" >&2
      printf '000'
      return 7
      ;;
  esac
  printf '200'
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

if [[ "${FAILS}" -ne 0 ]]; then
  echo "${FAILS} check(s) failed" >&2
  exit 1
fi
echo "all checks passed"
