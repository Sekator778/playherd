#!/usr/bin/env bash
# ON the bench box (~/herd-bench/repo): the runs of step 4. One call = one primitive; the lease and the order of runs are the caller's job.
#   tools/bench/run.sh start                       bench-ctl up (db + server with HERD_BENCH_IMAGE / HERD_BENCH_JVM_OPTS from the environment), wait until ready
#   tools/bench/run.sh stop                        stop the server container (db stays)
#   tools/bench/run.sh snapdb                      server stopped: copy database herd to the template herd_snap (the seeded state, R-9)
#   tools/bench/run.sh restore                     server stopped: database herd := herd_snap (known state before a run)
#   tools/bench/run.sh gatling <label> <Sim> [KEY=VAL ...]   one Gatling run (herdbench.<Sim>) with the USE sampler and Postgres snapshots
#   tools/bench/run.sh wrk <label> <rate> <seconds> <sessions> <mode> [threads] [connections]   one wrk2 run (constant throughput)
#   tools/bench/run.sh report <label> <ladder> [skipSec] [class]   per-step table + <label>.steps.csv of a Gatling run
#   tools/bench/run.sh jfr <label>                 stop the server (the JVM writes its JFR on exit) and copy the recording into the run folder
# Results: $BENCH_RESULTS/<label>/ (default ~/herd-bench/results). The generator image tag is in ~/herd-bench/gen-image (written by ship-gen),
# or BENCH_GEN_IMAGE. Cores (README): Postgres 0-7, server 12-19, generators 8-11,20-23,32-35,44-47 (knobs BENCH_GEN_CPUS, HERD_BENCH_*_CPUS).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/tools/bench"
RES=${BENCH_RESULTS:-$HOME/herd-bench/results}
GEN_CPUS=${BENCH_GEN_CPUS:-8-11,20-23,32-35,44-47}
GEN_IMAGE=${BENCH_GEN_IMAGE:-$(cat "$HOME/herd-bench/gen-image" 2>/dev/null || true)}
GEN_HEAP=${BENCH_GEN_HEAP:-8g}
ENV_FILE="$ROOT/.env.bench"
log() { printf '[run] %s\n' "$*" >&2; }
pw() { sed -n 's/^HERD_DB_PASSWORD=//p' "$ENV_FILE"; }
psql_db() { docker exec -e PGPASSWORD="$(pw)" herdbench-db-1 psql -h 127.0.0.1 -U herd -v ON_ERROR_STOP=1 -X "$@"; }

wait_ready() {
  for _ in $(seq 1 180); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' herdbench-server-1 2>/dev/null)" = healthy ] && { log "server healthy"; return 0; }
    sleep 2
  done
  echo "server did not become healthy" >&2; docker logs --tail 30 herdbench-server-1 >&2; return 1
}

manifest() {  # manifest <dir>
  {
    echo "date: $(date -Is)  epoch: $(date +%s.%N)"; echo "sha: $(cat "$ROOT/.bench-sha" 2>/dev/null)"
    echo "server image: $(sed -n 's/^HERD_BENCH_IMAGE=//p' "$ENV_FILE") $(docker inspect -f '{{.Image}}' herdbench-server-1 2>/dev/null)"
    echo "generator image: $GEN_IMAGE"
    echo "server JDK_JAVA_OPTIONS: $(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' herdbench-server-1 2>/dev/null | grep '^JDK_JAVA_OPTIONS=' || echo '(unset)')"
    echo "server cpuset: $(docker inspect -f '{{.HostConfig.CpusetCpus}}' herdbench-server-1 2>/dev/null)  db cpuset: $(docker inspect -f '{{.HostConfig.CpusetCpus}}' herdbench-db-1 2>/dev/null)  generator cpuset: $GEN_CPUS"
    echo "-- server HERD_ env"; docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' herdbench-server-1 2>/dev/null | grep '^HERD_' | grep -v 'TOKEN\|PASSWORD' | sort
    echo "-- uptime"; uptime
    echo "-- foreign containers"; docker ps --format '{{.Names}}' | grep -v '^herdbench-' || echo none
    echo "-- foreign java/mvn/chia/rsync processes"; pgrep -fa 'java|mvn|chia|rsync' | grep -v "pgrep\|herdbench" | cut -c1-110 || echo none
    echo "-- lscpu"; lscpu | grep -E 'Model name|^CPU\(s\)|NUMA node[0-9]'
  } > "$1/manifest.txt" 2>&1 || true
}

