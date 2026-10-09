#!/usr/bin/env bash
# tools/vps/remote.sh -- the part of the deploy that runs ON the VPS, as the deploy user. tools/vps/deploy.sh puts it there
# with the rest of the tree (rsync) and calls it over ssh; nothing here is meant to be typed by hand, though it can be.
#
#   remote.sh job start NAME -- COMMAND...   run COMMAND detached from the ssh session (a dropped connection, a laptop that
#                                            went to sleep, does not stop a build); a job of that name that is already
#                                            running is left alone
#   remote.sh job follow NAME                print the job's log as it grows and end with the job's exit status
#   remote.sh job wait NAME                  like follow when the job is running, nothing at all when it is not
#   remote.sh job status NAME                running | ok | failed:<status> | killed | none
#   remote.sh edge                           put edge/conf/Caddyfile.new (sent by deploy.sh) in place and start or reload Caddy
#   remote.sh build                          docker compose build of the stand (images only: nothing is started)
#   remote.sh up                             start the stand: tools/stand.sh's `up`, with the web published on 127.0.0.1 only
#   remote.sh stop                           stop the stand's containers (kept, with their data)
#   remote.sh verify DOMAIN [--no-ratelimit] [--insecure]
#                                            checks that need the VPS itself; exit status 1 if one fails
#                                            (--insecure: the certificate is the staging CA's, curl -k)
#
# bash 3.2-compatible on purpose: tools/vps/test.sh runs the job runner on a Mac.

set -euo pipefail

APP_DIR="${HERD_APP_DIR:-${HOME}/herd}"
EDGE_DIR="${HERD_EDGE_DIR:-${HOME}/edge}"
JOBS_DIR="${HERD_JOBS_DIR:-${HOME}/.herd-jobs}"
C2_DIR="${HERD_C2_DIR:-${HOME}/.c2/testnet11}"
OPERATOR_NAME="${HERD_OPERATOR_NAME:-operator}"
PROJECT=herd
EDGE_CONTAINER=edge-caddy
WEB_BIND=127.0.0.1
WEB_PORT=8480
GATEWAY=172.28.77.1 # docker-compose.yml: the herd_default network's gateway; chainsvc listens on it, nginx trusts it
MEMINFO="${HERD_MEMINFO_FILE:-/proc/meminfo}"
MEMORY_FLOOR_PCT=25 # the oracle profile (env-init.sh) pins the server's heap at this share of the RAM; verify warns below it

log() { printf '[remote] %s\n' "$*" >&2; }
die() {
  printf '[remote] error: %s\n' "$*" >&2
  exit 1
}

compose() { docker compose -p "${PROJECT}" -f "${APP_DIR}/docker-compose.yml" "$@"; }

# --- detached jobs ---------------------------------------------------------------------------------------------------------

job_dir() { printf '%s/%s' "${JOBS_DIR}" "$1"; }

# A job is alive while its wrapper process runs. A wrapper that has ended but whose parent does not collect it (an init that does not
# reap, as in a container whose first process is `sleep`) stays in the process table as a zombie and still answers kill -0.
job_alive() { # job_alive DIR
  local pid state
  pid="$(cat "$1/pid" 2>/dev/null || true)"
  [[ -n "${pid}" ]] || return 1
  kill -0 "${pid}" 2>/dev/null || return 1
  state="$(ps -o stat= -p "${pid}" 2>/dev/null | tr -d ' ' || true)"
  [[ "${state}" != Z* ]]
}

job_status_word() { # job_status_word NAME -> running | ok | failed:N | killed | none
  local dir status
  dir="$(job_dir "$1")"
  if [[ ! -d "${dir}" || ! -f "${dir}/pid" ]]; then
    echo none
  elif job_alive "${dir}"; then
    echo running
  elif [[ -f "${dir}/status" ]]; then
    status="$(cat "${dir}/status")"
    if [[ "${status}" == 0 ]]; then echo ok; else echo "failed:${status}"; fi
  else
    echo killed
  fi
}

# The wrapper of a job: writes its own pid (setsid may fork, so $! could name a process that is gone at once) and, when the
# command ends, the command's exit status. Arguments: the job directory, the directory to run in, the command.
# shellcheck disable=SC2016 # single quotes on purpose: the text is for the wrapper shell, not for this one
JOB_WRAPPER='dir="$1"; wd="$2"; shift 2; echo $$ >"${dir}/pid"; cd "${wd}" || { echo "no ${wd}" >&2; echo 1 >"${dir}/status"; exit 1; }; "$@"; rc=$?; echo "${rc}" >"${dir}/status"; exit "${rc}"'

