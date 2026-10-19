#!/usr/bin/env bash
# tools/vps/deploy.sh -- puts the Herd stand on a VPS and checks it. Run from the operator's machine (the Mac); the VPS has
# been prepared by bootstrap.sh and has its secrets (secrets.sh). Order of steps and the cutover: tools/vps/README.md.
#
#   tools/vps/deploy.sh [up] [options]    rsync the tree, set up Caddy (automatic HTTPS), build and start the stand with
#                                         tools/stand.sh, check it from outside and from inside
#   tools/vps/deploy.sh bootstrap         copy bootstrap.sh to the new VPS and run it there as root (once)
#   tools/vps/deploy.sh check             the checks only
#   tools/vps/deploy.sh apk FILE          put an Android build under https://DOMAIN/download/herd.apk
#   tools/vps/deploy.sh stop              stop the stand on the VPS (containers and data stay)
#
# Options:
#   --host H / --domain D / --user U / --key K / --ssh-port P   the VPS (or HERD_VPS_*, or .local/vps.env)
#   --root-user U       the account that bootstrap logs in as and runs bootstrap.sh with sudo (default: root; on Oracle Cloud's
#                       Ubuntu image: ubuntu); HERD_VPS_ROOT_USER
#   --profile P         oracle: an Oracle Cloud instance -- bootstrap.sh uses the image's iptables rules file and no ufw, and `up`
#                       checks that the VPS's .env has the memory settings of secrets.sh --profile oracle; HERD_VPS_PROFILE
#   --email ADDR        contact address for Let's Encrypt (optional)
#   --build-only        sync, Caddy, and build the images, but do not start the stand: the long first build can run while
#                       the stand on this machine is still the one that serves, and the cutover itself takes minutes
#   --rev REF           deploy the files tracked at REF (git archive) instead of the working tree -- also the rollback:
#                       --rev v0.1.0 or a commit id
#   --apk FILE          after the deploy, upload an APK (see the apk command)
#   --any-apk-address   accept an APK whose built-in address is not https://DOMAIN
#   --tls-staging       Caddy asks Let's Encrypt's staging CA: a rehearsal (the browser will not trust that certificate)
#   --skip-dns-check    do not require the A record of DOMAIN to point at HOST yet
#   --allow-local-stand do not look for a chainsvc running on this machine (the one-publisher rule; README)
#   --no-ratelimit-check  skip the burst of requests that tests the rate limit (it uses cancelBet, which changes nothing)
#   --dry-run           print the rsync and ssh commands and the Caddyfile; contact nothing
#
# Sources: Docker, Caddy and ufw behaviour -- tools/vps/README.md lists the pages and the dates they were read.

# shellcheck disable=SC2029 # the commands sent over ssh are built on this side on purpose
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vps/common.sh
source "${SELF_DIR}/common.sh"

COMMAND=up
BUILD_ONLY=0
REV=""
APK_FILE=""
ANY_APK_ADDRESS=0
TLS_STAGING=0
SKIP_DNS=0
ALLOW_LOCAL=0
RATELIMIT_CHECK=1
SCRATCH=""
VERIFY_FLAG=""
CURL_K=() # -k while the certificate is the staging CA's

usage() {
  sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-1}"
}

cleanup() { [[ -z "${SCRATCH}" ]] || rm -rf "${SCRATCH}"; }
trap cleanup EXIT

vps_load_config
case "${1:-}" in
  up | bootstrap | check | apk | stop)
    COMMAND="$1"
    shift
    ;;
esac
if [[ "${COMMAND}" == apk ]]; then
  APK_FILE="${1:?usage: deploy.sh apk FILE [options]}"
  shift
