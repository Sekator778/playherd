# shellcheck shell=bash
# shellcheck disable=SC2034 # the settings below are read by the scripts that source this file
# shellcheck disable=SC2029 # the ssh commands are built here on purpose: they expand on this side
# Helpers shared by tools/vps/deploy.sh and tools/vps/secrets.sh -- code that runs on the OPERATOR's machine (the
# Mac), never on the VPS. Sourced, not executed. Written for bash 3.2 (macOS /bin/bash): no associative arrays, no
# mapfile, no ${var,,}.

VPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${VPS_DIR}/../.." && pwd)"

# --- facts about the stand that this kit depends on (docker-compose.yml, web/nginx.conf, chain/c2-svc);
# tools/vps/test.sh checks every one of them against those files, so a change there fails the test instead of the deploy.
STAND_PROJECT=herd                 # docker-compose.yml: name
WEB_BIND=127.0.0.1                 # the web container is published on the loopback only; Caddy is the way in
WEB_PORT=8480                      # docker-compose.yml: "${HERD_WEB_PORT:-8480}:80"
CHAINSVC_GATEWAY=172.28.77.1       # docker-compose.yml: networks.default.ipam.gateway; web/nginx.conf trusts it
EDGE_CONTAINER=edge-caddy          # tools/vps/edge/compose.yml
# On the VPS, relative to the deploy user's home directory (ssh, scp and rsync all start there).
REMOTE_APP_DIR=herd                # the repository tree (rsync target) and the stand's .env
REMOTE_EDGE_DIR=edge               # Caddy: compose.yml, conf/Caddyfile, download/
REMOTE_C2_DIR=.c2/testnet11        # docker-compose.yml mounts ${HOME}/.c2/testnet11 into chainsvc
# What of the operator profile the chain service needs (names from chain/c2-svc/src: keys_file.rs, state_file.rs,
# play_state_file.rs, oracle/queue.rs, play/withdrawal_queue.rs, play/deposit.rs). The first two cannot be missing,
# the queue is needed for a start in time (secrets.sh says why), the others are created empty by the service.
C2_REQUIRED_FILES="@NAME@.json @NAME@.oracle.json @NAME@.oracle_queue.json"
C2_OPTIONAL_FILES="@NAME@.play.json @NAME@.play_withdrawal_queue.json @NAME@.play_deposit_registry.json"

DRY_RUN=0
STAGING_CA="https://acme-staging-v02.api.letsencrypt.org/directory"

log() { printf '[vps] %s\n' "$*" >&2; }
warn() { printf '[vps] WARNING %s\n' "$*" >&2; }
die() {
  printf '[vps] error: %s\n' "$*" >&2
  exit 1
}

# --- configuration: environment, then an optional defaults file, then the command line (the callers parse flags
# after vps_load_config and before vps_finalize_config)

# The defaults file holds NAME=value lines (no shell is run: only these names are read). The first of these that exists is used:
# $HERD_VPS_ENV_FILE, <repo>/.local/vps.env (git-ignored), ~/.config/herd/vps.env. A variable already in the environment wins.
load_defaults_file() {
  local file line key val
  DEFAULTS_FILE_USED=""
  for file in "${HERD_VPS_ENV_FILE:-}" "${REPO_ROOT}/.local/vps.env" "${HOME}/.config/herd/vps.env"; do
    [[ -n "${file}" && -f "${file}" ]] || continue
    while IFS= read -r line || [[ -n "${line}" ]]; do
      case "${line}" in '' | '#'*) continue ;; esac
      key="${line%%=*}"
      val="${line#*=}"
      val="${val%\"}"
      val="${val#\"}"
      val="${val%\'}"
      val="${val#\'}"
      case "${key}" in
        HERD_VPS_HOST | HERD_VPS_DOMAIN | HERD_VPS_USER | HERD_VPS_KEY | HERD_VPS_SSH_PORT | HERD_VPS_EMAIL | HERD_VPS_ROOT_USER)
          [[ -n "${!key:-}" ]] || export "${key}=${val}"
          ;;
      esac
    done <"${file}"
    DEFAULTS_FILE_USED="${file}"
    break
  done
}

vps_load_config() {
  load_defaults_file
  VPS_HOST="${HERD_VPS_HOST:-}"
  VPS_DOMAIN="${HERD_VPS_DOMAIN:-}"
  VPS_USER="${HERD_VPS_USER:-deploy}"
  VPS_ROOT_USER="${HERD_VPS_ROOT_USER:-root}"
  VPS_KEY="${HERD_VPS_KEY:-${HOME}/.ssh/herd_vps}"
  VPS_SSH_PORT="${HERD_VPS_SSH_PORT:-22}"
  VPS_EMAIL="${HERD_VPS_EMAIL:-}"
}

