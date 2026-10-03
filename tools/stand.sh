#!/usr/bin/env bash
# Local test stand control (Р-44/Р-45/Р-46/Р-48): up|down|logs|status|reset for the
# docker-compose.yml project "herd" (db, chainsvc, server, web). See
# docs/product/local_stand.md for what to do with it once it's up.
#
# Never touches any container outside this compose project — see check_no_foreign_herd_containers
# below, run before every `up`.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT=herd
COMPOSE=(docker compose -p "${PROJECT}" -f "${ROOT_DIR}/docker-compose.yml")
DEFAULT_WEB_PORT=8480
# The stand's own Postgres volume (live-price epoch, Р-70/Р-71) — must match
# volumes.herd_pg_live.name in docker-compose.yml. `reset` removes exactly this one, by name. The
# replay-epoch volume `herd_pg` (an archive of the BTC-only accelerated-history game) is no longer
# declared in the compose file and is never touched by this script.
DB_VOLUME=herd_pg_live
ARCHIVE_DB_VOLUME=herd_pg
# Where the server fetches live prices over REST — used only for the reachability check after `up`: Hyperliquid (the default
# of herd.prices.hyperliquid.url), and the two exchanges of the crypto markets, Coinbase Exchange (herd.prices.coinbase.url,
# the same ticker request the server's REST fallback makes) and Kraken (herd.prices.kraken.url), each of them a venue that
# HERD_PRICES_DISABLED_VENUES can switch off. The venue's WebSocket is a different address and is not probed here.
PRICE_SOURCE_URL=https://api.hyperliquid.xyz/info
COINBASE_PROBE_URL=https://api.exchange.coinbase.com/products/BTC-USD/ticker
KRAKEN_PROBE_URL=https://api.kraken.com/0/public/Time

cd "${ROOT_DIR}"

log() { printf '[stand] %s\n' "$*" >&2; }
die() { printf '[stand] error: %s\n' "$*" >&2; exit 1; }

require_docker() {
  command -v docker >/dev/null 2>&1 || die "docker not found on PATH"
  docker info >/dev/null 2>&1 || die "docker daemon not reachable (is Docker Desktop running?)"
}