fi
while (($# > 0)); do
  case "$1" in
    --host)
      VPS_HOST="${2:?}"
      shift 2
      ;;
    --domain)
      VPS_DOMAIN="${2:?}"
      shift 2
      ;;
    --user)
      VPS_USER="${2:?}"
      shift 2
      ;;
    --key)
      VPS_KEY="${2:?}"
      shift 2
      ;;
    --ssh-port)
      VPS_SSH_PORT="${2:?}"
      shift 2
      ;;
    --root-user)
      VPS_ROOT_USER="${2:?}"
      shift 2
      ;;
    --profile)
      VPS_PROFILE="${2:?}"
      shift 2
      ;;
    --email)
      VPS_EMAIL="${2:?}"
      shift 2
      ;;
    --build-only)
      BUILD_ONLY=1
      shift
      ;;
    --rev)
      REV="${2:?}"
      shift 2
      ;;
    --apk)
      APK_FILE="${2:?}"
      shift 2
      ;;
    --any-apk-address)
      ANY_APK_ADDRESS=1
      shift
      ;;
    --tls-staging)
      TLS_STAGING=1
      CURL_K=(-k)
      VERIFY_FLAG="${VERIFY_FLAG} --insecure"
      shift
      ;;
    --skip-dns-check)
      SKIP_DNS=1
      shift
      ;;
    --allow-local-stand)
      ALLOW_LOCAL=1
      shift
      ;;
    --no-ratelimit-check)
      RATELIMIT_CHECK=0
      VERIFY_FLAG="${VERIFY_FLAG} --no-ratelimit"
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h | --help) usage 0 ;;
    *) die "unknown argument '$1' (--help)" ;;
  esac
done

REMOTE_TOOL="${REMOTE_APP_DIR}/tools/vps/remote.sh"

new_scratch() { [[ -n "${SCRATCH}" ]] || SCRATCH="$(mktemp -d)"; }

# --- rsync --------------------------------------------------------------------------------------------------------------------

# What would go to the VPS: "<size in bytes> <path>" per file (rsync --list-only on this machine; nothing is sent). Used for the check below.
outgoing_list() { # outgoing_list SRC/
  rsync -r --list-only "${RSYNC_EXCLUDES[@]}" "$1" | awk '{ size = $2; gsub(",", "", size); name = $5; for (i = 6; i <= NF; i++) name = name " " $i; if ($1 !~ /^d/) print size " " name }'
}

# The list of files that go must hold nothing that is a secret or a build product, whatever the exclude list says (a typo in it, or
# a rsync that reads a pattern differently, would otherwise show only on the VPS).
scan_outgoing() { # scan_outgoing SRC/ ; sets OUTGOING_COUNT and OUTGOING_MB
  local list names bad count
  list="$(outgoing_list "$1")"
  names="$(printf '%s\n' "${list}" | cut -d' ' -f2-)"
  count="$(printf '%s\n' "${list}" | grep -c . || true)"
  OUTGOING_MB="$(printf '%s\n' "${list}" | awk '{ total += $1 } END { printf "%d", total / 1048576 }')"
  bad="$(printf '%s\n' "${names}" | grep -E '(^|/)(\.git|\.local|\.claude|node_modules|target|\.gradle)(/|$)|(^|/)\.env(\.|$)|\.(pem|key|jks|keystore|apk|aab)$|(^|/)id_(rsa|ed25519|ecdsa)' || true)"
  if [[ -n "${bad}" ]]; then
    die "the file list for the VPS contains paths that must stay here:
$(printf '%s\n' "${bad}" | head -n 20)"
  fi
  [[ "${count}" -gt 0 ]] || die "nothing to send from $1"
  OUTGOING_COUNT="${count}"
  if ((OUTGOING_MB > 200)); then
    warn "the tree is ${OUTGOING_MB} MB: more than a source tree should be. Something that should be excluded is in it (a build directory, a data dump?): rsync --list-only shows what"
  fi
}

