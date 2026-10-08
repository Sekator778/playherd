#!/usr/bin/env bash
# tools/vps/bootstrap.sh -- prepares a fresh Ubuntu 24.04 VPS for the Herd stand. Run once, as root, on the VPS
# (tools/vps/deploy.sh bootstrap copies it over and runs it; see tools/vps/README.md). Safe to run again: every step
# looks at the state first and changes nothing that is already as wanted.
#
#   bootstrap.sh --pubkey-file FILE | --pubkey-stdin   [options]
#
#   --pubkey-file FILE / --pubkey-stdin / $HERD_DEPLOY_PUBKEY
#                       the public key that may log in as the deploy user (one line: ~/.ssh/herd_vps.pub on the operator's machine)
#   --deploy-user NAME  the account that owns the stand (default: deploy; no sudo; it is in the docker group)
#   --ssh-port N        the port sshd listens on, opened in ufw before ufw is switched on (default: 22)
#   --swap-size SIZE    size of the swap file made when RAM is below 4 GiB (default: 4G)
#   --stand-subnet CIDR / --stand-gateway IP
#                       the stand's docker network (docker-compose.yml, networks.default.ipam); ufw lets the server container
#                       reach the chain service, which listens on the gateway address (defaults: 172.28.77.0/24, 172.28.77.1)
#   --no-ssh-hardening  leave sshd's password settings alone
#   --container         for a throwaway container (the dry run of this script): skips what needs a booted system or
#                       the kernel -- systemd units, ufw's enable, swap, sshd, clock sync, the upgrade of the base system
#   --skip LIST         comma-separated parts to skip: systemd,ufw,swap,sshd,time,upgrade
#
# What it does: apt update/upgrade and base packages; clock sync; swap file if RAM < 4 GiB; Docker Engine, buildx and the
# compose plugin from Docker's own apt repository (key checked against its published fingerprint), with log rotation
# for all containers; the deploy user with the given key and the directories of the stand; ufw (ssh, 80, 443, and the
# one rule the stand needs from its own network); unattended security upgrades; key-only ssh; the nightly backup job.
#
# Sources: Docker's install steps -- https://docs.docker.com/engine/install/ubuntu/ (read 2026-10-08).

set -Eeuo pipefail

BOOTSTRAP_VERSION=1
DEPLOY_USER=deploy
SSH_PORT=22
PUBKEY="${HERD_DEPLOY_PUBKEY:-}"
PUBKEY_FILE=""
PUBKEY_STDIN=0
SWAP_SIZE=4G
STAND_SUBNET=172.28.77.0/24
STAND_GATEWAY=172.28.77.1
HARDEN_SSH=1
SKIP=""
DOCKER_KEY_FINGERPRINT="9DC858229FC7DD38854AE2D88D81803C0EBFCD88" # Docker's apt signing key (docs.docker.com/engine/install/ubuntu/)

log() { printf '[bootstrap] %s\n' "$*"; }
warn() { printf '[bootstrap] WARNING %s\n' "$*" >&2; }
die() {
  printf '[bootstrap] error: %s\n' "$*" >&2
  exit 1
}
step() { printf '\n[bootstrap] ==> %s\n' "$*"; }
trap 'printf "[bootstrap] FAILED at line %s: %s\n" "${LINENO}" "${BASH_COMMAND}" >&2' ERR

usage() {
  sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-1}"
}

while (($# > 0)); do
  case "$1" in
    --pubkey-file)
      PUBKEY_FILE="${2:?--pubkey-file needs a path}"
      shift 2
      ;;
    --pubkey-stdin)
      PUBKEY_STDIN=1
      shift
      ;;
    --deploy-user)
      DEPLOY_USER="${2:?--deploy-user needs a name}"
      shift 2
      ;;
    --ssh-port)
      SSH_PORT="${2:?--ssh-port needs a number}"
      shift 2
      ;;
    --swap-size)
      SWAP_SIZE="${2:?--swap-size needs a size}"
      shift 2
      ;;
    --stand-subnet)
      STAND_SUBNET="${2:?--stand-subnet needs a CIDR}"
      shift 2
      ;;
    --stand-gateway)
      STAND_GATEWAY="${2:?--stand-gateway needs an address}"
      shift 2
      ;;
    --no-ssh-hardening)
      HARDEN_SSH=0
      shift
      ;;
    --container)
      SKIP="${SKIP:+${SKIP},}systemd,ufw,swap,sshd,time,upgrade"
      shift
      ;;
    --skip)
      SKIP="${SKIP:+${SKIP},}${2:?--skip needs a list}"
      shift 2
      ;;
    -h | --help) usage 0 ;;
    *) die "unknown argument '$1' (--help)" ;;
  esac