job_start() {
  local name="$1"
  shift
  [[ "${1:-}" == "--" ]] && shift
  (($# > 0)) || die "job start: no command"
  local dir i launcher
  dir="$(job_dir "${name}")"
  mkdir -p "${dir}"
  if job_alive "${dir}"; then
    echo "job ${name} is already running (pid $(cat "${dir}/pid"))"
    return 0
  fi
  [[ ! -f "${dir}/log" ]] || mv -f "${dir}/log" "${dir}/log.1"
  rm -f "${dir}/status" "${dir}/pid"
  launcher=(nohup)
  if command -v setsid >/dev/null 2>&1; then launcher=(setsid nohup); fi
  "${launcher[@]}" bash -c "${JOB_WRAPPER}" _ "${dir}" "${APP_DIR}" "$@" >"${dir}/log" 2>&1 </dev/null &
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    [[ -s "${dir}/pid" ]] && break
    sleep 0.2
  done
  [[ -s "${dir}/pid" ]] || die "job ${name} did not start"
  echo "job ${name} started (pid $(cat "${dir}/pid"))"
}

# Prints what the log gained since OFFSET bytes; the new offset is left in JOB_OFFSET.
job_print_new() { # job_print_new LOGFILE
  local size
  size="$(wc -c <"$1" 2>/dev/null || echo 0)"
  size=$((size + 0))
  if ((size > JOB_OFFSET)); then
    tail -c "+$((JOB_OFFSET + 1))" "$1" | head -c "$((size - JOB_OFFSET))"
    JOB_OFFSET="${size}"
  fi
}

job_follow() {
  local name="$1" dir status
  dir="$(job_dir "${name}")"
  [[ -d "${dir}" ]] || {
    echo "no job named ${name}" >&2
    return 3
  }
  JOB_OFFSET=0
  while :; do
    job_print_new "${dir}/log"
    job_alive "${dir}" || break
    sleep 1
  done
  job_print_new "${dir}/log" # whatever was written between the last read and the end
  if [[ -f "${dir}/status" ]]; then
    status="$(cat "${dir}/status")"
    return "${status}"
  fi
  echo "job ${name} ended without an exit status (killed?)" >&2
  return 137
}

job_wait() {
  local dir
  dir="$(job_dir "$1")"
  if [[ -d "${dir}" ]] && job_alive "${dir}"; then
    job_follow "$1" || true
  fi
  return 0
}

cmd_job() {
  local sub="${1:-}"
  shift || true
  case "${sub}" in
    start)
      (($# >= 1)) || die "usage: job start NAME -- COMMAND..."
      job_start "$@"
      ;;
    follow)
      job_follow "${1:?usage: job follow NAME}"
      ;;
    wait)
      job_wait "${1:?usage: job wait NAME}"
      ;;
    status)
      job_status_word "${1:?usage: job status NAME}"
      ;;
    *) die "usage: job start|follow|wait|status NAME ..." ;;
  esac
}

# Caddy in front of the stand (tools/vps/edge/compose.yml, its own compose project "edge"). deploy.sh has sent the rendered Caddyfile as
# conf/Caddyfile.new. It is checked with the image that will run it before anything live is touched; a running Caddy is reloaded (no
# dropped connections), a stopped one is started.
cmd_edge() {
  local conf="${EDGE_DIR}/conf" changed=1 running image
  [[ -f "${conf}/Caddyfile.new" ]] || die "no ${conf}/Caddyfile.new: tools/vps/deploy.sh sends it first"
  [[ -f "${APP_DIR}/tools/vps/edge/compose.yml" ]] || die "no ${APP_DIR}/tools/vps/edge/compose.yml: the tree is not on the VPS yet"
  mkdir -p "${conf}" "${EDGE_DIR}/download"
  cp -f "${APP_DIR}/tools/vps/edge/compose.yml" "${EDGE_DIR}/compose.yml"
  image="$(awk '$1 == "image:" {print $2; exit}' "${EDGE_DIR}/compose.yml")"
  [[ -n "${image}" ]] || die "no image in ${EDGE_DIR}/compose.yml"
  log "checking the new Caddyfile with ${image}"
  docker run --rm -v "${conf}:/etc/caddy:ro" "${image}" caddy validate --config /etc/caddy/Caddyfile.new --adapter caddyfile >/dev/null \
    || die "Caddy rejects ${conf}/Caddyfile.new (run it by hand without >/dev/null to read why); the running configuration was not touched"
  if [[ -f "${conf}/Caddyfile" ]] && cmp -s "${conf}/Caddyfile.new" "${conf}/Caddyfile"; then changed=0; fi
  mv -f "${conf}/Caddyfile.new" "${conf}/Caddyfile"
  running="$(docker inspect -f '{{.State.Running}}' "${EDGE_CONTAINER}" 2>/dev/null || echo false)"
  docker compose -p edge -f "${EDGE_DIR}/compose.yml" up -d
  if [[ "${running}" == true && "${changed}" == 1 ]]; then
    log "the Caddyfile changed: reloading Caddy"
    docker exec "${EDGE_CONTAINER}" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 \
      || {
        log "the reload failed: restarting the container"
        docker restart "${EDGE_CONTAINER}" >/dev/null
      }
  fi
  log "Caddy: $(docker inspect -f '{{.State.Status}}' "${EDGE_CONTAINER}" 2>/dev/null || echo unknown)"
}

