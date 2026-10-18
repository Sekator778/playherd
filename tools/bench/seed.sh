#!/usr/bin/env bash
# ON the bench box: seeding and journal fabricator of the bench (design §4.1-4.3, step 3), run as a container of the SERVER image on the bench
# network (the host cannot reach 172.30.98.x, README). One call = one transaction on the database `herd` of project herdbench.
#   tools/bench/seed.sh seed     --players 10000 --seed 1 [--reset] [--cookies-out /results/cookies.tsv]
#   tools/bench/seed.sh history  --events 1000000 --bets-per-round 30 --seed 1
#   tools/bench/seed.sh hotround --bets 100000 --market BTC --lead-sec 90 --seed 1
#   tools/bench/seed.sh verify
#   tools/bench/seed.sh cookies  --players 10000 --seed 1 --out /results/cookies.tsv     (no database)
# Prerequisite: the server has been started once on this database (it creates the markets and the calendar origin) and is STOPPED now
# (the fabricator writes the journal behind its back; the server replays it on its next start): `docker stop herdbench-server-1`.
# Order: seed (once), then history and/or hotround, then `bench-ctl.sh up` — the rebuild replays the journal (HerdRebuild JFR event).
# Knobs: BENCH_GEN_CPUS (8-11,20-23,32-35,44-47), BENCH_FAB_HEAP (16g), BENCH_RESULTS (~/herd-bench/results). /results in the container
# is that directory; the call is logged to <results>/seed-<mode>-<time>.log. Round lengths: --hourly-sec / --daily-sec (180 / 900).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE=${1:?usage: seed.sh <seed|history|hotround|verify|cookies> [--key value ...]}
shift
GEN_CPUS=${BENCH_GEN_CPUS:-8-11,20-23,32-35,44-47}
HEAP=${BENCH_FAB_HEAP:-16g}
RESULTS=${BENCH_RESULTS:-$HOME/herd-bench/results}
JAR=${BENCH_FAB_JAR:-$HOME/herd-bench/bench-fabricator.jar}
IMAGE=$(sed -n 's/^HERD_BENCH_IMAGE=//p' "$ROOT/.env.bench")
PW=$(sed -n 's/^HERD_DB_PASSWORD=//p' "$ROOT/.env.bench")
mkdir -p "$RESULTS"
[ -f "$JAR" ] || { echo "no $JAR: tools/bench/ship.sh --worktree builds and ships it" >&2; exit 1; }
if [ "$MODE" != cookies ] && [ "$(docker inspect -f '{{.State.Running}}' herdbench-server-1 2>/dev/null || echo false)" = true ]; then
  echo "herdbench-server-1 is running: stop it first (docker stop herdbench-server-1); the fabricator writes the journal behind its back" >&2
  exit 1
fi
LOG="$RESULTS/seed-$MODE-$(date +%Y%m%d-%H%M%S).log"
docker run --rm --name "herdbench-fabricator-$MODE" --network herdbench_default --cpuset-cpus "$GEN_CPUS" --user "$(id -u):$(id -g)" \
  -e BENCH_DB_URL=jdbc:postgresql://db:5432/herd -e BENCH_DB_USER=herd -e BENCH_DB_PASSWORD="$PW" \
  -v "$JAR:/bench/bench-fabricator.jar:ro" -v "$RESULTS:/results" \
  --entrypoint java "$IMAGE" -Xms"$HEAP" -Xmx"$HEAP" -XX:+UseParallelGC \
  -cp "/deployments/lib/main/*:/deployments/app/*:/bench/bench-fabricator.jar" \
  crowdvsmarket.server.bench.BenchJournal "$MODE" "$@" 2>&1 | tee "$LOG"