# --- validation: everything that ends up in a remote command line or in a rendered file is checked against a narrow pattern first
valid_domain() { # lowercase DNS name with at least one dot and an alphabetic last label; not an IP address
  local re='^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$'
  [[ "$1" =~ $re ]]
}
valid_host() { # an IPv4 address or a host name (an IPv6 address would need brackets for scp and rsync)
  local re='^[A-Za-z0-9][A-Za-z0-9._-]*$'
  [[ "$1" =~ $re ]]
}
valid_email() {
  local re='^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'
  [[ "$1" =~ $re ]]
}
valid_user() {
  local re='^[a-z_][a-z0-9_-]{0,30}$'
  [[ "$1" =~ $re ]]
}
valid_port() {
  local re='^[1-9][0-9]{0,4}$'
  [[ "$1" =~ $re ]] || return 1
  (($1 <= 65535))
}
valid_wc_id() { # a Reown/WalletConnect project id: 32 hex digits
  local re='^[0-9a-f]{32}$'
  [[ "$1" =~ $re ]]
}
valid_env_name() {
  local re='^[A-Z][A-Z0-9_]{0,63}$'
  [[ "$1" =~ $re ]]
}
valid_env_value() { # one line, no quotes, no spaces, no shell or dotenv syntax
  local re='^[A-Za-z0-9_.:/,@+=-]*$'
  [[ "$1" =~ $re ]]
}
is_ipv4() {
  local re='^[0-9]{1,3}(\.[0-9]{1,3}){3}$'
  [[ "$1" =~ $re ]]
}

lowercase() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# Checks the values that were set and builds SSH_OPTS / SCP_OPTS / SSH_TARGET. Needs the host (and the domain, when
# the caller says it needs one: HERD_NEED_DOMAIN=1). In a dry run an unset host or domain gets a placeholder.
vps_finalize_config() {
  local need_domain="${1:-0}"
  if ((DRY_RUN)); then
    [[ -n "${VPS_HOST}" ]] || {
      VPS_HOST="203.0.113.10"
      PLACEHOLDER_NOTE=1
    }
    if [[ "${need_domain}" == 1 && -z "${VPS_DOMAIN}" ]]; then
      VPS_DOMAIN="herd.example.org"
      PLACEHOLDER_NOTE=1
    fi
    if [[ "${PLACEHOLDER_NOTE:-0}" == 1 ]]; then
      log "dry run: host/domain not given, placeholders ${VPS_HOST} / ${VPS_DOMAIN:-<none>} are used in what follows"
    fi
  fi
  [[ -n "${VPS_HOST}" ]] || die "no VPS host: --host IP, or HERD_VPS_HOST (see tools/vps/README.md)"
  valid_host "${VPS_HOST}" || die "bad host '${VPS_HOST}'"
  valid_user "${VPS_USER}" || die "bad user '${VPS_USER}'"
  valid_user "${VPS_ROOT_USER}" || die "bad root user '${VPS_ROOT_USER}'"
  valid_port "${VPS_SSH_PORT}" || die "bad ssh port '${VPS_SSH_PORT}'"
  if [[ "${need_domain}" == 1 ]]; then
    VPS_DOMAIN="$(lowercase "${VPS_DOMAIN}")"
    [[ -n "${VPS_DOMAIN}" ]] || die "no domain: --domain herd.example.org, or HERD_VPS_DOMAIN"
    valid_domain "${VPS_DOMAIN}" || die "bad domain '${VPS_DOMAIN}' (a lowercase DNS name, not an IP address)"
  fi
  if [[ -n "${VPS_EMAIL}" ]]; then
    valid_email "${VPS_EMAIL}" || die "bad e-mail '${VPS_EMAIL}'"
  fi
  if ((!DRY_RUN)); then
    [[ -f "${VPS_KEY}" ]] || die "ssh key ${VPS_KEY} not found (--key, or HERD_VPS_KEY)"
  fi
  # The VPS has a known_hosts file of its own: a rebuilt VPS is a new host key, and the old line is removed with
  # ssh-keygen -R <host> -f ~/.ssh/known_hosts.herd-vps, not by touching the operator's main file.
  local common=(-o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=30
    -o ServerAliveCountMax=8 -o StrictHostKeyChecking=accept-new
    -o "UserKnownHostsFile=${HOME}/.ssh/known_hosts.herd-vps" -i "${VPS_KEY}")
  SSH_OPTS=("${common[@]}" -p "${VPS_SSH_PORT}")
  SCP_OPTS=("${common[@]}" -P "${VPS_SSH_PORT}")
  SSH_TARGET="${VPS_USER}@${VPS_HOST}"
}

# --- running things, or only saying what would run