sync_tree() {
  local src="${REPO_ROOT}/" description
  if [[ -n "${REV}" ]]; then
    new_scratch
    export_rev "${REV}" "${SCRATCH}/tree"
    src="${SCRATCH}/tree/"
    description="$(git -C "${REPO_ROOT}" rev-parse --short=12 "${REV}^{commit}")"
    log "tree: the tracked files at ${REV} (${description})"
  else
    description="$(tree_description)"
    log "tree: the working tree of ${REPO_ROOT} (${description})"
  fi
  scan_outgoing "${src}"
  log "tree: ${OUTGOING_COUNT} files, ${OUTGOING_MB} MB go to ${SSH_TARGET}:${REMOTE_APP_DIR}/ (nothing from .git, node_modules, target, .env*, .local, keys; --delete removes what is gone here, but not the VPS's .env)"
  case "${VPS_KEY}${HOME}" in *[[:space:]]*) die "the ssh key path or HOME contains a space: rsync's -e cannot carry that" ;; esac
  local ssh_cmd="ssh ${SSH_OPTS[*]}"
  local_run rsync "${RSYNC_FLAGS[@]}" "${RSYNC_EXCLUDES[@]}" -e "${ssh_cmd}" "${src}" "${SSH_TARGET}:${REMOTE_APP_DIR}/"
  remote "printf 'deployed=%s\nfrom=%s\nby=%s@%s\n' '$(date -u +%Y-%m-%dT%H:%M:%SZ)' '${description}' '$(id -un)' '$(hostname -s)' > ${REMOTE_APP_DIR}/.deploy-info"
}

# --- Caddy --------------------------------------------------------------------------------------------------------------------

push_edge() {
  new_scratch
  render_caddyfile "${VPS_DOMAIN}" "${WEB_PORT}" "${VPS_EMAIL}" "${TLS_STAGING}" >"${SCRATCH}/Caddyfile"
  if ((DRY_RUN)); then
    printf '[dry-run] the Caddyfile for %s:\n' "${VPS_DOMAIN}" >&2
    sed 's/^/[dry-run]   /' "${SCRATCH}/Caddyfile" >&2
  fi
  remote "mkdir -p edge/conf edge/download"
  remote_copy "${SCRATCH}/Caddyfile" "edge/conf/Caddyfile.new"
  remote "${REMOTE_TOOL} edge"
}

# --- the long jobs ----------------------------------------------------------------------------------------------------------

# Starts `remote.sh WHAT` on the VPS as a detached job and follows its log. A job of that name that is already running (this
# deploy was interrupted, the laptop went to sleep) is joined, not started twice.
run_stand_job() { # run_stand_job build|up
  local what="$1" rc=0 started
  started="$(date +%s)"
  remote "${REMOTE_TOOL} job start stand -- tools/vps/remote.sh ${what}"
  if ((DRY_RUN)); then
    remote "${REMOTE_TOOL} job follow stand"
    return 0
  fi
  new_scratch
  ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "${REMOTE_TOOL} job follow stand" 2>&1 | tee "${SCRATCH}/job.log" || rc=$?
  if ((rc == 255)); then
    die "the connection to the VPS dropped. The job keeps running there; run this command again to join it."
  elif ((rc != 0)); then
    die "the job '${what}' failed on the VPS (status ${rc}); its log is above, and in ${VPS_USER}@${VPS_HOST}:.herd-jobs/stand/log"
  fi
  log "'${what}' finished in $(($(date +%s) - started)) s"
  local warnings
  warnings="$(grep -F 'WARNING live prices' "${SCRATCH}/job.log" || true)"
  if [[ -n "${warnings}" ]]; then
    warn "the server container does not reach every price source (tools/stand.sh says so above):"
    printf '%s\n' "${warnings}" >&2
  fi
}

# --- checks -------------------------------------------------------------------------------------------------------------------

CHECK_FAILS=0
c_ok() { printf '  ok    %s\n' "$*"; }
c_warn() { printf '  WARN  %s\n' "$*"; }
c_fail() {
  printf '  FAIL  %s\n' "$*"
  CHECK_FAILS=$((CHECK_FAILS + 1))
}

# The status code of a request, 000 when there was none (curl prints 000 itself when it cannot connect, so its output is taken as it is).
curl_code() {
  local c
  c="$(curl -sS ${CURL_K[@]+"${CURL_K[@]}"} -o /dev/null -m 20 -w '%{http_code}' "$@" 2>/dev/null)" || true
  printf '%s' "${c:-000}"
}

