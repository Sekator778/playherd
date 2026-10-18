#!/usr/bin/env bash
# Bring the load-bench stand up and down. Runs ON the bench box, in ~/herd-bench/repo (put there by tools/bench/ship.sh).
#   tools/bench/bench-ctl.sh up       start db + server (compose project herdbench, never chainsvc/web)
#   tools/bench/bench-ctl.sh down     stop and remove ONLY the containers and network of project herdbench
#   tools/bench/bench-ctl.sh purge    down + remove the volume herdbench_pg (the bench database)
#   tools/bench/bench-ctl.sh status   containers of the project, image, health
# the bench box is shared: this script only ever touches objects of the compose project `herdbench`
# (names herdbench-*, network herdbench_default, volume herdbench_pg). The lease is the caller's job (README.md).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT=herdbench
ENV_FILE="$ROOT/.env.bench"
SHA=$(cat "$ROOT/.bench-sha" 2>/dev/null || echo unknown)
COMPOSE=(docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$ROOT/docker-compose.yml" -f "$ROOT/tools/bench/docker-compose.bench.yml")
log() { printf '[bench] %s\n' "$*" >&2; }

ensure_env() {
  [ -f "$ENV_FILE" ] && return 0
  umask 077
  rand() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32; }
  {
    echo "HERD_DB_PASSWORD=$(rand)"
    echo "HERD_CHAINSVC_TOKEN=$(rand)"
    echo "HERD_OPERATOR_TOKEN=$(rand)"
    echo "C2_SVC_TOKEN=$(rand)"
    echo "HERD_BENCH_IMAGE=herd-bench-server:${SHA:0:12}"
  } > "$ENV_FILE"
  log "wrote $ENV_FILE (new random secrets)"
}

case "${1:-status}" in
  up)
    ensure_env
    # refuse to share a name with anything that is not ours
    if docker network inspect herdbench_default >/dev/null 2>&1 &&
       [ "$(docker network inspect herdbench_default --format '{{index .Labels "com.docker.compose.project"}}')" != "$PROJECT" ]; then
      echo "network herdbench_default exists and is not a herdbench network" >&2; exit 1
    fi
    "${COMPOSE[@]}" up -d --no-build db server
    ;;
  down)
    ensure_env
    "${COMPOSE[@]}" down --remove-orphans
    ;;
  purge)
    ensure_env
    "${COMPOSE[@]}" down --remove-orphans
    docker volume rm herdbench_pg 2>/dev/null || true
    ;;
  status)
    docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
    ;;
  *) echo "usage: bench-ctl.sh {up|down|purge|status}" >&2; exit 2 ;;
esac