# --- the stand --------------------------------------------------------------------------------------------------------------

require_app() {
  [[ -x "${APP_DIR}/tools/stand.sh" ]] || die "no stand in ${APP_DIR}: run tools/vps/deploy.sh from the operator's machine"
  [[ -s "${APP_DIR}/.env" ]] || die "no ${APP_DIR}/.env: run tools/vps/secrets.sh (--env-only) first"
}

# One image at a time, the Rust one first: compose builds the three together otherwise, and a Rust, a Maven and an npm build at once want
# more memory than a 4 GB VPS has. The services that have a build section are the three below (tools/vps/test.sh checks the list against
# docker-compose.yml); the layers are cached, so a build that has been done once costs seconds when `up` asks for it again.
build_images() {
  local svc
  for svc in chainsvc server web; do
    log "building the image of ${svc}"
    compose build "${svc}"
  done
}

cmd_build() {
  require_app
  cd "${APP_DIR}"
  log "building the images of the stand (nothing is started)"
  build_images
}

cmd_up() {
  require_app
  # The chain service's data: without them Docker would create an empty root-owned directory for the bind mount, and
  # chainsvc would crash-loop. This is also where the one-publisher rule shows on this side: the data come from the
  # operator's machine by tools/vps/secrets.sh, after the stand there was stopped.
  [[ -s "${C2_DIR}/${OPERATOR_NAME}.json" && -s "${C2_DIR}/${OPERATOR_NAME}.oracle.json" ]] \
    || die "no operator key/state in ${C2_DIR}: run tools/vps/secrets.sh --data-only (after stopping the stand on the operator's machine)"
  if [[ -x "${APP_DIR}/tools/vps/backup.sh" ]]; then
    "${APP_DIR}/tools/vps/backup.sh" pre-deploy || log "the backup before the deploy failed -- going on"
  fi
  (cd "${APP_DIR}" && build_images)
  (
    cd "${APP_DIR}"
    # tools/stand.sh only defines its functions when sourced. Its `up` does the work (the checks for foreign herd-*
    # containers, the .env, the build, waiting for healthy, the price-source probes); one function is replaced: the host
    # port of the web. Caddy is the only way in, so the web is published on the loopback and not on 0.0.0.0, which Docker
    # would open past ufw.
    # shellcheck source=/dev/null
    source tools/stand.sh
    if ! { declare -F pick_web_port && declare -F cmd_up && declare -F print_urls; } >/dev/null; then
      echo "[remote] error: tools/stand.sh has no pick_web_port/cmd_up/print_urls any more; tools/vps/remote.sh has to follow it" >&2
      exit 2
    fi
    # shellcheck disable=SC2329 # called by stand.sh's cmd_up
    pick_web_port() { echo "${WEB_BIND}:${WEB_PORT}"; }
    # shellcheck disable=SC2329 # called by stand.sh's cmd_up
    print_urls() { log "stand is up: web on ${WEB_BIND}:${WEB_PORT} (public through Caddy)"; }
    export HERD_WEB_PORT="${WEB_BIND}:${WEB_PORT}"
    cmd_up
  )
}

cmd_stop() {
  require_app
  log "stopping the containers of the stand (they stay, with their volumes; docker's restart policy keeps them stopped)"
  compose stop
}