done

skipped() { [[ ",${SKIP}," == *",$1,"* ]]; }
have_systemd() { [[ -d /run/systemd/system ]] && ! skipped systemd; }

export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1
# A fresh image runs cloud-init and unattended-upgrades in its first minutes, and both hold the dpkg lock.
apt_get() { apt-get -o DPkg::Lock::Timeout=900 -o Dpkg::Options::=--force-confold -y -q "$@"; }

FILE_CHANGED=0
# install_file PATH MODE: the new content is on stdin; the file is replaced only if it differs (FILE_CHANGED says so)
install_file() {
  local path="$1" mode="$2" tmp
  tmp="$(mktemp)"
  cat >"${tmp}"
  mkdir -p "$(dirname "${path}")"
  if [[ -f "${path}" ]] && cmp -s "${tmp}" "${path}"; then
    FILE_CHANGED=0
  else
    install -m "${mode}" "${tmp}" "${path}"
    FILE_CHANGED=1
  fi
  rm -f "${tmp}"
}

# --- checks of the arguments -----------------------------------------------------------------------------------------------

check_arguments() {
  [[ "${DEPLOY_USER}" =~ ^[a-z_][a-z0-9_-]{0,30}$ ]] || die "bad --deploy-user '${DEPLOY_USER}'"
  [[ "${SSH_PORT}" =~ ^[1-9][0-9]{0,4}$ ]] || die "bad --ssh-port '${SSH_PORT}'"
  ((SSH_PORT <= 65535)) || die "bad --ssh-port '${SSH_PORT}'"
  [[ "${SWAP_SIZE}" =~ ^[1-9][0-9]*[MG]$ ]] || die "bad --swap-size '${SWAP_SIZE}' (like 4G or 2048M)"
  [[ "${STAND_SUBNET}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/[0-9]+$ ]] || die "bad --stand-subnet '${STAND_SUBNET}'"
  [[ "${STAND_GATEWAY}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "bad --stand-gateway '${STAND_GATEWAY}'"
  if [[ -n "${PUBKEY_FILE}" ]]; then
    [[ -r "${PUBKEY_FILE}" ]] || die "cannot read ${PUBKEY_FILE}"
    PUBKEY="$(head -n 1 "${PUBKEY_FILE}")"
  elif ((PUBKEY_STDIN)); then
    IFS= read -r PUBKEY || true
  fi
  [[ -n "${PUBKEY}" ]] || die "no public key: --pubkey-file FILE, --pubkey-stdin or HERD_DEPLOY_PUBKEY (the deploy user would be unreachable)"
  # one line, the key types OpenSSH knows, base64 body, optional comment
  local re='^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\.com|sk-ecdsa-sha2-nistp256@openssh\.com) [A-Za-z0-9+/]+=*( [^[:cntrl:]]*)?$'
  [[ "${PUBKEY}" =~ $re ]] || die "that does not look like one OpenSSH public key line (it must be the .pub file, not the private key)"
}

preflight() {
  [[ "$(id -u)" == 0 ]] || die "run as root (ssh root@host, or sudo)"
  [[ -r /etc/os-release ]] || die "no /etc/os-release"
  # shellcheck disable=SC1091
  . /etc/os-release
  [[ "${ID:-}" == ubuntu ]] || die "this is for Ubuntu 24.04; found ${PRETTY_NAME:-an unknown system}"
  case "${VERSION_ID:-}" in
    24.04) ;;
    22.04 | 26.04) warn "Ubuntu ${VERSION_ID}: Docker supports it, but this kit was written for 24.04" ;;
    *) warn "Ubuntu ${VERSION_ID:-?} is not one Docker lists as supported (22.04, 24.04, 26.04)" ;;
  esac
  ARCH="$(dpkg --print-architecture)"
  CODENAME="${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}"
  [[ -n "${CODENAME}" ]] || die "cannot tell the Ubuntu codename"
  log "Ubuntu ${VERSION_ID} (${CODENAME}), ${ARCH}; deploy user '${DEPLOY_USER}'; skipped: ${SKIP:-nothing}"
  if have_systemd && command -v cloud-init >/dev/null 2>&1; then
    log "waiting for cloud-init to finish (it holds the package lock while it runs)"
    timeout 600 cloud-init status --wait >/dev/null 2>&1 || true
  fi
}

# --- steps -------------------------------------------------------------------------------------------------------------------

base_packages() {
  step "base packages"
  apt_get update
  if skipped upgrade; then
    log "upgrade of the installed packages: skipped"
  else
    apt_get upgrade
  fi
  apt_get install ca-certificates curl gnupg ufw rsync cron lsof jq openssl iproute2 unattended-upgrades tar gzip
}

ensure_time_sync() {
  step "clock"
  if skipped time || ! have_systemd; then
    log "clock sync: skipped (no booted systemd here)"
    return 0
  fi
  timedatectl set-ntp true 2>/dev/null || true
  if ! systemctl is-active --quiet systemd-timesyncd && ! systemctl is-active --quiet chrony; then
    log "no NTP client is running: installing chrony"
    apt_get install chrony
    systemctl enable --now chrony
  fi
  for _ in $(seq 1 30); do
    [[ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null || true)" == yes ]] && {
      log "clock: synchronized"
      return 0
    }
    sleep 2
  done
  warn "the clock is not reported as synchronized yet (the oracle records carry the time); check 'timedatectl status'"
}

ensure_swap() {
  step "swap"
  local mem_kb avail_kb want_kb unit_kb
  mem_kb="${HERD_BOOTSTRAP_MEM_KB:-$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)}"
  if ((mem_kb >= 4 * 1024 * 1024)); then
    log "RAM is $((mem_kb / 1024)) MiB (4 GiB or more): no swap added"
    return 0
  fi
  if [[ -z "${HERD_BOOTSTRAP_MEM_KB:-}" && -n "$(swapon --show --noheadings 2>/dev/null || true)" ]]; then
    log "RAM is $((mem_kb / 1024)) MiB and there is swap already: left as it is"
    return 0
  fi
  if skipped swap; then
    log "RAM is $((mem_kb / 1024)) MiB: would add a ${SWAP_SIZE} swap file /swapfile (skipped here)"
    return 0
  fi
  unit_kb=1024
  [[ "${SWAP_SIZE}" == *G ]] && unit_kb=1048576
  want_kb=$((${SWAP_SIZE%[MG]} * unit_kb))
  avail_kb="$(df -Pk / | awk 'NR == 2 {print $4}')"
  if ((avail_kb < want_kb + 5 * 1024 * 1024)); then
    warn "only $((avail_kb / 1024)) MiB free on /: not making a ${SWAP_SIZE} swap file (the build of the images wants memory; use a larger VPS)"
    return 0
  fi
  log "RAM is $((mem_kb / 1024)) MiB: adding a ${SWAP_SIZE} swap file (the Rust and Maven builds of the images are hungry)"
  if [[ ! -f /swapfile ]]; then
    fallocate -l "${SWAP_SIZE}" /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=$((want_kb / 1024)) status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
  fi
  if swapon /swapfile 2>/dev/null; then
    log "swap on: /swapfile ${SWAP_SIZE}"
  else
    warn "swapon /swapfile failed (the file system may not allow a swap file); the builds of the images may run out of memory"
  fi
  grep -qE '^/swapfile[[:space:]]' /etc/fstab || echo '/swapfile none swap sw 0 0' >>/etc/fstab
  install_file /etc/sysctl.d/99-herd.conf 0644 <<'EOF'
# Written by tools/vps/bootstrap.sh: use the swap file only when memory is really short.
vm.swappiness=10
EOF
  sysctl -q -p /etc/sysctl.d/99-herd.conf || true
}

install_docker() {
  step "Docker Engine"
  local pkg
  for pkg in docker.io docker-compose docker-compose-v2 podman-docker; do
    if dpkg -s "${pkg}" >/dev/null 2>&1; then
      die "the Ubuntu package '${pkg}' is installed and conflicts with Docker's own packages; remove it (apt-get remove ${pkg}) and run this again"
    fi
  done
  install -m 0755 -d /etc/apt/keyrings
  if [[ ! -s /etc/apt/keyrings/docker.asc ]]; then
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  fi
  chmod a+r /etc/apt/keyrings/docker.asc
  if ! gpg --show-keys --with-colons /etc/apt/keyrings/docker.asc 2>/dev/null | awk -F: '$1 == "fpr" {print $10}' | grep -x "${DOCKER_KEY_FINGERPRINT}" >/dev/null; then
    rm -f /etc/apt/keyrings/docker.asc
    die "the key downloaded from download.docker.com does not have the fingerprint ${DOCKER_KEY_FINGERPRINT}; refusing to trust it"
  fi
  log "Docker's apt key: fingerprint ${DOCKER_KEY_FINGERPRINT} confirmed"
  install_file /etc/apt/sources.list.d/docker.sources 0644 <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${CODENAME}
Components: stable
Architectures: ${ARCH}
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  # Logs of every container are rotated: the stand's containers have no logging options of their own (the server logs at DEBUG),
  # and a 40 GB disk would fill. Written before the first start of the daemon; merged into a file that is already there.
  mkdir -p /etc/docker
  local desired='{"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"5"}}' merged
  if [[ -s /etc/docker/daemon.json ]]; then
    merged="$(jq -S -s '.[0] * .[1]' /etc/docker/daemon.json <(printf '%s' "${desired}"))" || die "/etc/docker/daemon.json is not valid JSON"
  else
    merged="$(printf '%s' "${desired}" | jq -S .)"
  fi
  install_file /etc/docker/daemon.json 0644 <<<"${merged}"
  DOCKER_CONFIG_CHANGED="${FILE_CHANGED}"
  apt_get update
  apt_get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  if have_systemd; then
    systemctl enable docker containerd >/dev/null 2>&1 || true
    if systemctl is-active --quiet docker; then
      if [[ "${DOCKER_CONFIG_CHANGED}" == 1 ]]; then
        warn "daemon.json changed: restarting Docker (containers with a restart policy come back)"
        systemctl restart docker
      fi
    else
      systemctl start docker
    fi
  else
    log "Docker's service is not started here (no booted systemd)"
  fi
  log "installed: $(dpkg-query -W -f='docker-ce ${Version}' docker-ce 2>/dev/null || echo '?'), $(dpkg-query -W -f='compose plugin ${Version}' docker-compose-plugin 2>/dev/null || echo '?')"
}

setup_deploy_user() {
  step "deploy user '${DEPLOY_USER}'"
  getent group docker >/dev/null || groupadd docker
  if id -u "${DEPLOY_USER}" >/dev/null 2>&1; then
    log "user exists"
  else
    if command -v adduser >/dev/null 2>&1; then
      adduser --disabled-password --gecos "" "${DEPLOY_USER}" >/dev/null
    else
      useradd --create-home --shell /bin/bash "${DEPLOY_USER}"
    fi
    log "user created"
  fi
  # usermod rewrites /etc/group even when the user is in the group already; the second run of this script changes nothing.
  if ! id -nG "${DEPLOY_USER}" | tr ' ' '\n' | grep -x docker >/dev/null; then
    usermod -aG docker "${DEPLOY_USER}"
    log "${DEPLOY_USER} added to the docker group"
  fi
  DEPLOY_HOME="$(getent passwd "${DEPLOY_USER}" | cut -d: -f6)"
  [[ -d "${DEPLOY_HOME}" ]] || die "no home directory for ${DEPLOY_USER}"
  install -d -m 700 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "${DEPLOY_HOME}/.ssh"
  [[ -e "${DEPLOY_HOME}/.ssh/authorized_keys" ]] || : >"${DEPLOY_HOME}/.ssh/authorized_keys"
  if grep -qxF "${PUBKEY}" "${DEPLOY_HOME}/.ssh/authorized_keys"; then
    log "the key is already authorized"
  else
    printf '%s\n' "${PUBKEY}" >>"${DEPLOY_HOME}/.ssh/authorized_keys"
    log "key authorized for ${DEPLOY_USER}"
  fi
  chmod 600 "${DEPLOY_HOME}/.ssh/authorized_keys"
  chown "${DEPLOY_USER}:${DEPLOY_USER}" "${DEPLOY_HOME}/.ssh/authorized_keys"
  # The stand's places (tools/vps/README.md): the tree, Caddy, backups, the chain service's data (docker-compose.yml mounts
  # ~/.c2/testnet11 into it), the jobs of remote.sh. The key directory is private to the user.
  # One directory at a time, parents first: install -d would make the parents of a path itself, owned by root.
  local entry mode path
  for entry in 755:herd 755:edge 755:edge/conf 755:edge/download 700:backups 700:backups/pg 700:backups/c2 700:.c2 755:.herd-jobs; do
    mode="${entry%%:*}"
    path="${entry#*:}"
    install -d -m "${mode}" -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "${DEPLOY_HOME}/${path}"
  done
}

setup_ufw() {
  step "firewall (ufw)"
  ufw default deny incoming >/dev/null
  ufw default allow outgoing >/dev/null
  ufw allow "${SSH_PORT}/tcp" comment 'ssh' >/dev/null
  ufw allow 80/tcp comment 'http: certificate checks and the redirect to https' >/dev/null
  ufw allow 443 comment 'https and http/3' >/dev/null
  # The stand's server container talks to the chain service, which runs in the host's network namespace and listens on
  # the gateway address of the stand's docker network. For ufw that is a connection to the host itself, and its default
  # drops it; without this rule the oracle seq floor is never read and no round is settled.
  ufw allow from "${STAND_SUBNET}" to "${STAND_GATEWAY}" port 8090 proto tcp comment 'herd: server container -> chainsvc' >/dev/null
  ufw logging low >/dev/null
  if skipped ufw; then
    log "ufw: rules written, not switched on here (--container / --skip ufw). Status:"
    ufw show added 2>/dev/null | sed 's/^/    /' || true
  else
    ufw --force enable >/dev/null
    ufw status verbose | sed 's/^/    /'
  fi
  log "Docker publishes container ports past ufw; the stand publishes only on 127.0.0.1 (tools/vps/remote.sh), Caddy is in the host network"
}

setup_unattended_upgrades() {
  step "unattended security upgrades"
  install_file /etc/apt/apt.conf.d/20auto-upgrades 0644 <<'EOF'
// Written by tools/vps/bootstrap.sh
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
  # Ubuntu's own 50unattended-upgrades already allows the -security pocket; Docker's repository is not in the allowed
  # origins, so the Docker packages change only when someone upgrades them on purpose. No automatic reboot: a kernel
  # update shows as /var/run/reboot-required.
  if have_systemd; then
    systemctl enable --now unattended-upgrades >/dev/null 2>&1 || true
  fi
  if grep -qs -- '-security' /etc/apt/apt.conf.d/50unattended-upgrades; then
    log "periodic unattended upgrades: on, the -security pocket is allowed (50unattended-upgrades)"
  else
    warn "/etc/apt/apt.conf.d/50unattended-upgrades does not mention the -security pocket: check which origins may be upgraded unattended"
  fi
}

harden_ssh() {
  step "ssh"
  if skipped sshd || ((!HARDEN_SSH)); then
    log "sshd settings: left alone"
    return 0
  fi
  if [[ ! -x /usr/sbin/sshd ]]; then
    log "sshd is not installed here: nothing to harden"
    return 0
  fi
  [[ -s "${DEPLOY_HOME}/.ssh/authorized_keys" ]] || die "refusing to turn passwords off: the deploy user has no key"
  # Files in sshd_config.d are read in name order and the first value of an option wins, so 10- beats 50-cloud-init.conf.
  install_file /etc/ssh/sshd_config.d/10-herd.conf 0644 <<'EOF'
# Written by tools/vps/bootstrap.sh: keys only. Root may still log in with a key.
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
EOF
  if [[ "${FILE_CHANGED}" == 1 ]]; then
    if ! /usr/sbin/sshd -t; then
      rm -f /etc/ssh/sshd_config.d/10-herd.conf
      die "sshd rejects the new settings; removed them"
    fi
    if have_systemd; then
      systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || systemctl restart ssh
    fi
    log "sshd: passwords off, root by key only"
  else
    log "sshd: already set"
  fi
}

setup_backup_cron() {
  step "nightly backup job"
  install_file /etc/cron.d/herd-backup 0644 <<EOF
# Written by tools/vps/bootstrap.sh: a database dump and an archive of the chain data every night, the last 7 kept
# (tools/vps/backup.sh; ${DEPLOY_HOME}/backups/backup.log).
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
17 3 * * * ${DEPLOY_USER} [ -x ${DEPLOY_HOME}/herd/tools/vps/backup.sh ] && ${DEPLOY_HOME}/herd/tools/vps/backup.sh nightly >>${DEPLOY_HOME}/backups/backup.log 2>&1
EOF
  if have_systemd; then
    systemctl enable --now cron >/dev/null 2>&1 || true
  fi
  log "/etc/cron.d/herd-backup: 03:17 UTC every day"
}

summary() {
  step "done"
  mkdir -p /var/lib/herd-bootstrap
  printf 'version=%s\ndate=%s\ndeploy_user=%s\n' "${BOOTSTRAP_VERSION}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${DEPLOY_USER}" >/var/lib/herd-bootstrap/done
  if command -v docker >/dev/null 2>&1; then
    log "$(docker --version 2>/dev/null || true); $(docker compose version 2>/dev/null || true)"
  fi
  if [[ -f /var/run/reboot-required ]]; then
    warn "the system asks for a reboot (a kernel update): reboot it before the first deploy -- 'reboot' as root; the stand is not running yet"
  fi
  log "next, from the operator's machine: ssh -i <key> ${DEPLOY_USER}@<this host> true   and then tools/vps/secrets.sh --env-only, tools/vps/deploy.sh --build-only"
}

check_arguments
preflight
base_packages
ensure_time_sync
ensure_swap
install_docker
setup_deploy_user
setup_ufw
setup_unattended_upgrades
harden_ssh
setup_backup_cron
summary