external_checks() {
  local domain="${VPS_DOMAIN}" code waited=0 body hdrs i hits first second spoof_a spoof_b burst
  echo "checks from here, as a visitor sees the site:"
  while ((waited <= 150)); do
    code="$(curl_code "https://${domain}/")"
    [[ "${code}" == 200 ]] && break
    # The first start of Caddy fetches the certificate; give it time.
    sleep 5
    waited=$((waited + 5))
  done
  if [[ "${code}" == 200 ]]; then
    c_ok "https://${domain}/ -> 200 (certificate trusted by this machine)"
  else
    c_fail "https://${domain}/ -> ${code} after ${waited} s (DNS? ports 80/443? ssh in and read: docker logs edge-caddy)"
  fi

  body="$(curl -sS ${CURL_K[@]+"${CURL_K[@]}"} -m 20 -X POST -H 'Content-Type: application/json' -d '{"args":[]}' "https://${domain}/api/rpc/serverInfo" 2>/dev/null || true)"
  if command -v jq >/dev/null 2>&1 && printf '%s' "${body}" | jq -e '.engineReady != null' >/dev/null 2>&1; then
    c_ok "https://${domain}/api/rpc/serverInfo -> $(printf '%s' "${body}" | jq -c '{mode, priceSource, markets, testWallet, engineReady, oracle: {network: .oracle.network, lastConfirmedSeq: .oracle.lastConfirmedSeq, firstSeq: .oracle.firstSeq}}')"
    [[ "$(printf '%s' "${body}" | jq -r '.engineReady')" == true ]] || c_warn "engineReady is false: the server is rebuilding its state, calls answer 503 until it is true"
  elif [[ -n "${body}" ]] && printf '%s' "${body}" | grep -q 'engineReady'; then
    c_ok "https://${domain}/api/rpc/serverInfo answers: ${body:0:200}"
  else
    c_fail "https://${domain}/api/rpc/serverInfo did not answer with a server info (got: ${body:0:120})"
  fi

  hdrs="$(curl -sSI -m 20 "http://${domain}/" 2>/dev/null || true)"
  if printf '%s' "${hdrs}" | head -n 1 | grep -qE ' (301|302|307|308) ' && printf '%s' "${hdrs}" | grep -qi "^location: https://${domain}"; then
    c_ok "http://${domain}/ redirects to https"
  else
    c_fail "http://${domain}/ does not redirect to https (got: $(printf '%s' "${hdrs}" | head -n 1 | tr -d '\r'))"
  fi
  hdrs="$(curl -sSI ${CURL_K[@]+"${CURL_K[@]}"} -m 20 "https://${domain}/" 2>/dev/null || true)"
  if printf '%s' "${hdrs}" | grep -qi '^strict-transport-security: max-age='; then
    c_ok "HSTS header present"
  else
    c_fail "no Strict-Transport-Security header on https://${domain}/"
  fi

  # Docker publishes ports past ufw. Nothing but 22/80/443 may answer from outside.
  for port in "${WEB_PORT}" 8090; do
    if curl -s -m 6 -o /dev/null "http://${VPS_HOST}:${port}/" 2>/dev/null; then
      c_fail "port ${port} of ${VPS_HOST} answers from outside"
    else
      c_ok "port ${port} of ${VPS_HOST} does not answer from outside"
    fi
  done

  # The visitor's rate-limit bucket cannot be forged from outside: Caddy replaces CF-Connecting-IP with the address it saw. 50 calls
  # of cancelBet (rate limited like placeBet, answered before the session is looked at, changes nothing) from here, claiming to be one
  # address, must run into 429; one more claiming to be another address must be limited too, because to the server both are this machine.
  if ((RATELIMIT_CHECK)); then
    spoof_a="198.51.100.$((RANDOM % 200 + 1))"
    spoof_b="203.0.113.$((RANDOM % 200 + 1))"
    hits=0
    burst=50
    for ((i = 0; i < burst; i++)); do
      code="$(curl_code -X POST -H 'Content-Type: application/json' -H "CF-Connecting-IP: ${spoof_a}" -d '{"args":["x"]}' "https://${domain}/api/rpc/cancelBet")"
      [[ "${code}" == 429 ]] && hits=$((hits + 1))
    done
    if ((hits == 0)); then
      c_warn "rate limit: ${burst} calls never got 429 (HERD_RATELIMIT_CAPACITY raised? then this check says nothing)"
    else
      # three calls, not one: the bucket refills (0.5 a second by default) while the burst runs, so the first call after it can
      # find a token and get 401 although the bucket is shared; a fresh bucket (capacity 30) would answer all three without 429
      second=""
      for ((i = 0; i < 3; i++)); do
        code="$(curl_code -X POST -H 'Content-Type: application/json' -H "CF-Connecting-IP: ${spoof_b}" -d '{"args":["x"]}' "https://${domain}/api/rpc/cancelBet")"
        second="${second:+${second} }${code}"
        [[ "${code}" == 429 ]] && break
      done
      first="${hits}"
      if [[ "${second}" == *429* ]]; then
        c_ok "rate limit: limited after a burst (${first} x 429), and a forged CF-Connecting-IP does not give a fresh bucket"
      else
        c_fail "rate limit: a call with a forged CF-Connecting-IP got ${second}, not 429 -- Caddy does not overwrite that header"
      fi
    fi
  else
    c_warn "rate limit: skipped (--no-ratelimit-check)"
  fi

  code="$(curl_code "https://${domain}/download/")"
  case "${code}" in
    200) c_ok "https://${domain}/download/ lists the files" ;;
    404) c_ok "https://${domain}/download/ -> 404 (nothing uploaded yet: deploy.sh apk FILE)" ;;
    *) c_warn "https://${domain}/download/ -> ${code}" ;;
  esac
}