# --- checks ------------------------------------------------------------------------------------------------------------------

FAILS=0
WARNS=0
ok() { printf '  ok    %s\n' "$*"; }
warn() {
  printf '  WARN  %s\n' "$*"
  WARNS=$((WARNS + 1))
}
fail() {
  printf '  FAIL  %s\n' "$*"
  FAILS=$((FAILS + 1))
}

# The local addresses a TCP port is listening on, one per line (ss prints host:port, [v6]:port, *:port).
listening_on() { ss -ltnH "sport = :$1" 2>/dev/null | awk '{print $4}'; }

only_these_addrs() { # only_these_addrs PORT ADDR... : every listener of PORT is one of the addresses (host part only)
  local port="$1" a host good allowed
  shift
  while IFS= read -r a; do
    [[ -n "${a}" ]] || continue
    host="${a%:*}"
    good=0
    for allowed in "$@"; do
      [[ "${host}" == "${allowed}" ]] && good=1
    done
    ((good)) || return 1
  done < <(listening_on "${port}")
  return 0
}

CURL_K=()
# The status code of a request, 000 when there was none (curl prints 000 itself when it cannot connect, so its output is taken as it is).
http_code() {
  local c
  c="$(curl -sS ${CURL_K[@]+"${CURL_K[@]}"} -o /dev/null -m 15 -w '%{http_code}' "$@" 2>/dev/null)" || true
  printf '%s' "${c:-000}"
}

check_stack() {
  local rows bad=0 svc state
  rows="$(compose ps --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null || true)"
  for svc in db chainsvc server web; do
    state="$(printf '%s\n' "${rows}" | awk -v s="${svc}" '$1 == s {print $2 " " $3}')"
    if [[ "${state}" == "running healthy" ]]; then
      ok "stand: ${svc} running, healthy"
    else
      fail "stand: ${svc} is '${state:-missing}' (want: running healthy)"
      bad=1
    fi
  done
  return "${bad}"
}

check_edge() {
  local state
  state="$(docker inspect -f '{{.State.Status}}' "${EDGE_CONTAINER}" 2>/dev/null || true)"
  if [[ "${state}" == running ]]; then ok "edge: ${EDGE_CONTAINER} running"; else fail "edge: ${EDGE_CONTAINER} is '${state:-missing}'"; fi
}

check_binds() {
  local addrs published
  # What Docker says about the published port is the authority (with the userland proxy there is a listener to see, with plain NAT
  # there is none); a listener on the port that is not on the loopback is a failure in either case.
  published="$(compose port web 80 2>/dev/null | head -n 1 || true)"
  if [[ "${published}" == "${WEB_BIND}:${WEB_PORT}" ]]; then
    ok "web: Docker publishes it as ${published}"
  else
    fail "web: Docker publishes it as '${published:-nothing}', not ${WEB_BIND}:${WEB_PORT} -- Docker publishes past ufw; it must be the loopback only"
  fi
  addrs="$(listening_on "${WEB_PORT}" | tr '\n' ' ')"
  if [[ -n "${addrs// /}" ]] && ! only_these_addrs "${WEB_PORT}" "${WEB_BIND}" "[::1]"; then
    fail "web: port ${WEB_PORT} is open on ${addrs}-- it must be ${WEB_BIND} only"
  fi
  addrs="$(listening_on 8090 | tr '\n' ' ')"
  if [[ -z "${addrs// /}" ]]; then
    fail "chainsvc: nothing listens on port 8090"
  elif only_these_addrs 8090 127.0.0.1 "${GATEWAY}"; then
    ok "chainsvc: port 8090 is on 127.0.0.1 and ${GATEWAY} only (${addrs})"
  else
    fail "chainsvc: port 8090 is open on ${addrs}"
  fi
}

check_chainsvc() {
  local code
  code="$(http_code "http://127.0.0.1:8090/health")"
  if [[ "${code}" == 200 ]]; then ok "chainsvc: /health 200 on the loopback"; else fail "chainsvc: /health answered ${code} on the loopback"; fi
  # The path the server really uses: from its container to the gateway address. The host's firewall drops it by default (ufw) or
  # rejects it (the rules file of Oracle's image); bootstrap.sh adds the rule.
  code="$(compose exec -T server curl -s -o /dev/null -m 10 -w '%{http_code}' "http://${GATEWAY}:8090/health" 2>/dev/null)" || true
  code="${code:-000}"
  if [[ "${code}" == 200 ]]; then
    ok "chainsvc: reachable from the server container at ${GATEWAY}:8090"
  else
    fail "chainsvc: the server container gets ${code} from http://${GATEWAY}:8090/health (the firewall rule 'server container -> chainsvc' missing? bootstrap.sh adds it: the ufw rule 'herd: server container -> chainsvc', or on Oracle Cloud the herd-chainsvc line in /etc/iptables/rules.v4)"
  fi
}