# One word the way a shell would have to be given it: left alone if it is plain, in single quotes otherwise.
shq() {
  local s="$1" q="'"
  if [[ -n "${s}" && "${s}" =~ ^[A-Za-z0-9_./:=@%+,-]+$ ]]; then
    printf '%s' "${s}"
  else
    printf "'%s'" "${s//${q}/${q}\\${q}${q}}"
  fi
}
quote_cmd() { # quote_cmd WORD... -> the command line, quoted so that it can be pasted into a shell
  local out="" w
  for w in "$@"; do
    out="${out}$(shq "${w}") "
  done
  printf '%s' "${out% }"
}

# A local command: run it, or in a dry run print it.
local_run() {
  if ((DRY_RUN)); then
    printf '[dry-run] %s\n' "$(quote_cmd "$@")" >&2
    return 0
  fi
  "$@"
}

# One command line on the VPS as the deploy user (a single string, read by the remote shell).
remote() {
  if ((DRY_RUN)); then
    printf '[dry-run] ssh %s: %s\n' "${SSH_TARGET}" "$1" >&2
    return 0
  fi
  ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "$1"
}

# Like remote, but the command is a script read from a local file on stdin: remote_script FILE 'args for the script'.
remote_script() {
  local file="$1" args="${2:-}"
  if ((DRY_RUN)); then
    printf '[dry-run] ssh %s: bash -s -- %s   < %s\n' "${SSH_TARGET}" "${args}" "${file}" >&2
    return 0
  fi
  ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "bash -s -- ${args}" <"${file}"
}

# remote_copy LOCAL_FILE REMOTE_PATH  (a path relative to the deploy user's home, or absolute)
remote_copy() {
  if ((DRY_RUN)); then
    printf '[dry-run] scp %s -> %s:%s\n' "$1" "${SSH_TARGET}" "$2" >&2
    return 0
  fi
  scp -q "${SCP_OPTS[@]}" "$1" "${SSH_TARGET}:$2"
}

