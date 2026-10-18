#!/usr/bin/env bash
# Bring the load-bench stand up and down. Runs ON the bench box, in ~/herd-bench/repo (put there by tools/bench/ship.sh).
#   tools/bench/bench-ctl.sh up       start db + server (compose project herdbench, never chainsvc/web)
#   tools/bench/bench-ctl.sh down     stop and remove ONLY the containers and network of project herdbench
#   tools/bench/bench-ctl.sh purge    down + remove the volume herdbench_pg (the bench database)
#   tools/bench/bench-ctl.sh image <tag>  pin HERD_BENCH_IMAGE in .env.bench (`up` and `ship.sh` also keep it equal to .bench-sha / the caller's HERD_BENCH_IMAGE)
#   tools/bench/bench-ctl.sh status   containers of the project, image, health
#   tools/bench/bench-ctl.sh snap <label> [dir]   Postgres statistics now (pg_stat_wal, pg_stat_database, pg_stat_statements, settings)
#                                      into <dir>/<label>.*.txt (default ~/herd-bench/results/snap); take one before and one after a run
#   tools/bench/bench-ctl.sh jfr [dir] stop the server (the JVM writes its JFR recording on exit; the JRE image has no jcmd) and copy
#                                      /tmp/herd.jfr to <dir> (default ~/herd-bench/results/snap); `up` starts it again
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

# The image tag in .env.bench follows .bench-sha (written by ship.sh) or the HERD_BENCH_IMAGE of the caller, on every call: an .env.bench
# left by an earlier ship must not keep an old tag (step 3 had to edit it by hand).
sync_image() {
  [ -f "$ENV_FILE" ] || return 0
  want=${HERD_BENCH_IMAGE:-herd-bench-server:${SHA:0:12}}
  # "unknown" means there is no .bench-sha: keep what the file has
  case $want in *:unknown) return 0 ;; esac
  tmp=$(mktemp) && { grep -v '^HERD_BENCH_IMAGE=' "$ENV_FILE"; echo "HERD_BENCH_IMAGE=$want"; } > "$tmp" && cat "$tmp" > "$ENV_FILE" && rm -f "$tmp"
}

case "${1:-status}" in
  image)   # image <tag>: pin the server image tag (e.g. the pre-instrumentation arm of the overhead run) and print it
    ensure_env
    HERD_BENCH_IMAGE=${2:?usage: bench-ctl.sh image <tag>} sync_image
    sed -n 's/^HERD_BENCH_IMAGE=//p' "$ENV_FILE"
    ;;
  up)
    ensure_env
    sync_image
    # refuse to share a name with anything that is not ours
    if docker network inspect herdbench_default >/dev/null 2>&1 &&
       [ "$(docker network inspect herdbench_default --format '{{index .Labels "com.docker.compose.project"}}')" != "$PROJECT" ]; then
      echo "network herdbench_default exists and is not a herdbench network" >&2; exit 1
    fi
    "${COMPOSE[@]}" up -d --no-build db server
    ;;
  down)
    ensure_env
    sync_image
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
  snap)
    label=${2:?usage: bench-ctl.sh snap <label> [dir]}
    dir=${3:-$HOME/herd-bench/results/snap}
    mkdir -p "$dir"
    psql_db() { docker exec -e PGPASSWORD="$(sed -n 's/^HERD_DB_PASSWORD=//p' "$ENV_FILE")" herdbench-db-1 psql -h 127.0.0.1 -U herd -d herd -X "$@"; }
    psql_db -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null
    psql_db -c "SELECT now() AS at, * FROM pg_stat_wal" > "$dir/$label.pg_stat_wal.txt"
    psql_db -c "SELECT now() AS at, * FROM pg_stat_database WHERE datname = 'herd'" > "$dir/$label.pg_stat_database.txt"
    psql_db -c "SELECT calls, round(total_exec_time::numeric, 1) AS total_ms, round(mean_exec_time::numeric, 3) AS mean_ms, rows, left(query, 100) AS query FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 25" > "$dir/$label.pg_stat_statements.txt"
    psql_db -c "SELECT name, setting, unit FROM pg_settings WHERE name IN ('synchronous_commit','fsync','wal_sync_method','shared_buffers','wal_buffers','commit_delay','track_wal_io_timing','log_min_duration_statement','max_connections') ORDER BY name" > "$dir/$label.pg_settings.txt"
    log "snapshot $label -> $dir"
    ;;
  jfr)
    dir=${2:-$HOME/herd-bench/results/snap}
    mkdir -p "$dir"
    docker stop -t 60 herdbench-server-1 >/dev/null
    docker cp herdbench-server-1:/tmp/herd.jfr "$dir/herd-$(date +%Y%m%d-%H%M%S).jfr"
    log "JFR dump -> $dir"
    ;;
  *) echo "usage: bench-ctl.sh {up|down|purge|image <tag>|status|snap <label> [dir]|jfr [dir]}" >&2; exit 2 ;;
esac