check_seq_floor() {
  local cid started line waited=0
  cid="$(compose ps -q server 2>/dev/null | head -n 1 || true)"
  if [[ -z "${cid}" ]]; then
    fail "oracle seq floor: no server container"
    return
  fi
  started="$(docker inspect -f '{{.State.StartedAt}}' "${cid}" 2>/dev/null || true)"
  while ((waited <= 180)); do
    line="$(docker logs --since "${started}" "${cid}" 2>&1 | grep -m1 'oracle seq floor obtained' || true)"
    if [[ -n "${line}" ]]; then
      ok "oracle seq floor: ${line#*oracle seq floor obtained: }"
      return
    fi
    sleep 5
    waited=$((waited + 5))
  done
  fail "oracle seq floor: the server has not read the chain service's maxKnownSeq in 180 s -- no round is settled until it does (docker compose -p ${PROJECT} logs server chainsvc)"
}

check_web_through_caddy() {
  local domain="$1" rnd code waited=0 line peer cid
  rnd="probe$(date +%s)$$"
  while ((waited <= 120)); do
    code="$(http_code --resolve "${domain}:443:127.0.0.1" "https://${domain}/?${rnd}=1")"
    [[ "${code}" == 200 ]] && break
    sleep 5
    waited=$((waited + 5))
  done
  if [[ "${code}" != 200 ]]; then
    fail "caddy: https://${domain}/ (resolved to this host) answers ${code} (certificate not issued yet? docker logs ${EDGE_CONTAINER})"
    return
  fi
  ok "caddy: https://${domain}/ answers 200 with a valid certificate"
  cid="$(compose ps -q web 2>/dev/null | head -n 1 || true)"
  sleep 1
  line="$(docker logs --since 2m "${cid}" 2>&1 | grep -m1 "${rnd}" || true)"
  peer="${line%% *}"
  if [[ "${peer}" == "${GATEWAY}" ]]; then
    ok "nginx sees the request from ${GATEWAY}, the only peer whose CF-Connecting-IP it trusts"
  else
    fail "nginx saw the request from '${peer:-nothing}', not ${GATEWAY}: it would ignore CF-Connecting-IP and every visitor would share one rate-limit bucket (line: ${line:-none})"
  fi
}

# The rate limit keyed on the address nginx is told about. cancelBet is limited like placeBet (RpcResource), is
# checked before the session is, and a call without a session changes nothing.
check_ratelimit() {
  local capacity burst i code hits=0 other_a other_b
  capacity="$(grep -m1 '^HERD_RATELIMIT_CAPACITY=' "${APP_DIR}/.env" 2>/dev/null | cut -d= -f2- || true)"
  capacity="${capacity:-30}"
  [[ "${capacity}" =~ ^[0-9]+$ ]] || capacity=30
  burst=$((capacity + 20))
  other_a="198.51.100.$((RANDOM % 200 + 1))"
  other_b="203.0.113.$((RANDOM % 200 + 1))"
  for ((i = 0; i < burst; i++)); do
    code="$(http_code -X POST -H 'Content-Type: application/json' -H "CF-Connecting-IP: ${other_a}" -d '{"args":["x"]}' "http://${WEB_BIND}:${WEB_PORT}/api/rpc/cancelBet")"
    [[ "${code}" == 429 ]] && hits=$((hits + 1))
  done
  if ((hits == 0)); then
    warn "rate limit: ${burst} calls from one address never got 429 (HERD_RATELIMIT_CAPACITY set higher? the check says nothing then)"
    return
  fi
  code="$(http_code -X POST -H 'Content-Type: application/json' -H "CF-Connecting-IP: ${other_b}" -d '{"args":["x"]}' "http://${WEB_BIND}:${WEB_PORT}/api/rpc/cancelBet")"
  if [[ "${code}" == 429 ]]; then
    fail "rate limit: a second address got 429 as well -- the limit is not keyed on CF-Connecting-IP (one bucket for everybody)"
  else
    ok "rate limit: one address is limited (${hits} x 429), another one is not (${code}): the bucket is the visitor's"
  fi
}