sha256_of() { # sha256_of FILE -> the digest only
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# --- the one-publisher rule (README: "Одна ключевая пара — один издатель"). The oracle singleton and the PLAY payouts are
# operated by one key, so the chain service of this Mac's stand must not be running when the VPS's starts.

# Whether a chainsvc container of the Mac's own stand (compose project herd) is running. No docker, or a daemon that is
# not up: nothing runs, so no. Only reads container state.
local_chainsvc_running() {
  command -v docker >/dev/null 2>&1 || return 1
  docker info >/dev/null 2>&1 || return 1
  local ids
  ids="$(docker ps -q --filter "label=com.docker.compose.project=${STAND_PROJECT}" --filter "label=com.docker.compose.service=chainsvc" 2>/dev/null || true)"
  [[ -n "${ids}" ]]
}

require_local_chainsvc_stopped() { # require_local_chainsvc_stopped ALLOW_FLAG_VALUE WHAT
  if [[ "${1:-0}" == 1 ]]; then
    warn "the check for a running chainsvc on this machine is switched off (--allow-local-stand)"
    return 0
  fi
  if ((DRY_RUN)); then
    log "dry run: would check that this machine's own stand (compose project ${STAND_PROJECT}) has no chainsvc running"
    return 0
  fi
  if local_chainsvc_running; then
    die "the chain service of the stand on this machine is running (compose project ${STAND_PROJECT}). The oracle singleton and the PLAY payouts have one operator key: ${2:-this step} would put a second publisher on it. Stop the local stand first (docker compose -p ${STAND_PROJECT} stop), then repeat. See tools/vps/README.md, \"Один издатель\"."
  fi
}

# --- DNS: Caddy asks Let's Encrypt for a certificate the first time it starts, and failed validations are counted per
# domain and hour. So the A record is looked at before anything is started.

dns_a_records() { # dns_a_records NAME [RESOLVER] -> IPv4 addresses, one per line
  local name="$1" resolver="${2:-}" out=""
  if command -v dig >/dev/null 2>&1; then
    out="$(dig +short +time=3 +tries=1 A "${name}" ${resolver:+"@${resolver}"} 2>/dev/null || true)"
  elif command -v host >/dev/null 2>&1; then
    out="$(host -t A -W 3 "${name}" ${resolver:+"${resolver}"} 2>/dev/null | awk '/has address/ {print $NF}' || true)"
  fi
  printf '%s\n' "${out}" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' || true
}

dns_aaaa_records() {
  command -v dig >/dev/null 2>&1 || return 0
  dig +short +time=3 +tries=1 AAAA "$1" 2>/dev/null | grep -E ':' || true
}

# dns_check DOMAIN HOST: dies unless DOMAIN resolves to HOST (an IPv4 address, or a name that resolves to some) with the
# system resolver or with one of two public ones; warns about an AAAA record.
dns_check() {
  local domain="$1" host="$2" want seen resolver
  if ((DRY_RUN)); then
    log "dry run: would check that ${domain} resolves to ${host}"
    return 0
  fi
  if ! command -v dig >/dev/null 2>&1 && ! command -v host >/dev/null 2>&1; then
    warn "neither dig nor host is installed: the A record of ${domain} was not checked"
    return 0
  fi
  if is_ipv4 "${host}"; then
    want="${host}"
  else
    want="$(dns_a_records "${host}")"
  fi
  [[ -n "${want}" ]] || die "cannot work out the IPv4 address of ${host}"
  seen=""
  for resolver in "" 1.1.1.1 8.8.8.8; do
    seen="$(dns_a_records "${domain}" "${resolver}")"
    if [[ -n "${seen}" ]] && printf '%s\n' "${want}" | grep -qxFf <(printf '%s\n' "${seen}"); then
      log "DNS: ${domain} -> $(printf '%s' "${seen}" | tr '\n' ' ')(${resolver:-system resolver})"
      if [[ -n "$(dns_aaaa_records "${domain}")" ]]; then
        warn "${domain} also has an AAAA record; Let's Encrypt may validate over IPv6, so it must be the VPS's own IPv6 address (or removed)"
      fi
      return 0
    fi
  done
  die "${domain} does not resolve to ${host} (seen: ${seen:-nothing}). Point its A record at the VPS (DNS only, no proxy) and wait for it to spread; --skip-dns-check overrides this, at the cost of failed certificate requests."
}

# --- the Caddyfile

# render_caddyfile DOMAIN PORT [EMAIL] [STAGING 0|1] -> the finished Caddyfile on stdout
render_caddyfile() {
  local domain="$1" port="$2" email="${3:-}" staging="${4:-0}"
  valid_domain "${domain}" || die "render_caddyfile: bad domain '${domain}'"
  valid_port "${port}" || die "render_caddyfile: bad port '${port}'"
  if [[ -n "${email}" ]]; then
    valid_email "${email}" || die "render_caddyfile: bad e-mail '${email}'"
  fi
  if [[ -n "${email}" || "${staging}" == 1 ]]; then
    printf '{\n'
    [[ -z "${email}" ]] || printf '\temail %s\n' "${email}"
    [[ "${staging}" != 1 ]] || printf '\tacme_ca %s\n' "${STAGING_CA}"
    printf '}\n\n'
  fi
  sed -e "s|@@DOMAIN@@|${domain}|g" -e "s|@@WEB_PORT@@|${port}|g" "${VPS_DIR}/edge/Caddyfile.tmpl"
}

# --- the tree that goes to the VPS

# Paths that never leave this machine: the version control directory, dependency and build output, the secrets of the
# repository's .gitignore, and local-only notes. Used for rsync and, by tools/vps/test.sh, to check the list.
RSYNC_EXCLUDES=(
  --exclude '.git' --exclude 'node_modules' --exclude 'target'
  --exclude '.env' --exclude '.env.*' --exclude '.local' --exclude '.claude'
  --exclude '*.pem' --exclude '*.key' --exclude '*.jks' --exclude '*.keystore'
  --exclude '*.apk' --exclude '*.aab' --exclude 'local.properties'
  --exclude '.gradle' --exclude '/build' --exclude '/web/dist' --exclude '/web/coverage'
  --exclude '/web/android/build' --exclude '/web/android/app/build' --exclude '*.tsbuildinfo'
  --exclude '/tools/verify-oracle/.venv' --exclude '__pycache__' --exclude '*.pyc'
  --exclude '.idea' --exclude '.vscode' --exclude '.DS_Store' --exclude '*.log'
  # state that deploy.sh and the VPS itself keep inside the tree: --delete must not remove it
  --exclude '/.deploy-info'
)

# export_rev REV DIR: a clean copy of the tracked files at REV (git archive), for deploying or rolling back to a commit
export_rev() {
  git -C "${REPO_ROOT}" rev-parse --verify --quiet "$1^{commit}" >/dev/null || die "unknown revision '$1'"
  mkdir -p "$2"
  git -C "${REPO_ROOT}" archive "$1" | tar -x -C "$2"
}

# Text for the cost-free question "what exactly is being deployed": the commit and whether the tree has changes.
tree_description() {
  local rev dirty=""
  rev="$(git -C "${REPO_ROOT}" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
  if [[ -n "$(git -C "${REPO_ROOT}" status --porcelain --untracked-files=no 2>/dev/null || true)" ]]; then
    dirty="+uncommitted-changes"
  fi
  printf '%s%s' "${rev}" "${dirty}"
}