run_with_instruments() {  # run_with_instruments <label> <cmd...>   (the command runs in the foreground)
  local label=$1; shift
  local dir="$RES/$label"; mkdir -p "$dir"
  manifest "$dir"
  "$HERE/bench-ctl.sh" snap before "$dir" >/dev/null 2>&1 || true
  "$HERE/use-sampler.sh" "$dir/use.raw" >/dev/null 2>&1 &
  local sp=$!
  date +%s.%N > "$dir/t_start"
  local rc=0
  "$@" || rc=$?
  date +%s.%N > "$dir/t_end"
  kill "$sp" 2>/dev/null || true; wait "$sp" 2>/dev/null || true
  "$HERE/bench-ctl.sh" snap after "$dir" >/dev/null 2>&1 || true
  return $rc
}

case "${1:-}" in
  start)
    "$HERE/bench-ctl.sh" up >&2
    wait_ready ;;
  stop)
    docker stop -t 60 herdbench-server-1 >/dev/null ;;
  snapdb)
    psql_db -d postgres -qc "DROP DATABASE IF EXISTS herd_snap" -c "CREATE DATABASE herd_snap TEMPLATE herd OWNER herd" && log "template herd_snap created" ;;
  restore)
    psql_db -d postgres -qc "DROP DATABASE IF EXISTS herd WITH (FORCE)" -c "CREATE DATABASE herd TEMPLATE herd_snap OWNER herd" && log "database herd restored from herd_snap" ;;
  gatling)
    label=${2:?label}; sim=${3:?Sim}; shift 3
    [ -n "$GEN_IMAGE" ] || { echo "no generator image (ship-gen)" >&2; exit 1; }
    dir="$RES/$label"; mkdir -p "$dir"
    envs=(-e BENCH_RF=/results/$label -e BENCH_RD="$label" -e BENCH_COOKIES=/results/cookies.tsv -e BENCH_URL=http://server:8081)
    for kv in "$@"; do envs+=(-e "$kv"); done
    run_with_instruments "$label" docker run --rm --name "herdbench-gen-$label" --network herdbench_default --cpuset-cpus "$GEN_CPUS" \
      --user "$(id -u):$(id -g)" -v "$RES:/results" "${envs[@]}" "$GEN_IMAGE" \
      java -Xms"$GEN_HEAP" -Xmx"$GEN_HEAP" -XX:+UseG1GC -XX:MaxGCPauseMillis=30 --add-opens=java.base/java.lang=ALL-UNNAMED \
      -cp "/gen/lib/*:/gen/classes" io.gatling.app.Gatling -s "herdbench.$sim" -rf "/results/$label/gatling" -rd "$label" -nr \
      > "$dir/gatling.out" 2>&1
    ;;
  wrk)
    label=${2:?label}; rate=${3:?rate}; secs=${4:?seconds}; sessions=${5:?sessions}; mode=${6:?mode}; thr=${7:-8}; conn=${8:-256}
    [ -n "$GEN_IMAGE" ] || { echo "no generator image (ship-gen)" >&2; exit 1; }
    dir="$RES/$label"; mkdir -p "$dir"
    run_with_instruments "$label" docker run --rm --name "herdbench-wrk-$label" --network herdbench_default --cpuset-cpus "$GEN_CPUS" \
      --user "$(id -u):$(id -g)" -v "$RES:/results" "$GEN_IMAGE" \
      wrk -t"$thr" -c"$conn" -d"${secs}s" -R"$rate" --latency -s /gen/herd.lua http://server:8081 -- /results/cookies.tsv "$sessions" "$mode" \
      > "$dir/wrk.out" 2>&1
    ;;
  report)   # report <label> <ladder> [skip] [cls]: the per-step table of a Gatling run (herdbench.Report, inside the generator image)
    label=${2:?label}; ladder=${3:?ladder}; skip=${4:-5}; cls=${5:-ok}
    docker run --rm --user "$(id -u):$(id -g)" -v "$RES:/results" "$GEN_IMAGE" java -cp "/gen/lib/*:/gen/classes" herdbench.Report \
      "/results/$label/$label" "$ladder" "$skip" 5 10 "$cls" ;;
  jfr)
    label=${2:?label}; dir="$RES/$label"; mkdir -p "$dir"
    docker stop -t 90 herdbench-server-1 >/dev/null
    docker cp herdbench-server-1:/tmp/herd.jfr "$dir/herd.jfr" && log "JFR -> $dir/herd.jfr" ;;
  *) sed -n 2,15p "$0" >&2; exit 2 ;;
esac