check_host() {
  local use
  use="$(df -P / | awk 'NR == 2 {gsub("%", "", $5); print $5}')"
  if [[ "${use}" =~ ^[0-9]+$ ]] && ((use >= 85)); then warn "disk: / is ${use}% full (docker system df; docker builder prune)"; else ok "disk: / is ${use:-?}% full"; fi
  if command -v timedatectl >/dev/null 2>&1; then
    if [[ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null || true)" == yes ]]; then
      ok "clock: synchronized"
    else
      warn "clock: not reported as synchronized (the oracle records carry the time)"
    fi
  fi
  if [[ -f /etc/cron.d/herd-backup ]]; then ok "backups: /etc/cron.d/herd-backup is there"; else warn "backups: no /etc/cron.d/herd-backup (bootstrap.sh writes it)"; fi
}

# MiB (one decimal) of a size as `docker stats` prints it: binary units (KiB, MiB, GiB), decimal ones (kB, MB, GB) in older versions.
mem_mib() { # mem_mib 3.115GiB -> 3189.8
  awk -v s="$1" 'BEGIN {
    n = s + 0
    u = s
    sub(/^[0-9.]+/, "", u)
    f["B"] = 1; f["KiB"] = 1024; f["MiB"] = 1048576; f["GiB"] = 1073741824; f["TiB"] = 1099511627776
    f["kB"] = 1000; f["KB"] = 1000; f["MB"] = 1000000; f["GB"] = 1000000000; f["TB"] = 1000000000000
    if (!(u in f)) exit 1
    printf "%.1f\n", n * f[u] / 1048576
  }'
}

# The memory the stand's containers hold, as a share of the machine's RAM -- always reported. With the oracle profile (HERD_VPS_PROFILE=oracle in
# .env, written by env-init.sh --profile oracle) it has to be at least MEMORY_FLOOR_PCT: Oracle may reclaim an Always Free instance whose CPU, network
# and memory use all stay under 20% (tools/vps/README.md, "Oracle Always Free"), and the pinned heap of the server is what keeps the memory above that.
# `docker stats` reports what the containers use without their page cache; the machine's own figures are shown next to it.
check_memory() {
  local total_kb avail_kb free_kb ids rows name used mib sum="0" parts="" short profile opts cid line total_mib share pct_avail pct_free
  total_kb="$(awk '/^MemTotal:/ {print $2}' "${MEMINFO}" 2>/dev/null || true)"
  avail_kb="$(awk '/^MemAvailable:/ {print $2}' "${MEMINFO}" 2>/dev/null || true)"
  free_kb="$(awk '/^MemFree:/ {print $2}' "${MEMINFO}" 2>/dev/null || true)"
  if ! [[ "${total_kb}" =~ ^[0-9]+$ ]] || ((total_kb == 0)); then
    warn "memory: cannot read MemTotal from ${MEMINFO}"
    return 0
  fi
  profile="$(grep -m1 '^HERD_VPS_PROFILE=' "${APP_DIR}/.env" 2>/dev/null | cut -d= -f2- || true)"
  ids="$(compose ps -q 2>/dev/null | tr '\n' ' ' || true)"
  rows=""
  if [[ -n "${ids// /}" ]]; then
    # shellcheck disable=SC2086 # the container ids are a list of words
    rows="$(docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' ${ids} 2>/dev/null || true)"
  fi
  while read -r name used _; do
    [[ -n "${name}" && -n "${used}" ]] || continue
    mib="$(mem_mib "${used}")" || continue
    sum="$(awk -v a="${sum}" -v b="${mib}" 'BEGIN { printf "%.1f", a + b }')"
    short="${name#herd-}"
    parts="${parts}${parts:+, }${short%-[0-9]*} ${mib%.*}"
  done <<<"${rows}"
  if [[ "${sum}" == 0 ]]; then
    if [[ "${profile}" == oracle ]]; then warn "memory: no figures from docker stats for the stand's containers (is the stand up?)"; fi
    return 0
  fi
  total_mib="$(awk -v k="${total_kb}" 'BEGIN { printf "%.0f", k / 1024 }')"
  share="$(awk -v s="${sum}" -v t="${total_kb}" 'BEGIN { printf "%.1f", s * 1024 * 100 / t }')"
  line="the stand's containers hold ${sum%.*} MiB = ${share}% of the machine's ${total_mib} MiB (${parts} MiB)"
  if [[ "${avail_kb}" =~ ^[0-9]+$ && "${free_kb}" =~ ^[0-9]+$ ]]; then
    pct_avail="$(awk -v a="${avail_kb}" -v t="${total_kb}" 'BEGIN { printf "%.0f", (t - a) * 100 / t }')"
    pct_free="$(awk -v f="${free_kb}" -v t="${total_kb}" 'BEGIN { printf "%.0f", (t - f) * 100 / t }')"
    line="${line}; the whole machine: ${pct_avail}% in use without cache (MemTotal-MemAvailable), ${pct_free}% not free (MemTotal-MemFree)"
  fi
  if [[ "${profile}" == oracle ]]; then
    if awk -v s="${share}" -v f="${MEMORY_FLOOR_PCT}" 'BEGIN { exit(s + 0 >= f + 0 ? 0 : 1) }'; then
      ok "memory: ${line}; the oracle profile asks for at least ${MEMORY_FLOOR_PCT}%"
    else
      warn "memory: ${line} -- BELOW the ${MEMORY_FLOOR_PCT}% the oracle profile asks for: an Always Free instance whose CPU, network and memory use all stay under 20% may be reclaimed by Oracle (HERD_SERVER_HEAP_MB in .env, and the stand has to be restarted after it is set: tools/vps/deploy.sh)"
    fi
  else
    ok "memory: ${line}"
  fi
  cid="$(compose ps -q server 2>/dev/null | head -n 1 || true)"
  opts=""
  if [[ -n "${cid}" ]]; then
    opts="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "${cid}" 2>/dev/null | grep -m1 '^JDK_JAVA_OPTIONS=' | cut -d= -f2- || true)"
  fi
  if [[ "${profile}" == oracle ]]; then
    if [[ "${opts}" == *-Xms* && "${opts}" == *-Xmx* && "${opts}" == *-XX:+AlwaysPreTouch* ]]; then
      ok "memory: the server JVM runs with ${opts}"
    else
      warn "memory: the oracle profile is set in .env, but the server container has JDK_JAVA_OPTIONS='${opts}' (it was started before the setting: tools/vps/deploy.sh restarts the stand)"
    fi
  elif [[ -n "${opts}" ]]; then
    ok "memory: the server JVM runs with ${opts}"
  fi
}

