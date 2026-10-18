#!/usr/bin/env bash
# ON the bench box: step 4, item 3 of the task: the generator self-check and the first S-A (A1) ceiling, one run per rate step with a
# stop at the first step that breaks the criteria. Needs: seeded database (herd_snap), images shipped. Run under nohup.
#   HERD_BENCH_IMAGE=<new image> tools/bench/step4-ceiling.sh
# Knobs: SHAPE=spread|hourly (default spread), SELFCHECK=0 skips the self-check, DRAIN (s between steps), TAG (result prefix). Steps: STEPS="100 200 500 ..." seconds per step STEP_SEC (190: one full 3-minute round, boundary included), skipped at the start SKIP (20).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="$HERE/run.sh"
STEPS=${STEPS:-"100 200 500 1000 1500 2000 3000 5000 8000"}
STEP_SEC=${STEP_SEC:-190}
SKIP=${SKIP:-20}
PLAYERS=${PLAYERS:-100000}
TAG=${TAG:-ceil}
RES=${BENCH_RESULTS:-$HOME/herd-bench/results}
log() { printf '%s [ceiling] %s\n' "$(date +%H:%M:%S)" "$*"; }

"$RUN" stop 2>/dev/null || true
"$RUN" restore
"$RUN" start
sleep 20
if [ "${SELFCHECK:-1}" = 1 ]; then
  log "self-check: serverInfo, Gatling and wrk2 at the same rate"
  "$RUN" gatling selfcheck-gatling SimPoll BENCH_LADDER=5000:60 BENCH_SESSIONS=1000 BENCH_METHODS=serverInfo
  "$RUN" wrk selfcheck-wrk 5000 60 1000 serverInfo 8 256
  sleep 10
fi
for r in $STEPS; do
  label="$TAG-$r"
  log "step $r/s for $STEP_SEC s"
  "$RUN" gatling "$label" SimBets BENCH_LADDER="$r:$STEP_SEC" BENCH_PLAYERS=$PLAYERS BENCH_SHAPE=${SHAPE:-spread}
  "$RUN" report "$label" "$r:$STEP_SEC" "$SKIP" | tee "$RES/$label/report.md"
  # criteria (design §3 S-A): accepted >= 99.9 % of arrivals and p99.9 <= 500 ms; stop one step after the first miss of the loose test
  # (accepted < 99 % of arrivals or p99.9 > 2000 ms): the backlog of a saturated step would spoil the next one
  # ROUND_NOT_OPEN_FOR_BETTING is the round boundary (a row accepts bets for its next round only after the scheduler has settled the
  # previous one), counted apart from the acceptance test
  read -r arrived okps notopen p999 stop < <(python3 - "$RES/$label/$label.steps.csv" "$((STEP_SEC - SKIP))" <<'PY'
import csv, re, sys
r = list(csv.reader(open(sys.argv[1])))[1]
win = float(sys.argv[2])
arrived, ok, rej, p999 = float(r[2]), float(r[4]), r[6], r[9]
m = re.search(r'ROUND_NOT_OPEN_FOR_BETTING=(\d+)', rej)
notopen = int(m.group(1)) if m else 0
stop = int((arrived > 0 and ok < 0.99 * arrived and ok + notopen / win < 0.99 * arrived) or (p999 not in ('-', '') and float(p999) > 2000))
print(arrived, ok, notopen, p999, stop)
PY
)
  log "step $r: arrived $arrived/s, accepted $okps/s, ROUND_NOT_OPEN rejects $notopen in the window, p99.9 $p999 ms"
  [ "$stop" = 1 ] && { log "loose criteria broken at $r/s: stop"; break; }
  sleep ${DRAIN:-20}
done
"$RUN" jfr "$TAG"
log "done"
