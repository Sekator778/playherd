#!/usr/bin/env bash
# S-F (design §3): the ceiling of Postgres itself, without our code. Runs ON the bench box after bench-ctl.sh up.
#   tools/bench/pgbench-ceiling.sh [results-dir]
# One INSERT of one game_events-shaped row per transaction (tools/bench/sql/insert_game_event.sql) into the table
# bench_game_events of the throwaway database bench_pg (never the stand's `herd` database), pgbench -T 300,
# -c 1 and -c 8, each with synchronous_commit=on and off, after a 30 s warm-up. pgbench runs in a container of the SAME image
# (postgres:16-alpine) on the bench network, pinned to the generator cores. The server container is stopped for the
# run (it would write to the same disk and CPU). Also pg_test_fsync on the data directory of the bench database.
# Per run it saves: pgbench output, per-transaction log (-l), pg_stat_wal before/after, /proc/diskstats of the DB disk
# before/after, and p50/p90/p99/p99.9/max from the log (summary.tsv). Only objects of project herdbench are touched.
# Knobs: BENCH_DURATION (300), BENCH_GEN_CPUS (8-11,20-23,32-35,44-47), BENCH_DISK (sdc), BENCH_CLIENTS ("1 8").
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/tools/bench"
DURATION=${BENCH_DURATION:-300}
GEN_CPUS=${BENCH_GEN_CPUS:-8-11,20-23,32-35,44-47}
DISK=${BENCH_DISK:-sdc}
CLIENTS=${BENCH_CLIENTS:-"1 8"}
OUT=${1:-$HOME/herd-bench/results/$(date +%Y%m%d-%H%M%S)-pgbench}
IMAGE=postgres:16-alpine
NET=herdbench_default
DB=herdbench-db-1
PW=$(grep '^HERD_DB_PASSWORD=' "$ROOT/.env.bench" | cut -d= -f2)
mkdir -p "$OUT"
log() { printf '[pgbench] %s\n' "$*" >&2; }
psql_db() { docker exec -e PGPASSWORD="$PW" "$DB" psql -U herd -v ON_ERROR_STOP=1 "$@"; }

docker inspect "$DB" >/dev/null   # the bench db must be up
docker stop herdbench-server-1 >/dev/null 2>&1 || true

(
  set +e
  echo "date: $(date -Is)"; echo "sha: $(cat "$ROOT/.bench-sha" 2>/dev/null)"
  echo "image: $(docker image inspect $IMAGE --format '{{.Id}}')"
  echo "db cpuset: $(docker inspect $DB --format '{{.HostConfig.CpusetCpus}}')  generator cpuset: $GEN_CPUS"
  echo "duration: $DURATION s"; uptime; echo "-- lscpu"; lscpu | grep -E 'Model name|^CPU\(s\)|NUMA node'
  echo "-- disk $DISK"; lsblk -d -o NAME,ROTA,SIZE,MODEL "/dev/$DISK"; cat "/sys/block/$DISK/queue/scheduler"; findmnt -no SOURCE,FSTYPE,OPTIONS /var/lib/docker
  echo "-- foreign load now"; docker ps --format '{{.Names}}' | grep -v '^herdbench-' || echo none; pgrep -fa 'java|mvn|rsync|chia' | grep -v pgrep | cut -c1-100
) > "$OUT/manifest.txt" 2>&1 || true

psql_db -d herd -qc "DROP DATABASE IF EXISTS bench_pg" -c "CREATE DATABASE bench_pg" >/dev/null
docker exec -i -e PGPASSWORD="$PW" "$DB" psql -U herd -d bench_pg -v ON_ERROR_STOP=1 -q < "$HERE/sql/bench_game_events.sql"
psql_db -d bench_pg -Atc "SELECT name||'='||setting||coalesce(' x '||unit,'') FROM pg_settings WHERE name IN ('fsync','synchronous_commit','wal_sync_method','full_page_writes','wal_level','commit_delay','commit_siblings','shared_buffers','max_wal_size','checkpoint_timeout','wal_buffers','track_wal_io_timing','max_connections','data_directory','server_version') ORDER BY 1" > "$OUT/pg_settings.txt"

