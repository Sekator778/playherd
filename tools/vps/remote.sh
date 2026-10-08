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
JOB_WRAPPER='dir="$1"; wd="$2"; shift 2; echo $$ >"${dir}/pid"; cd "${wd}" || exit 1; "$@"; rc=$?; echo "${rc}" >"${dir}/status"; exit "${rc}"'

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

cmd_build() {
  require_app
  cd "${APP_DIR}"
  log "building the images of the stand (nothing is started)"
  compose build
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
  local addrs
  addrs="$(listening_on "${WEB_PORT}" | tr '\n' ' ')"
  if [[ -z "${addrs// /}" ]]; then
    fail "web: nothing listens on port ${WEB_PORT}"
  elif only_these_addrs "${WEB_PORT}" "${WEB_BIND}" "[::1]"; then
    ok "web: port ${WEB_PORT} is on the loopback only (${addrs})"
  else
    fail "web: port ${WEB_PORT} is open on ${addrs}-- Docker publishes past ufw; it must be ${WEB_BIND} only"
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
  # The path the server really uses: from its container to the gateway address. ufw's default is to drop it; bootstrap.sh
  # adds the rule.
  code="$(compose exec -T server curl -s -o /dev/null -m 10 -w '%{http_code}' "http://${GATEWAY}:8090/health" 2>/dev/null)" || true
  code="${code:-000}"
  if [[ "${code}" == 200 ]]; then
    ok "chainsvc: reachable from the server container at ${GATEWAY}:8090"
  else
    fail "chainsvc: the server container gets ${code} from http://${GATEWAY}:8090/health (ufw rule 'herd: server -> chainsvc' missing? bootstrap.sh adds it)"
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