cmd_verify() {
  local domain="${1:-}" ratelimit=1 flag
  [[ -n "${domain}" ]] || die "usage: verify DOMAIN [--no-ratelimit] [--insecure]"
  shift
  for flag in "$@"; do
    case "${flag}" in
      --no-ratelimit) ratelimit=0 ;;
      --insecure) CURL_K=(-k) ;;
      *) die "verify: unknown flag ${flag}" ;;
    esac
  done
  require_app
  echo "checks on the VPS:"
  check_stack || true
  check_edge
  check_binds
  check_chainsvc
  check_seq_floor
  check_web_through_caddy "${domain}"
  if ((ratelimit)); then check_ratelimit; fi
  check_memory
  check_host
  echo "${FAILS} failed, ${WARNS} warnings"
  ((FAILS == 0))
}

cmd_status() {
  echo "jobs:"
  local d
  for d in "${JOBS_DIR}"/*/; do
    [[ -d "${d}" ]] || continue
    printf '  %s: %s\n' "$(basename "${d}")" "$(job_status_word "$(basename "${d}")")"
  done
  if [[ -f "${APP_DIR}/.deploy-info" ]]; then
    echo "deployed:"
    sed 's/^/  /' "${APP_DIR}/.deploy-info"
  fi
  if command -v docker >/dev/null 2>&1 && [[ -f "${APP_DIR}/docker-compose.yml" && -s "${APP_DIR}/.env" ]]; then
    compose ps 2>/dev/null || true
  fi
}

main() {
  case "${1:-}" in
    job)
      shift
      cmd_job "$@"
      ;;
    edge) cmd_edge ;;
    build) cmd_build ;;
    up) cmd_up ;;
    stop) cmd_stop ;;
    verify)
      shift
      cmd_verify "$@"
      ;;
    status) cmd_status ;;
    *)
      sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
      exit 1
      ;;
  esac
}

# Run only when executed; sourcing it (tools/vps/test.sh) just defines the functions.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
