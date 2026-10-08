#!/usr/bin/env bash
# tools/vps/data-install.sh -- the VPS side of the chain-data half of tools/vps/secrets.sh. secrets.sh pipes it over ssh
# (`bash -s -- ARGS < data-install.sh`); it is not kept on the VPS. It can run anywhere with HERD_C2_PARENT set to the
# directory that holds (or will hold) testnet11 -- tools/vps/test.sh does that.
#
#   data-install.sh state OPERATOR FILE...   what the VPS has: lines "chainsvc_running yes|no", "dir_present yes|no" and
#                                            "file <name> <sha256 or ->" for each FILE
#   data-install.sh prepare                  an empty, private ~/.c2/.incoming for the files that scp is about to send
#   data-install.sh install [--replace] SHA256=NAME...
#                                            checks every file in .incoming against the checksums (the script itself is
#                                            what bash reads from stdin, so the manifest comes as arguments), then moves
#                                            the files into ~/.c2/testnet11 (mode 600, directory 700).
#                                            --replace first sets the old directory aside as testnet11.bak-<time>.
#
# Nothing here prints the content of a file. The directory is the one docker-compose.yml mounts into chainsvc
# (${HOME}/.c2/testnet11). The install refuses while that service runs: it rewrites these files all the time, and
# a copy placed under it would be overwritten or, worse, would rewind it.

set -euo pipefail

C2_PARENT="${HERD_C2_PARENT:-${HOME}/.c2}"
PROFILE=testnet11
DIR="${C2_PARENT}/${PROFILE}"
INCOMING="${C2_PARENT}/.incoming"

die() {
  printf '[data-install] error: %s\n' "$*" >&2
  exit 1
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

chainsvc_running() {
  command -v docker >/dev/null 2>&1 || return 1
  [[ -n "$(docker ps -q --filter label=com.docker.compose.project=herd --filter label=com.docker.compose.service=chainsvc 2>/dev/null || true)" ]]
}

safe_name() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die "bad file name '$1'"; }

cmd_state() {
  local operator="${1:?usage: state OPERATOR FILE...}" name
  shift
  safe_name "${operator}.json"
  if chainsvc_running; then echo "chainsvc_running yes"; else echo "chainsvc_running no"; fi
  if [[ -d "${DIR}" ]]; then echo "dir_present yes"; else echo "dir_present no"; fi
  for name in "$@"; do
    safe_name "${name}"
    if [[ -f "${DIR}/${name}" ]]; then
      echo "file ${name} $(sha256_of "${DIR}/${name}")"
    else
      echo "file ${name} -"
    fi
  done
}

cmd_prepare() {
  umask 077
  mkdir -p "${C2_PARENT}"
  chmod 700 "${C2_PARENT}"
  rm -rf "${INCOMING}"
  mkdir -m 700 "${INCOMING}"
  echo "prepared ${INCOMING}"
}

cmd_install() {
  local replace=0 pair hash name count=0 seen=" " stamp f
  if [[ "${1:-}" == "--replace" ]]; then
    replace=1
    shift
  fi
  chainsvc_running && die "the chain service of the stand is running on this host: stop it first (docker compose -p herd stop), then repeat"
  [[ -d "${INCOMING}" ]] || die "no ${INCOMING}: run 'prepare' and send the files first"
  (($# > 0)) || die "empty manifest: install [--replace] SHA256=NAME..."
  local manifest=()
  for pair in "$@"; do
    hash="${pair%%=*}"
    name="${pair#*=}"
    [[ "${hash}" =~ ^[0-9a-f]{64}$ ]] || die "bad checksum in '${pair}'"
    safe_name "${name}"
    [[ -f "${INCOMING}/${name}" ]] || die "${name} was not received"
    [[ "$(sha256_of "${INCOMING}/${name}")" == "${hash}" ]] || die "${name}: the checksum differs after the transfer"
    manifest+=("${name}")
    seen="${seen}${name} "
  done
  # Nothing may be left in .incoming that the manifest does not name
  for f in "${INCOMING}"/*; do
    [[ -e "${f}" ]] || continue
    [[ "${seen}" == *" $(basename "${f}") "* ]] || die "$(basename "${f}") is in ${INCOMING} but not in the manifest"
  done
  umask 077
  if [[ -d "${DIR}" && "${replace}" == 1 ]]; then
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    mv "${DIR}" "${DIR}.bak-${stamp}"
    echo "the old ${DIR} is now ${DIR}.bak-${stamp}"
  fi
  mkdir -p "${DIR}"
  chmod 700 "${DIR}"
  for name in "${manifest[@]}"; do
    mv -f "${INCOMING}/${name}" "${DIR}/${name}"
    chmod 600 "${DIR}/${name}"
    count=$((count + 1))
  done
  rmdir "${INCOMING}"
  echo "installed ${count} files into ${DIR} (mode 600, directory 700)"
}

case "${1:-}" in
  state)
    shift
    cmd_state "$@"
    ;;
  prepare) cmd_prepare ;;
  install)
    shift
    cmd_install "$@"
    ;;
  *)
    sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
    exit 1
    ;;
esac
