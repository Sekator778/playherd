#!/usr/bin/env bash
# ON the bench box: raw once-a-second samples for the USE table (design §6.4): per-CPU counters, runnable tasks, the DB disk, the bench bridge.
#   tools/bench/use-sampler.sh <out.raw> [seconds]      runs until killed (or for <seconds>); parse with tools/bench/use_table.py
# Reads only /proc (no sysstat needed). Knob: BENCH_DISK (sdc).
set -u
OUT=${1:?usage: use-sampler.sh <out.raw> [seconds]}
LIMIT=${2:-0}
DISK=${BENCH_DISK:-sdc}
# The bridge interface itself counts nothing for container-to-container traffic (it is forwarded between veths), so the network row is
# the eth0 of the server container, read from its network namespace: /proc/<pid>/net/dev (the traffic the server sees).
PID=$(docker inspect -f '{{.State.Pid}}' herdbench-server-1 2>/dev/null || echo 0)
NETDEV=/proc/$PID/net/dev
[ -r "$NETDEV" ] || NETDEV=/dev/null
BR=eth0
{ echo "# disk=$DISK netdev=$NETDEV iface=$BR started=$(date +%s.%N)"; } > "$OUT"
i=0
while :; do
  {
    echo "T $(date +%s.%N)"
    grep -E '^cpu[0-9]+ ' /proc/stat
    grep -E '^procs_running' /proc/stat
    grep -E " $DISK " /proc/diskstats | sed 's/^/D /'
    grep -E "^ *$BR:" "$NETDEV" | sed 's/^ */N /'
  } >> "$OUT"
  i=$((i + 1)); [ "$LIMIT" -gt 0 ] && [ "$i" -ge "$LIMIT" ] && break
  sleep 1
done
