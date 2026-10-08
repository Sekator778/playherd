#!/usr/bin/env bash
# tools/vps/backup.sh -- backups on the VPS, as the deploy user.
#
#   backup.sh nightly           what /etc/cron.d/herd-backup runs at 03:17 UTC (bootstrap.sh writes that file)
#   backup.sh pre-deploy        the same, taken by remote.sh before a deploy replaces the running stand
#   backup.sh list              what there is
#   backup.sh restore FILE --yes   put a database dump back: the schema is dropped and recreated from the dump, in one
#                                  transaction (rewinds the game: see README, "Откат")
#
# Each run writes two files into ~/backups (mode 600) and keeps the newest HERD_BACKUP_KEEP (7) of each kind:
#   pg/herd-<kind>-<UTC time>.sql.gz   pg_dump of the stand's database, taken inside the db container; --clean --if-exists
#                                      so that a restore into a database that already has tables replaces them. Checked
#                                      after writing (gzip -t, and pg_dump's closing line is there); a dump that fails
#                                      the check is deleted and the run exits non-zero
#   c2/c2-<kind>-<UTC time>.tar.gz     ~/.c2/testnet11: the operator key and the chain service's state. Same disk as the
#                                      stand: this is protection against a bad deploy, not against losing the VPS (the
#                                      README says how to copy the files off it)
# <kind> is nightly or predeploy; the two are pruned separately.
#
# No stack deployed, or its db stopped: nothing to dump, and that is not an error.

set -euo pipefail

BACKUP_DIR="${HERD_BACKUP_DIR:-${HOME}/backups}"
KEEP="${HERD_BACKUP_KEEP:-7}"
C2_PARENT="${HERD_C2_PARENT:-${HOME}/.c2}"
C2_PROFILE=testnet11
PROJECT=herd
APP_DIR="${HERD_APP_DIR:-${HOME}/herd}"