do_checks() {
  CHECK_FAILS=0
  if ((DRY_RUN)); then
    remote "${REMOTE_TOOL} verify ${VPS_DOMAIN}${VERIFY_FLAG}"
    log "dry run: then the checks from here (https, serverInfo, redirect, HSTS, closed ports, a forged rate-limit header, /download/)"
    return 0
  fi
  external_checks
  local rc=0
  ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "${REMOTE_TOOL} verify ${VPS_DOMAIN}${VERIFY_FLAG}" || rc=$?
  if ((rc != 0)); then CHECK_FAILS=$((CHECK_FAILS + 1)); fi
  if ((CHECK_FAILS > 0)); then
    die "${CHECK_FAILS} check(s) failed (lines marked FAIL above)"
  fi
  log "all checks passed"
}

# --- commands ---------------------------------------------------------------------------------------------------------------------

preflight_up() {
  local tool
  for tool in ssh scp rsync curl; do
    command -v "${tool}" >/dev/null 2>&1 || die "${tool} is not installed on this machine"
  done
  if ((DRY_RUN)); then
    log "dry run: would log in as ${SSH_TARGET}, check docker and ${REMOTE_APP_DIR}/.env there"
    [[ "${VPS_PROFILE}" != oracle ]] || log "dry run: would check that ${REMOTE_APP_DIR}/.env there holds the memory settings of the oracle profile"
    ((SKIP_DNS)) || dns_check "${VPS_DOMAIN}" "${VPS_HOST}"
    ((BUILD_ONLY)) || require_local_chainsvc_stopped "${ALLOW_LOCAL}" "starting the stand on the VPS"
    return 0
  fi
  remote "true" || die "cannot log in as ${SSH_TARGET} with ${VPS_KEY} (has 'deploy.sh bootstrap' run? is the key the one given to bootstrap?)"
  remote "docker compose version >/dev/null 2>&1" || die "docker (with the compose plugin) is not usable by ${VPS_USER} on the VPS: bootstrap.sh installs it and adds the user to the docker group"
  remote "test -s ${REMOTE_APP_DIR}/.env" || die "no ${REMOTE_APP_DIR}/.env on the VPS: run tools/vps/secrets.sh --env-only first"
  if [[ "${VPS_PROFILE}" == oracle ]]; then
    # An Always Free instance whose memory use stays under 20% may be reclaimed (tools/vps/README.md): the heap of the server is what keeps it above.
    remote "grep -qx 'HERD_VPS_PROFILE=oracle' ${REMOTE_APP_DIR}/.env && grep -q '^HERD_SERVER_HEAP_MB=[0-9][0-9]*' ${REMOTE_APP_DIR}/.env" \
      || die "the profile is oracle, but ${REMOTE_APP_DIR}/.env on the VPS has no memory settings of it (an idle Always Free instance may be reclaimed): run tools/vps/secrets.sh --env-only --profile oracle first"
  fi
  ((SKIP_DNS)) || dns_check "${VPS_DOMAIN}" "${VPS_HOST}"
  if ((!BUILD_ONLY)); then
    # The one-publisher rule, on this side: the stand here must be stopped, and the chain data must have been copied.
    require_local_chainsvc_stopped "${ALLOW_LOCAL}" "starting the stand on the VPS"
    remote "test -s ${REMOTE_C2_DIR}/operator.json && test -s ${REMOTE_C2_DIR}/operator.oracle.json" \
      || die "no operator key and oracle state in ${REMOTE_C2_DIR} on the VPS: stop the stand on this machine, then tools/vps/secrets.sh --data-only"
  fi
  # A build or a start that an earlier run left going has to end before the tree under it is replaced.
  if [[ "$(remote "test -x ${REMOTE_TOOL} && ${REMOTE_TOOL} job status stand || echo none" 2>/dev/null || echo none)" == running ]]; then
    log "a build/start job is still running on the VPS (an earlier deploy): waiting for it"
    ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "${REMOTE_TOOL} job wait stand" || true
  fi
}

