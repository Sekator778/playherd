#!/usr/bin/env bash
# ON the bench box: design §5 I-0, the overhead run. Profile A1 (spread, tech) at RATE bets/s (30 % of the measured ceiling), three repeats of
#   a = the commit before the instrumentation (image OLD), JFR off
#   b = after the instrumentation (image NEW), JFR off  (HERD_BENCH_JVM_OPTS empty)
#   c = after the instrumentation (image NEW), JFR on   (bench.jfc, the stand's default)
# interleaved a1 b1 c1 a2 b2 c2 a3 b3 c3 (drift of the box hits all arms alike); each run starts from the same database (herd_snap),
# warms up WARM_SEC at half the rate and measures MEASURE_SEC (two full 3-minute rounds by default).
#   OLD=herd-bench-server:20c14cff6bd9 NEW=herd-bench-server:5e7eb170d7a0 RATE=600 tools/bench/step4-i0.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="$HERE/run.sh"
: "${OLD:?}" "${NEW:?}" "${RATE:?}"
WARM_SEC=${WARM_SEC:-180}
MEASURE_SEC=${MEASURE_SEC:-360}
PLAYERS=${PLAYERS:-100000}
HALF=$(python3 -c "print(max(1, round($RATE / 2)))")
LADDER="$HALF:$WARM_SEC,$RATE:$MEASURE_SEC"
log() { printf '%s [i0] %s\n' "$(date +%H:%M:%S)" "$*"; }
for n in 1 2 3; do
  for arm in a b c; do
    label="i0-$arm$n"
    case $arm in a) img=$OLD; jvm="" ;; b) img=$NEW; jvm="" ;; c) img=$NEW; jvm=__default__ ;; esac
    log "$label: image $img, JFR $([ "$arm" = c ] && echo on || echo off)"
    "$RUN" stop 2>/dev/null || true
    "$RUN" restore
    if [ "$jvm" = __default__ ]; then unset HERD_BENCH_JVM_OPTS; else export HERD_BENCH_JVM_OPTS=""; fi
    HERD_BENCH_IMAGE=$img "$RUN" start
    sleep 15
    "$RUN" gatling "$label" SimBets BENCH_LADDER="$LADDER" BENCH_PLAYERS=$PLAYERS BENCH_SHAPE=${SHAPE:-hourly}
    "$RUN" report "$label" "$LADDER" ${SKIP:-20} > "${BENCH_RESULTS:-$HOME/herd-bench/results}/$label/report.md" || true
    [ "$arm" = c ] && "$RUN" jfr "$label" || "$RUN" stop
  done
done
log "done"
