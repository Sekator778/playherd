#!/usr/bin/env bash
# tools/vps/backup.sh -- backups on the VPS, as the deploy user.
#
#   backup.sh nightly           what /etc/cron.d/herd-backup runs at 03:17 UTC (bootstrap.sh writes that file)
#   backup.sh pre-deploy        the same, taken by remote.sh before a deploy replaces the running stand
#   backup.sh list              what there is
#   backup.sh restore FILE --yes   put a database dump back: the schema is dropped and recreated from the dump, in one
#                                  transaction (rewinds the game: see README, "Rollback"), and the journal archive with the same stamp
#                                  back into the journal volume. A dump taken before D-107 has none: add --empty-journal, and the server
#                                  imports the restored table into a new journal on its start
#
# Each run writes two files into ~/backups (mode 600) and keeps the newest HERD_BACKUP_KEEP (7) of each kind:
#   pg/herd-<kind>-<UTC time>.sql.gz   pg_dump of the stand's database, taken inside the db container; --clean --if-exists
#                                      so that a restore into a database that already has tables replaces them. Checked
#                                      after writing (gzip -t, and pg_dump's closing line is there); a dump that fails
#                                      the check is deleted and the run exits non-zero
#   journal/journal-<kind>-<UTC time>.tar.gz   (D-107) the event journal, the Chronicle Queue files of the server: the TRUTH of the game, of which
#                                      the database is a copy. Taken right AFTER the dump with the same time stamp, so the journal is never
#                                      behind the dump (a journal ahead of the database is what a crash leaves; the start repairs it; the
#                                      other way round the server refuses to start). The files only grow at their end and the reader takes a
#                                      half-written last batch as not there, so a copy of the running queue is consistent. Checked after
#                                      writing (tar -t)
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
JOURNAL_DIR=/var/lib/herd/journal
JOURNAL_VOLUME=herd_journal_live
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

backup_journal() { # backup_journal KIND TS
  local kind="$1" ts="$2" cid out tmp
  cid="$(stand_container server)"
  if [[ -z "${cid}" ]]; then
    log "no running server container of the stand: no journal archive"
    return 0
  fi
  out="${BACKUP_DIR}/journal/journal-${kind}-${ts}.tar.gz"
  tmp="${out}.partial"
  if ! docker exec "${cid}" tar -C "${JOURNAL_DIR}" -cf - . | gzip -1 >"${tmp}"; then
    rm -f "${tmp}"
    log "tar of the journal failed"
    return 1
  fi
  if ! gzip -t "${tmp}" || ! gzip -dc "${tmp}" | tar -tf - >/dev/null; then
    rm -f "${tmp}"
    log "the journal archive did not pass the check (gzip -t / tar -t): deleted"
    return 1
  fi
  mv -f "${tmp}" "${out}"
  log "journal: ${out} ($(wc -c <"${out}" | tr -d ' ') bytes)"
  prune "${BACKUP_DIR}/journal" "journal-${kind}-*.tar.gz"
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
  mkdir -p "${BACKUP_DIR}/pg" "${BACKUP_DIR}/c2" "${BACKUP_DIR}/journal"
  backup_pg "${kind}" "${ts}" || rc=1
  backup_journal "${kind}" "${ts}" || rc=1 # after the dump: never behind it
  backup_c2 "${kind}" "${ts}" || rc=1
  return "${rc}"
}

cmd_list() {
  local d
  for d in pg journal c2; do
    echo "${BACKUP_DIR}/${d}:"
    { ls -lh "${BACKUP_DIR}/${d}" 2>/dev/null || true; } | sed 's/^/  /'
  done
}

cmd_restore() {
  local file="${1:-}" yes="${2:-}" empty_journal="${3:-}" cid candidate found="" journal_file="" jbase image
  [[ -n "${file}" ]] || die "usage: backup.sh restore FILE.sql.gz --yes [--empty-journal]   (a path, or a name from 'backup.sh list')"
  for candidate in "${file}" "${BACKUP_DIR}/${file}" "${BACKUP_DIR}/pg/${file}"; do
    if [[ -f "${candidate}" ]]; then
      found="${candidate}"
      break
    fi
  done
  [[ -n "${found}" ]] || die "no such dump: ${1}"
  file="${found}"
  [[ "${yes}" == "--yes" ]] || die "restore replaces the database of the running stand with the one in ${file}: everything played since is gone. Repeat with --yes."
  [[ -z "${empty_journal}" || "${empty_journal}" == "--empty-journal" ]] || die "unknown flag ${empty_journal}"
  gzip -t "${file}" || die "${file} is not a valid gzip file"
  # D-107: the journal is the truth and the database a copy of it, so the dump goes back WITH the journal of the same stamp - a dump alone would be
  # filled again with everything played since by the projector. A dump from before the journal existed has none: --empty-journal, and the server
  # imports the restored table into a new journal on its start.
  jbase="$(basename "${file}")"
  jbase="${jbase#herd-}"
  jbase="${jbase%.sql.gz}"
  candidate="${BACKUP_DIR}/journal/journal-${jbase}.tar.gz"
  if [[ "${empty_journal}" != "--empty-journal" ]]; then
    [[ -f "${candidate}" ]] || die "no journal archive ${candidate} for this dump: the journal is the truth of the game and goes back with the dump. A dump from before the journal existed: repeat with --empty-journal (the server imports the table into a new journal)"
    if ! gzip -t "${candidate}" || ! gzip -dc "${candidate}" | tar -tf - >/dev/null; then die "${candidate} is not a valid journal archive"; fi
    journal_file="${candidate}"
  fi
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
  # the journal volume is replaced after the database, which is the transaction that can fail
  image="$(docker inspect -f '{{.Image}}' "$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" --filter "label=com.docker.compose.service=server" | head -n 1)" 2>/dev/null || true)"
  if [[ -z "${image}" ]]; then
    log "the database is restored, but there is no server container to take the image from to replace the journal volume ${JOURNAL_VOLUME}: restore the journal by hand (the server stays stopped)"
    return 1
  fi
  log "replacing the journal in volume ${JOURNAL_VOLUME}: ${journal_file:-(emptied)}"
  if ! { if [[ -n "${journal_file}" ]]; then gzip -dc "${journal_file}"; else :; fi; } \
    | docker run --rm -i -v "${JOURNAL_VOLUME}:/j" --entrypoint sh "${image}" -c 'find /j -mindepth 1 -delete && if [ -n "$1" ]; then tar -C /j -xf -; fi' sh "${journal_file}"; then
    log "the database is restored but the journal volume could not be replaced -- the server stays stopped: replace the journal, then 'docker compose -p ${PROJECT} start server'"
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