do_up() {
  vps_finalize_config 1
  log "deploy of ${VPS_DOMAIN} to ${SSH_TARGET}$([[ -n "${DEFAULTS_FILE_USED:-}" ]] && echo " (defaults from ${DEFAULTS_FILE_USED})")"
  preflight_up
  sync_tree
  push_edge
  if ((BUILD_ONLY)); then
    run_stand_job build
    log "images built, nothing started. Caddy is up and has (or is getting) the certificate for ${VPS_DOMAIN}; the stand is not running."
    log "next: stop the stand on this machine, tools/vps/secrets.sh --data-only, tools/vps/deploy.sh (without --build-only)"
    return 0
  fi
  run_stand_job up
  do_checks
  if [[ -n "${APK_FILE}" ]]; then do_apk; fi
  log "done: https://${VPS_DOMAIN}/"
  log "Sage sign-in needs https://${VPS_DOMAIN} in the allowed domains of the Reown project (cloud.reown.com); a change takes about 5 minutes to reach the relay"
}

do_check() {
  vps_finalize_config 1
  do_checks
}

do_stop() {
  vps_finalize_config 0
  remote "${REMOTE_TOOL} stop"
}

do_bootstrap() {
  vps_finalize_config 0
  local pub="${VPS_KEY}.pub" root_target sudo="" profile_arg=""
  if ((!DRY_RUN)); then [[ -f "${pub}" ]] || die "no ${pub}: bootstrap gives the deploy user the public half of --key"; fi
  root_target="${VPS_ROOT_USER}@${VPS_HOST}"
  [[ "${VPS_ROOT_USER}" == root ]] || sudo="sudo -n "
  [[ -z "${VPS_PROFILE}" ]] || profile_arg=" --profile ${VPS_PROFILE}"
  log "bootstrap of ${VPS_HOST} as ${root_target}: Docker, the firewall, the '${VPS_USER}' user with ${pub}, unattended upgrades, swap, clock${VPS_PROFILE:+ (profile ${VPS_PROFILE})}"
  if ((DRY_RUN)); then
    printf '[dry-run] scp %s -> %s:herd-bootstrap.sh\n' "${SELF_DIR}/bootstrap.sh" "${root_target}" >&2
    printf '[dry-run] ssh %s: %sbash herd-bootstrap.sh --deploy-user %s --ssh-port %s --pubkey-stdin%s   < %s\n' "${root_target}" "${sudo}" "${VPS_USER}" "${VPS_SSH_PORT}" "${profile_arg}" "${pub}" >&2
    return 0
  fi
  scp -q "${SCP_OPTS[@]}" "${SELF_DIR}/bootstrap.sh" "${root_target}:herd-bootstrap.sh"
  ssh "${SSH_OPTS[@]}" "${root_target}" "${sudo}bash herd-bootstrap.sh --deploy-user ${VPS_USER} --ssh-port ${VPS_SSH_PORT} --pubkey-stdin${profile_arg}" <"${pub}"
  remote "true" || die "bootstrap finished, but ${SSH_TARGET} cannot log in with ${VPS_KEY}"
  log "bootstrap done; ${SSH_TARGET} works. Next: tools/vps/secrets.sh --env-only"
}