log "pg_test_fsync on the bench data directory"
docker exec "$DB" pg_test_fsync -s 5 -f /var/lib/postgresql/data/pg_test_fsync.tmp > "$OUT/pg_test_fsync.txt" 2>&1 || true
docker exec "$DB" rm -f /var/lib/postgresql/data/pg_test_fsync.tmp

disk_stats() { grep -E " $DISK " /proc/diskstats; }
run_pgbench() {  # run_pgbench <label> <clients> <sync on|off> <seconds> <log yes|no>
  local label=$1 c=$2 sync=$3 secs=$4 logit=$5 j=$(( $2 < 4 ? $2 : 4 ))
  local extra=()
  [ "$logit" = yes ] && extra=(-l --log-prefix "/results/${label}.txlog")
  docker run --rm --name "herdbench-pgbench-$label" --network "$NET" --cpuset-cpus "$GEN_CPUS" --user "$(id -u):$(id -g)" \
    -e PGPASSWORD="$PW" -e PGOPTIONS="-c synchronous_commit=$sync" -v "$OUT:/results" -v "$HERE/sql:/sql:ro" \
    --entrypoint pgbench "$IMAGE" -h db -U herd -n -f /sql/insert_game_event.sql -c "$c" -j "$j" -T "$secs" -P 30 -r "${extra[@]}" bench_pg
}

log "warm-up 30 s (discarded)"
psql_db -d bench_pg -qc "TRUNCATE bench_game_events RESTART IDENTITY" -c "CHECKPOINT" >/dev/null
run_pgbench warmup 8 on 30 no > "$OUT/warmup.out" 2>&1

for c in $CLIENTS; do
  for sync in on off; do
    label="c${c}_sync${sync}"
    log "$label: $DURATION s"
    psql_db -d bench_pg -qc "TRUNCATE bench_game_events RESTART IDENTITY" -c "CHECKPOINT" -c "SELECT pg_stat_reset_shared('wal')" >/dev/null
    sleep 5
    disk_stats > "$OUT/$label.disk.before"; date +%s.%N > "$OUT/$label.t0"
    run_pgbench "$label" "$c" "$sync" "$DURATION" yes > "$OUT/$label.out" 2>&1
    disk_stats > "$OUT/$label.disk.after"; date +%s.%N > "$OUT/$label.t1"
    psql_db -d bench_pg -xc "SELECT * FROM pg_stat_wal" -c "SELECT count(*) AS rows_inserted, pg_size_pretty(pg_total_relation_size('bench_game_events')) AS table_size FROM bench_game_events" > "$OUT/$label.pg_stat_wal" 2>&1
  done
done

# percentiles from the per-transaction logs (pgbench log: client tx_no latency_us script_no epoch_s epoch_us)
python3 -I - "$OUT" <<'PY'
import glob, os, sys
out = sys.argv[1]
rows = []
by_label = {}
for f in sorted(glob.glob(os.path.join(out, "c*_sync*.txlog*"))):
    by_label.setdefault(os.path.basename(f).split(".txlog")[0], []).append(f)   # one file per pgbench thread
for label, files in sorted(by_label.items()):
    lat = []
    for f in files:
        for line in open(f):
            p = line.split()
            if len(p) >= 3: lat.append(int(p[2]))
    lat.sort()
    n = len(lat)
    if not n: continue
    q = lambda x: lat[min(n - 1, int(x * n))] / 1000.0
    rows.append((label, n, q(.5), q(.9), q(.99), q(.999), lat[-1] / 1000.0))
with open(os.path.join(out, "summary.tsv"), "w") as s:
    s.write("label\ttransactions\tp50_ms\tp90_ms\tp99_ms\tp99.9_ms\tmax_ms\n")
    for r in rows:
        s.write("%s\t%d\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\n" % r)
print(open(os.path.join(out, "summary.tsv")).read())
PY
log "done: $OUT"