# Refuses to proceed if a container named herd-* is running but is NOT one this same compose
# project manages (com.docker.compose.project=herd label) — i.e. something else on this shared
# machine happens to use the "herd" name. Legitimate containers from our own prior `up` carry
# that label and are left alone (compose reconciles them).
check_no_foreign_herd_containers() {
  local names
  names=$(docker ps -a --format '{{.Names}}' | grep -E '^herd-' || true)
  [[ -z "${names}" ]] && return 0
  local foreign=()
  while IFS= read -r name; do
    [[ -z "${name}" ]] && continue
    local proj
    proj=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "${name}" 2>/dev/null || true)
    if [[ "${proj}" != "${PROJECT}" ]]; then
      foreign+=("${name} (compose project: '${proj:-<none>}')")
    fi
  done <<<"${names}"
  if [[ ${#foreign[@]} -gt 0 ]]; then
    log "containers named herd-* exist that this script did not create — refusing to touch anything:"
    printf '  - %s\n' "${foreign[@]}" >&2
    die "resolve manually (rename/stop that container yourself), then retry"
  fi
}

port_is_free() {
  ! lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

# The host port that the web container of THIS compose project (herd-web-1, project herd) publishes right now; nothing when it
# is not running. Asked of the project — `docker compose port web 80` prints 0.0.0.0:8480 (and [::]:8480) — not of a container
# name, so only our own web container can ever be the answer.
own_web_port() {
  "${COMPOSE[@]}" port web 80 2>/dev/null | sed -n 's/.*:\([1-9][0-9]*\)$/\1/p' | head -n 1 || true
}

# The host port for the web: the one asked for (HERD_WEB_PORT, else 8480) when it is free — or when it is the one this stand's
# own web container already publishes: a second `up` on a running stand reconciles the container it has, so that port is the
# stand's, not somebody else's. (It used to count as busy: the second `up` moved the web from 8480 to 8481, and the public
# tunnel on 8480 lost the stand.) When something else holds the port asked for and the stand is running, the stand stays where
# it is; with no stand running the next free port is taken.
pick_web_port() {
  local p="${HERD_WEB_PORT:-${DEFAULT_WEB_PORT}}" own
  own="$(own_web_port)"
  if port_is_free "${p}"; then
    echo "${p}"
    return
  fi
  if [[ -n "${own}" && "${p}" == "${own}" ]]; then
    log "port ${p} is held by this stand's own web container — kept"
    echo "${p}"
    return
  fi
  if [[ -n "${own}" ]]; then
    log "port ${p} is in use by something else — this stand's web container stays on its own port ${own}"
    echo "${own}"
    return
  fi
  log "port ${p} is in use — looking for the next free one"
  for ((p = DEFAULT_WEB_PORT + 1; p < DEFAULT_WEB_PORT + 50; p++)); do
    if port_is_free "${p}"; then
      echo "${p}"
      return
    fi
  done
  die "no free port found in ${DEFAULT_WEB_PORT}..$((DEFAULT_WEB_PORT + 49))"
}

ensure_env_file() {
  command -v openssl >/dev/null 2>&1 || die "openssl not found (needed to generate stand secrets)"
  if [[ ! -s "${ROOT_DIR}/.env" ]]; then
    echo "# Generated by tools/stand.sh — local Docker stand secrets (Р-44). NEVER commit (see .gitignore)." >"${ROOT_DIR}/.env"
  fi

  if ! grep -q '^HERD_OPERATOR_TOKEN=' "${ROOT_DIR}/.env"; then
    log "generating HERD_OPERATOR_TOKEN"
    echo "HERD_OPERATOR_TOKEN=$(openssl rand -hex 32)" >>"${ROOT_DIR}/.env"
  fi

  # Task item 3 (security minor): Postgres password, generated the same way as the tokens above
  # — was hardcoded "herd"/"herd" in docker-compose.yml. NOTE: this only takes effect for a BRAND
  # NEW volume such as herd_pg_live on its first start (Postgres applies POSTGRES_PASSWORD only
  # while initializing an empty data directory); an existing volume keeps its old password until an
  # operator runs `ALTER USER herd WITH PASSWORD '...'` inside the running db container — see
  # docs/research/server_design.md "Postgres password" for the one-time command.
  if ! grep -q '^HERD_DB_PASSWORD=' "${ROOT_DIR}/.env"; then
    log "generating HERD_DB_PASSWORD"
    echo "HERD_DB_PASSWORD=$(openssl rand -hex 24)" >>"${ROOT_DIR}/.env"
  fi

  # FIXED INTERFACE (a): chainsvc's C2_SVC_TOKEN and server's HERD_CHAINSVC_TOKEN must be the
  # *same* bearer-token value — reuse whichever one already exists in .env so a partially-edited
  # file never ends up with two different tokens, generate once only if neither is present yet.
  if ! grep -q '^C2_SVC_TOKEN=' "${ROOT_DIR}/.env" || ! grep -q '^HERD_CHAINSVC_TOKEN=' "${ROOT_DIR}/.env"; then
    local chainsvc_token
    # `|| true` on each: under `set -o pipefail`, grep finding no match makes the whole pipeline
    # (and, un-guarded, this assignment) exit non-zero, which `set -e` would treat as this
    # function failing outright — but "not found yet" is exactly the normal case being handled
    # here, not an error.
    chainsvc_token=$(grep -m1 '^C2_SVC_TOKEN=' "${ROOT_DIR}/.env" | cut -d= -f2- || true)
    [[ -z "${chainsvc_token}" ]] && chainsvc_token=$(grep -m1 '^HERD_CHAINSVC_TOKEN=' "${ROOT_DIR}/.env" | cut -d= -f2- || true)
    if [[ -z "${chainsvc_token}" ]]; then
      log "generating C2_SVC_TOKEN/HERD_CHAINSVC_TOKEN (chainsvc bearer auth, FIXED INTERFACE a)"
      chainsvc_token="$(openssl rand -hex 32)"
    fi
    grep -q '^C2_SVC_TOKEN=' "${ROOT_DIR}/.env" || echo "C2_SVC_TOKEN=${chainsvc_token}" >>"${ROOT_DIR}/.env"
    grep -q '^HERD_CHAINSVC_TOKEN=' "${ROOT_DIR}/.env" || echo "HERD_CHAINSVC_TOKEN=${chainsvc_token}" >>"${ROOT_DIR}/.env"
  fi
}

lan_ip() {
  ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "<не найден — проверьте Wi-Fi/Ethernet>"
}

wait_healthy() {
  local timeout_s=420
  local waited=0
  log "waiting for all services to report healthy (up to ${timeout_s}s)…"
  while true; do
    local rows unhealthy_or_starting
    rows=$("${COMPOSE[@]}" ps --format '{{.Service}} {{.Health}}' 2>/dev/null || true)
    if [[ -n "${rows}" ]] && ! grep -qE '(starting|unhealthy|^\S+ $)' <<<"${rows}"; then
      log "all services healthy:"
      echo "${rows}" | sed 's/^/  /' >&2
      return 0
    fi
    if ((waited >= timeout_s)); then
      log "timed out waiting for healthy status. Current state:"
      "${COMPOSE[@]}" ps >&2
      log "last 40 log lines per service:"
      "${COMPOSE[@]}" logs --tail=40 >&2
      die "stand did not become healthy within ${timeout_s}s"
    fi
    sleep 3
    waited=$((waited + 3))
  done
}

# A HERD_* setting the way docker-compose.yml sees it: process environment first, then `.env`, then
# the default given here (the compose file's own `${VAR:-default}`).
setting() {
  local name="$1" default="$2" value="${!1:-}"
  if [[ -z "${value}" && -f "${ROOT_DIR}/.env" ]]; then
    value=$(grep -m1 "^${name}=" "${ROOT_DIR}/.env" | cut -d= -f2- || true)
    value="${value%\"}"
    value="${value#\"}"
  fi
  echo "${value:-${default}}"
}

# Whether HERD_PRICES_DISABLED_VENUES names the venue (comma-separated; blanks and case ignored, as the server reads it).
venue_disabled() {
  local venue="$1" part
  while IFS= read -r part; do
    part=$(printf '%s' "${part}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')
    if [[ "${part}" == "${venue}" ]]; then
      return 0
    fi
  done < <(printf '%s\n' "$(setting HERD_PRICES_DISABLED_VENUES '')" | tr ',' '\n')
  return 1
}

# One reachability probe from inside the `server` container: <venue> <url> [<JSON body>: then a POST, else a GET].
# A venue that HERD_PRICES_DISABLED_VENUES switches off is not probed. Never fatal: a warning, as for any venue.
probe_price_source() {
  local venue="$1" url="$2" body="${3:-}" code
  if venue_disabled "${venue}"; then
    log "live prices: ${venue} is switched off (HERD_PRICES_DISABLED_VENUES), not probed"
    return 0
  fi
  local args=(-sS -m 10 -o /dev/null -w '%{http_code}')
  if [[ -n "${body}" ]]; then
    args+=(-X POST -H 'Content-Type: application/json' -d "${body}")
  fi
  if code=$("${COMPOSE[@]}" exec -T server curl "${args[@]}" "${url}" 2>&1) && [[ "${code}" == "200" ]]; then
    log "live prices: the server container reaches ${url} (${venue}, HTTP 200)"
  else
    log "WARNING live prices: the server container cannot reach ${url} (${venue}: ${code:-no answer})"
    log "  The stand would have no live prices from ${venue}. Check outbound access of the container (docker-compose.yml,"
    log "  network herd_default), or switch the venue off with HERD_PRICES_DISABLED_VENUES=${venue} in .env"
    log "  (or HERD_PRICES_SOURCE=synthetic to run without external sources)."
  fi
}

# The stand plays on live prices by default, so the `server` container must reach every venue it prices from — Hyperliquid
# (perpetuals of the stocks, indices and commodities, the spot pair of HYPE), Coinbase and Kraken (the crypto markets and
# their backups) — on this Mac a bridge network has no internet unless masquerading is on for it (see
# docker-compose.yml, network herd_default; that fix was not verified without a running stand).
# Reported once the stand is up, never fatal.
check_price_source() {
  [[ "$(setting HERD_MODE live)" == "live" && "$(setting HERD_PRICES_SOURCE live)" == "live" ]] || return 0
  probe_price_source hyperliquid "${PRICE_SOURCE_URL}" '{"type":"meta"}'
  probe_price_source coinbase "${COINBASE_PROBE_URL}"
  probe_price_source kraken "${KRAKEN_PROBE_URL}"
}

cmd_up() {
  require_docker
  check_no_foreign_herd_containers
  ensure_env_file
  export HERD_WEB_PORT
  HERD_WEB_PORT="$(pick_web_port)"
  log "using host port ${HERD_WEB_PORT} for web"
  if docker volume inspect "${ARCHIVE_DB_VOLUME}" >/dev/null 2>&1; then
    log "volume ${ARCHIVE_DB_VOLUME} (replay-epoch archive) exists — kept as is, not used; the stand's database is ${DB_VOLUME}"
  fi
  "${COMPOSE[@]}" up -d --build
  wait_healthy
  check_price_source
  print_urls
}

cmd_down() {
  require_docker
  "${COMPOSE[@]}" down
}

cmd_reset() {
  require_docker
  # Script and compose file must agree on which volume is the stand's: drop only a volume the compose
  # file declares, and never the archive one.
  grep -qE "^[[:space:]]+name: ${DB_VOLUME}[[:space:]]*$" "${ROOT_DIR}/docker-compose.yml" \
    || die "docker-compose.yml does not declare volume ${DB_VOLUME} — refusing to reset"
  if grep -qE "^[[:space:]]+name: ${ARCHIVE_DB_VOLUME}[[:space:]]*$" "${ROOT_DIR}/docker-compose.yml"; then
    die "docker-compose.yml declares the archive volume ${ARCHIVE_DB_VOLUME} again — refusing to reset"
  fi
  log "removing the stand's containers and its DB volume ${DB_VOLUME} only — the archive volume ${ARCHIVE_DB_VOLUME} and ~/.c2/testnet11 are untouched"
  "${COMPOSE[@]}" down
  if docker volume inspect "${DB_VOLUME}" >/dev/null 2>&1; then
    docker volume rm "${DB_VOLUME}" >/dev/null
    log "removed volume ${DB_VOLUME} — the next up starts an empty database"
  else
    log "volume ${DB_VOLUME} does not exist — nothing to remove"
  fi
}

cmd_logs() {
  require_docker
  "${COMPOSE[@]}" logs -f "${@:-}"
}

cmd_status() {
  require_docker
  "${COMPOSE[@]}" ps
  echo
  local port
  port=$(docker compose -p "${PROJECT}" -f "${ROOT_DIR}/docker-compose.yml" port web 80 2>/dev/null | sed 's/^0.0.0.0://' || true)
  if [[ -n "${port}" ]]; then
    log "GET http://localhost:${port}/api/rpc/serverInfo :"
    curl -sf -X POST -H 'Content-Type: application/json' -d '{"args":[]}' "http://localhost:${port}/api/rpc/serverInfo" | (command -v jq >/dev/null 2>&1 && jq . || cat)
    echo
  fi
}

print_urls() {
  local port="${HERD_WEB_PORT:-${DEFAULT_WEB_PORT}}"
  echo
  log "stand is up. Open in a browser:"
  echo "  Local:  http://localhost:${port}"
  echo "  LAN:    http://$(lan_ip):${port}   (телефон/другой Mac в той же Wi-Fi)"
  echo
}

main() {
  case "${1:-}" in
    up) cmd_up ;;
    down) cmd_down ;;
    logs) shift; cmd_logs "$@" ;;
    status) cmd_status ;;
    reset) cmd_reset ;;
    *)
      cat >&2 <<EOF
Usage: $(basename "$0") up|down|logs [service]|status|reset

  up      build (if needed) and start db+chainsvc+server+web, wait until healthy, check that the
          server reaches the live price sources (Hyperliquid, Coinbase, Kraken), print URLs; a stand that
          is already running keeps the host port of its web (8480 by default), so a tunnel to it stays valid
  down    stop and remove containers (keeps the herd_pg_live volume and ~/.c2/testnet11)
  logs    follow logs (optionally for one service: "logs server")
  status  show container health + live GET of serverInfo
  reset   like down, but also removes the herd_pg_live volume (fresh DB on next up); the old
          replay-epoch volume herd_pg is never touched
EOF
      exit 1
      ;;
  esac
}

# Run only when executed; `source tools/stand.sh` (tools/test-stand.sh) just defines the functions.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