# The address built into an APK (Capacitor writes web/capacitor.config.ts's server.url into assets/capacitor.config.json); nothing when it cannot be read.
apk_address() { # apk_address FILE
  local cfg
  cfg="$(unzip -p "$1" assets/capacitor.config.json 2>/dev/null || true)"
  [[ -n "${cfg}" ]] || return 0
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "${cfg}" | jq -r '.server.url // empty' 2>/dev/null || true
  else
    printf '%s' "${cfg}" | tr -d '\n' | sed -n 's/.*"url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
  fi
}

# The APK that testers install. It is a WebView around https://DOMAIN (web/capacitor.config.ts: server.url, read from HERD_APP_URL by
# `cap sync`), so an APK built for another address is useless here; the address is looked up inside the file.
do_apk() {
  vps_finalize_config 1
  local f="${APK_FILE}" size sha url code listing
  if ((DRY_RUN)) && [[ ! -f "${f}" ]]; then
    log "dry run: ${f} does not exist here; would check it is an APK built for https://${VPS_DOMAIN}, upload it to ${SSH_TARGET}:edge/download/herd.apk with its sha256"
    remote "mv -f edge/download/.herd.apk.new edge/download/herd.apk"
    return 0
  fi
  [[ -f "${f}" ]] || die "no such file: ${f}"
  size="$(wc -c <"${f}" | tr -d ' ')"
  ((size > 100000 && size < 300000000)) || die "${f}: ${size} bytes is not a believable APK"
  [[ "$(head -c 4 "${f}" | od -An -tx1 | tr -d ' \n')" == 504b0304 ]] || die "${f} is not a zip file (an APK is)"
  if command -v unzip >/dev/null 2>&1; then
    # (the listing is read whole first: grep -q leaves a pipe early, and with pipefail unzip's SIGPIPE would fail the test)
    listing="$(unzip -l "${f}" 2>/dev/null || true)"
    grep -q 'AndroidManifest.xml' <<<"${listing}" || die "${f} has no AndroidManifest.xml: not an APK"
    url="$(apk_address "${f}")"
    if [[ -z "${url}" ]]; then
      warn "cannot read the address built into ${f} (assets/capacitor.config.json); make sure it was built with: make apk HERD_APP_URL=https://${VPS_DOMAIN}"
    elif [[ "${url}" != "https://${VPS_DOMAIN}" && "${url}" != "https://${VPS_DOMAIN}/" ]]; then
      if ((ANY_APK_ADDRESS)); then
        warn "the APK opens ${url}, not https://${VPS_DOMAIN} (--any-apk-address)"
      else
        die "the APK opens ${url}, not https://${VPS_DOMAIN}. Build it for this address: make apk HERD_APP_URL=https://${VPS_DOMAIN}  (or pass --any-apk-address)"
      fi
    else
      log "the APK opens ${url}"
    fi
  fi
  sha="$(sha256_of "${f}")"
  remote "mkdir -p edge/download"
  remote_copy "${f}" "edge/download/.herd.apk.new"
  remote "cd edge/download && mv -f .herd.apk.new herd.apk && chmod 644 herd.apk && printf '%s  herd.apk\n' '${sha}' > herd.apk.sha256 && chmod 644 herd.apk.sha256"
  if ((DRY_RUN)); then return 0; fi
  # What a tester gets: the same bytes, served as an Android package.
  new_scratch
  code="$(curl -sS ${CURL_K[@]+"${CURL_K[@]}"} -m 120 -o "${SCRATCH}/herd.apk" -w '%{http_code} %{content_type}' "https://${VPS_DOMAIN}/download/herd.apk" 2>/dev/null || echo '000 -')"
  if [[ "${code%% *}" == 200 && "$(sha256_of "${SCRATCH}/herd.apk")" == "${sha}" ]]; then
    log "https://${VPS_DOMAIN}/download/herd.apk: ${size} bytes, sha256 ${sha}, served as '${code#* }'"
  else
    die "https://${VPS_DOMAIN}/download/herd.apk did not come back intact (${code})"
  fi
}

case "${COMMAND}" in
  up) do_up ;;
  bootstrap) do_bootstrap ;;
  check) do_check ;;
  apk) do_apk ;;
  stop) do_stop ;;
esac