log() { printf '[backup %s] %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
die() {
  printf '[backup] error: %s\n' "$*" >&2
  exit 1
}

[[ "${KEEP}" =~ ^[0-9]+$ && "${KEEP}" -ge 1 ]] || die "HERD_BACKUP_KEEP must be a number >= 1"

stand_container() { # stand_container SERVICE -> the id of the running container of that service of the stand, or nothing
  docker ps -q --filter "label=com.docker.compose.project=${PROJECT}" --filter "label=com.docker.compose.service=$1" 2>/dev/null | head -n 1 || true
}

# prune DIR GLOB: keeps the newest KEEP files that match, deletes the rest
prune() {
  local dir="$1" glob="$2" f
  # shellcheck disable=SC2086 # the glob is meant to expand
  { ls -1t "${dir}"/${glob} 2>/dev/null || true; } | tail -n "+$((KEEP + 1))" | while IFS= read -r f; do
    rm -f -- "${f}"
    log "pruned ${f}"
  done
}

backup_pg() { # backup_pg KIND TS
  local kind="$1" ts="$2" cid out tmp
  cid="$(stand_container db)"
  if [[ -z "${cid}" ]]; then
    log "no running db container of the stand: no database dump"
    return 0
  fi
  out="${BACKUP_DIR}/pg/herd-${kind}-${ts}.sql.gz"
  tmp="${out}.partial"
  if ! docker exec -i "${cid}" pg_dump -U herd -d herd --clean --if-exists --no-owner --no-privileges | gzip -9 >"${tmp}"; then
    rm -f "${tmp}"
    log "pg_dump failed"
    return 1
  fi
  if ! gzip -t "${tmp}" || ! gzip -dc "${tmp}" | tail -n 12 | grep -q 'PostgreSQL database dump complete'; then
    rm -f "${tmp}"
    log "the dump did not pass the check (gzip -t / closing line): deleted"
    return 1
  fi
  mv -f "${tmp}" "${out}"
  log "database: ${out} ($(wc -c <"${out}" | tr -d ' ') bytes)"
  prune "${BACKUP_DIR}/pg" "herd-${kind}-*.sql.gz"
}

backup_c2() { # backup_c2 KIND TS
  local kind="$1" ts="$2" out tmp rc=0
  if [[ ! -d "${C2_PARENT}/${C2_PROFILE}" ]]; then
    log "no ${C2_PARENT}/${C2_PROFILE}: no chain data to back up"
    return 0
  fi
  out="${BACKUP_DIR}/c2/c2-${kind}-${ts}.tar.gz"
  tmp="${out}.partial"
  # tar exits 1 for "a file changed while it was read": the service renames whole files into place, so every file in the
  # archive is a complete version; only 2 and up is a failure.
  tar -C "${C2_PARENT}" -czf "${tmp}" "${C2_PROFILE}" || rc=$?
  if ((rc > 1)); then
    rm -f "${tmp}"
    log "tar of ${C2_PARENT}/${C2_PROFILE} failed (${rc})"
    return 1
  fi
  tar -tzf "${tmp}" >/dev/null || {
    rm -f "${tmp}"
    log "the chain-data archive is unreadable: deleted"
    return 1
  }
  mv -f "${tmp}" "${out}"
  log "chain data: ${out} ($(wc -c <"${out}" | tr -d ' ') bytes)"
  prune "${BACKUP_DIR}/c2" "c2-${kind}-*.tar.gz"
}

cmd_backup() { # cmd_backup KIND
  local kind="$1" ts rc=0
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  umask 077
  mkdir -p "${BACKUP_DIR}/pg" "${BACKUP_DIR}/c2"
  backup_pg "${kind}" "${ts}" || rc=1
  backup_c2 "${kind}" "${ts}" || rc=1
  return "${rc}"
}

cmd_list() {
  local d
  for d in pg c2; do
    echo "${BACKUP_DIR}/${d}:"
    { ls -lh "${BACKUP_DIR}/${d}" 2>/dev/null || true; } | sed 's/^/  /'
  done
}

cmd_restore() {
  local file="${1:-}" yes="${2:-}" cid
  [[ -n "${file}" ]] || die "usage: backup.sh restore FILE.sql.gz --yes"
  [[ -f "${file}" ]] || file="${BACKUP_DIR}/pg/${file}"
  [[ -f "${file}" ]] || die "no such dump: ${1}"
  [[ "${yes}" == "--yes" ]] || die "restore replaces the database of the running stand with the one in ${file}: everything played since is gone. Repeat with --yes."
  gzip -t "${file}" || die "${file} is not a valid gzip file"
  cid="$(stand_container db)"
  [[ -n "${cid}" ]] || die "the db container of the stand is not running"
  log "stopping the server (nothing writes to the database while it is replaced)"
  docker compose -p "${PROJECT}" -f "${APP_DIR}/docker-compose.yml" stop server
  log "restoring ${file}"
  # The dump was taken with --clean --if-exists, which drops only what the dump itself creates: a table that a newer version of the server
  # added after the dump would stay behind, and deploying that version again would fail on its migration. So the schema is dropped and made
  # again first. All of it is one transaction (-1) that stops at the first error: a dump that does not apply leaves the old database as it was.
  if ! { printf 'DROP SCHEMA IF EXISTS public CASCADE;\nCREATE SCHEMA public;\n'; gzip -dc "${file}"; } \
    | docker exec -i "${cid}" psql -q -v ON_ERROR_STOP=1 -1 -U herd -d herd >/dev/null; then
    log "restore FAILED -- the database is as before; starting the server again"
    docker compose -p "${PROJECT}" -f "${APP_DIR}/docker-compose.yml" start server
    return 1
  fi
  docker compose -p "${PROJECT}" -f "${APP_DIR}/docker-compose.yml" start server
  log "restored; the server is starting (its oracle seq counter is raised to the chain service's maxKnownSeq again before it settles a round)"
}

main() {
  case "${1:-}" in
    nightly) cmd_backup nightly ;;
    pre-deploy) cmd_backup predeploy ;;
    list) cmd_list ;;
    restore)
      shift
      cmd_restore "$@"
      ;;
    *)
      sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
      exit 1
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
