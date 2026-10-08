#!/usr/bin/env bash
# tools/vps/env-init.sh -- the VPS side of tools/vps/secrets.sh: creates or updates the stand's .env on the VPS.
# secrets.sh pipes it over ssh (`bash -s -- ARGS < env-init.sh`); it is not kept there. It can run anywhere, with
# HERD_APP_DIR pointing at the directory that holds (or will hold) the .env -- tools/vps/test.sh does that.
#
#   env-init.sh --domain DOMAIN [--walletconnect-id ID] [--set NAME=VALUE]...
#
# The four secrets docker-compose.yml needs -- HERD_OPERATOR_TOKEN, HERD_DB_PASSWORD and the chain service's token, which
# is one value under two names (C2_SVC_TOKEN for chainsvc, HERD_CHAINSVC_TOKEN for the server), exactly what
# tools/stand.sh's ensure_env_file generates -- are made HERE, on the VPS, from openssl's random bytes. They never exist
# on the operator's machine, and the values of the Mac's own .env are never read or reused.
#
# A secret that is already in the file is kept, whatever the arguments say, and never printed. That matters for one of
# them above all: Postgres applies HERD_DB_PASSWORD only while it initializes an EMPTY data directory, so a changed
# password in .env locks the server out of its own database.
#
# The file is written next to its final place with mode 600 and moved over the old one, so a reader sees one version or the
# other. Output: what was done, with names of settings and never their secret values.

set -euo pipefail

APP_DIR="${HERD_APP_DIR:-${HOME}/herd}"
ENV_FILE="${APP_DIR}/.env"
SECRETS="HERD_OPERATOR_TOKEN HERD_DB_PASSWORD C2_SVC_TOKEN HERD_CHAINSVC_TOKEN"

die() {
  printf '[env-init] error: %s\n' "$*" >&2
  exit 1
}

domain=""
wc_id=""
extras=()
while (($# > 0)); do
  case "$1" in
    --domain)
      domain="${2:?--domain needs a value}"
      shift 2
      ;;
    --walletconnect-id)
      wc_id="${2:?--walletconnect-id needs a value}"
      shift 2
      ;;
    --set)
      extras+=("${2:?--set needs NAME=VALUE}")
      shift 2
      ;;
    *) die "unknown argument '$1'" ;;
  esac
done

# The same patterns secrets.sh checks, repeated: this script may be run by hand.
[[ "${domain}" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || die "bad or missing --domain"
if [[ -n "${wc_id}" ]]; then
  [[ "${wc_id}" =~ ^[0-9a-f]{32}$ ]] || die "bad --walletconnect-id (32 hex digits)"
fi
for kv in ${extras[@]+"${extras[@]}"}; do
  [[ "${kv}" =~ ^[A-Z][A-Z0-9_]{0,63}=[A-Za-z0-9_.:/,@+=-]*$ ]] || die "bad --set '${kv}' (NAME=VALUE, no spaces or quotes)"
  for s in ${SECRETS}; do
    [[ "${kv%%=*}" != "${s}" ]] || die "--set ${s}: the secrets are generated here, not set from outside"
  done
done

umask 077
mkdir -p "${APP_DIR}"
new="$(mktemp "${ENV_FILE}.XXXXXX")"
trap 'rm -f "${new}" "${new}.tmp"' EXIT

random_hex() { # random_hex BYTES
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$1"
  else
    head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}
get() { grep -m1 "^$1=" "${new}" | cut -d= -f2- || true; }
has() { grep -q "^$1=" "${new}"; }

# set_kv NAME VALUE: replaces the value in place when it differs, appends the line when there is none, leaves the file
# alone when it already says that
set_kv() {
  local key="$1" val="$2"
  if has "${key}"; then
    [[ "$(get "${key}")" == "${val}" ]] && return 0
    awk -v k="${key}" -v v="${val}" 'BEGIN { FS = OFS = "=" } $1 == k { print k "=" v; next } { print }' "${new}" >"${new}.tmp"
    mv "${new}.tmp" "${new}"
  else
    printf '%s=%s\n' "${key}" "${val}" >>"${new}"
  fi
  CHANGED_KEYS="${CHANGED_KEYS:-} ${key}"
}

if [[ -f "${ENV_FILE}" ]]; then
  cp "${ENV_FILE}" "${new}"
  state=updated
else
  printf '%s\n' "# The stand's .env on the VPS -- written by tools/vps/secrets.sh (env-init.sh), $(date -u +%Y-%m-%dT%H:%M:%SZ). Secrets are this VPS's own." \
    "# NEVER commit or copy it anywhere. docker-compose.yml and tools/stand.sh read it; Postgres takes HERD_DB_PASSWORD only on a" \
    "# first start with an empty database, so do not change that one afterwards." >"${new}"
  state=created
fi
CHANGED_KEYS=""
GENERATED=""

# --- the secrets: made once, kept for good
if ! has HERD_OPERATOR_TOKEN; then
  printf 'HERD_OPERATOR_TOKEN=%s\n' "$(random_hex 32)" >>"${new}"
  GENERATED="${GENERATED} HERD_OPERATOR_TOKEN"
fi
if ! has HERD_DB_PASSWORD; then
  printf 'HERD_DB_PASSWORD=%s\n' "$(random_hex 24)" >>"${new}"
  GENERATED="${GENERATED} HERD_DB_PASSWORD"
fi
svc_token="$(get C2_SVC_TOKEN)"
hsvc_token="$(get HERD_CHAINSVC_TOKEN)"
if [[ -n "${svc_token}" && -n "${hsvc_token}" && "${svc_token}" != "${hsvc_token}" ]]; then
  die "C2_SVC_TOKEN and HERD_CHAINSVC_TOKEN differ in ${ENV_FILE}: they have to be one value (chainsvc and the server must agree). Fix it by hand."
fi
shared="${svc_token:-${hsvc_token}}"
if [[ -z "${shared}" ]]; then
  shared="$(random_hex 32)"
  GENERATED="${GENERATED} C2_SVC_TOKEN HERD_CHAINSVC_TOKEN"
fi
has C2_SVC_TOKEN || printf 'C2_SVC_TOKEN=%s\n' "${shared}" >>"${new}"
has HERD_CHAINSVC_TOKEN || printf 'HERD_CHAINSVC_TOKEN=%s\n' "${shared}" >>"${new}"

# --- settings of this deployment
set_kv HERD_PUBLIC_DOMAIN "${domain}" # the domain a player signs at sign-in: it has to be the address of the site
[[ -z "${wc_id}" ]] || set_kv VITE_WALLETCONNECT_PROJECT_ID "${wc_id}" # a build argument of the web image (public)
# The session cookie of a site served over TLS. docker-compose.yml passes it on to the server only if its environment lists
# HERD_COOKIE_SECURE (README, "Открытые пункты"); the line costs nothing until then.
set_kv HERD_COOKIE_SECURE true
for kv in ${extras[@]+"${extras[@]}"}; do
  set_kv "${kv%%=*}" "${kv#*=}"
done

if [[ "${state}" == updated && -z "${GENERATED}" && -z "${CHANGED_KEYS}" ]]; then
  echo ".env: unchanged (${ENV_FILE})"
  exit 0
fi
chmod 600 "${new}"
mv -f "${new}" "${ENV_FILE}"
trap - EXIT
echo ".env: ${state} (${ENV_FILE}, mode 600)"
[[ -z "${GENERATED}" ]] || echo "  generated here, not shown:${GENERATED}"
[[ -z "${CHANGED_KEYS}" ]] || echo "  set:${CHANGED_KEYS}"
