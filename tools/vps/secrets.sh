#!/usr/bin/env bash
# tools/vps/secrets.sh -- puts the two kinds of secret material on the VPS. Run from the operator's machine (the Mac).
#
#   tools/vps/secrets.sh [--env-only | --data-only] [options]
#
#   .env      the stand's secrets and settings: ~/herd/.env on the VPS, mode 600. The tokens and the database password are
#             generated ON THE VPS from fresh random bytes (tools/vps/env-init.sh); nothing is read from this machine's .env
#             and nothing is printed. An existing .env keeps its secrets: only the settings below are updated.
#   data      the chain service's data -- the operator key, the oracle singleton's state, the publisher queue and the PLAY
#             treasury files of ~/.c2/testnet11 -- copied with scp into ~/.c2/testnet11 on the VPS (files 600, directory 700),
#             each file checked by sha256 on both sides.
#
# THE ONE-PUBLISHER RULE. The oracle singleton and the PLAY payouts are operated by one key. Two chain services on it would
# fight over the singleton's coin and the treasury's. So the data step refuses to run while a chainsvc of the stand on this
# machine is running or one is running on the VPS; stop the stand on this machine first (README, "Один издатель"). The .env
# step has no such limit and is the one to run early.
#
# Options:
#   --host H / --domain D / --user U / --key K / --ssh-port P    the VPS (or HERD_VPS_*, or .local/vps.env)
#   --walletconnect-id ID   the Reown project id for Sage sign-in (32 hex digits; or $VITE_WALLETCONNECT_PROJECT_ID). It is
#                           a build argument of the web image and public. This script does not read it from any .env.
#   --no-walletconnect-id   go on without one (the browser test wallet works without it; Sage does not)
#   --set NAME=VALUE        one more line for the VPS's .env, e.g. --set HERD_BOTS_COUNT=0 (repeatable; the secrets cannot be set)
#   --c2-dir DIR            where the data come from (default: ~/.c2/testnet11)
#   --operator-name NAME    the profile's name (default: operator; docker-compose.yml's C2_OPERATOR_NAME)
#   --force-data            replace data that are already on the VPS and differ (the old ones are kept as testnet11.bak-<time>);
#                           the VPS's state is newer than ours once its service has run, so this rewinds it
#   --retire-local          after the copy is verified, rename the local directory to <dir>.moved-to-vps-<date>, so that the
#                           stand on this machine cannot start on the stale state by mistake (it is renamed, not deleted)
#   --allow-local-stand     do not look for a chainsvc running on this machine
#   --dry-run               say what would be copied and run; touch nothing
#
# Sources: the file names are those of chain/c2-svc/src (keys_file.rs, state_file.rs, play_state_file.rs, oracle/queue.rs,
# play/withdrawal_queue.rs, play/deposit.rs); the .env keys are docker-compose.yml's and tools/stand.sh's.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vps/common.sh
source "${SELF_DIR}/common.sh"

DO_ENV=1
DO_DATA=1
WC_ID="${VITE_WALLETCONNECT_PROJECT_ID:-}"
NO_WC=0
EXTRAS=()
C2_DIR="${HOME}/.c2/testnet11"
OPERATOR_NAME=operator
FORCE_DATA=0
RETIRE_LOCAL=0
ALLOW_LOCAL=0
SECRET_NAMES="HERD_OPERATOR_TOKEN HERD_DB_PASSWORD C2_SVC_TOKEN HERD_CHAINSVC_TOKEN"

usage() {
  sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-1}"
}

vps_load_config
while (($# > 0)); do
  case "$1" in
    --env-only)
      DO_DATA=0
      shift
      ;;
    --data-only)
      DO_ENV=0
      shift
      ;;
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
    --walletconnect-id)
      WC_ID="${2:?}"
      shift 2
      ;;
    --no-walletconnect-id)
      NO_WC=1
      shift
      ;;
    --set)
      EXTRAS+=("${2:?--set needs NAME=VALUE}")
      shift 2
      ;;
    --c2-dir)
      C2_DIR="${2:?}"
      shift 2
      ;;
    --operator-name)
      OPERATOR_NAME="${2:?}"
      shift 2
      ;;
    --force-data)
      FORCE_DATA=1
      shift
      ;;
    --retire-local)
      RETIRE_LOCAL=1
      shift
      ;;
    --allow-local-stand)
      ALLOW_LOCAL=1
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

((DO_ENV || DO_DATA)) || die "--env-only and --data-only together leave nothing to do"
vps_finalize_config "${DO_ENV}"
[[ "${OPERATOR_NAME}" =~ ^[a-z][a-z0-9_-]{0,30}$ ]] || die "bad --operator-name '${OPERATOR_NAME}'"

# --- the .env ----------------------------------------------------------------------------------------------------------------

step_env() {
  log "== .env on the VPS"
  local kv name s args
  if [[ -z "${WC_ID}" && "${NO_WC}" != 1 ]]; then
    die "no Reown project id for Sage sign-in: pass --walletconnect-id <32 hex> (or VITE_WALLETCONNECT_PROJECT_ID), or --no-walletconnect-id to go without. It is the id of decision Р-58 in docs/product/decisions.md; it is public."
  fi
  if [[ -n "${WC_ID}" ]]; then
    valid_wc_id "${WC_ID}" || die "that is not a Reown project id (32 lowercase hex digits)"
  fi
  args="--domain ${VPS_DOMAIN}"
  [[ -z "${WC_ID}" ]] || args="${args} --walletconnect-id ${WC_ID}"
  for kv in ${EXTRAS[@]+"${EXTRAS[@]}"}; do
    name="${kv%%=*}"
    valid_env_name "${name}" || die "bad --set name in '${kv}'"
    [[ "${kv}" == *=* ]] || die "--set ${kv}: NAME=VALUE expected"
    valid_env_value "${kv#*=}" || die "bad --set value in '${kv}' (letters, digits and _.:/,@+=- only)"
    for s in ${SECRET_NAMES}; do
      [[ "${name}" != "${s}" ]] || die "--set ${s}: the secrets are generated on the VPS and cannot be set from here"
    done
    args="${args} --set ${kv}"
  done
  remote_script "${VPS_DIR}/env-init.sh" "${args}"
}

# --- the chain service's data --------------------------------------------------------------------------------------------------

FILES=()
HASHES=()

collect_files() {
  local tmpl name path
  FILES=()
  HASHES=()
  for tmpl in ${C2_REQUIRED_FILES}; do
    name="${tmpl//@NAME@/${OPERATOR_NAME}}"
    path="${C2_DIR}/${name}"
    if [[ ! -f "${path}" ]]; then
      case "${name}" in
        *.oracle_queue.json)
          die "${path} is missing. The queue is what makes the chain service start in time on the VPS: it holds the point on the chain it has read up to. Without it the service walks the oracle's whole history from the first coin before it opens its port, and docker compose gives up on it as unhealthy long before that ends. Copy it from the machine that ran the stand."
          ;;
        *) die "${path} is missing (the operator key and the oracle singleton's state are what the VPS's chain service is for)" ;;
      esac
    fi
    FILES+=("${name}")
  done
  for tmpl in ${C2_OPTIONAL_FILES}; do
    name="${tmpl//@NAME@/${OPERATOR_NAME}}"
    path="${C2_DIR}/${name}"
    if [[ -f "${path}" ]]; then
      FILES+=("${name}")
    else
      log "not there, not copied (the service makes it empty): ${name}"
    fi
  done
  for name in "${FILES[@]}"; do
    HASHES+=("$(sha256_of "${C2_DIR}/${name}")")
  done
}

remote_state_value() { # remote_state_value 'file NAME' -> the field after it
  printf '%s\n' "${REMOTE_STATE}" | awk -v k="$1" '{ line = $1 " " $2; if (line == k) print $3 }'
}

step_data() {
  log "== chain data -> ${SSH_TARGET}:${REMOTE_C2_DIR}"
  require_local_chainsvc_stopped "${ALLOW_LOCAL}" "copying the chain service's state"
  if ((!DRY_RUN)); then
    [[ -d "${C2_DIR}" ]] || die "no ${C2_DIR}"
  fi
  if ((DRY_RUN)) && [[ ! -d "${C2_DIR}" ]]; then
    log "dry run: ${C2_DIR} does not exist here; the files below are the ones that would be looked for"
    local tmpl
    for tmpl in ${C2_REQUIRED_FILES} ${C2_OPTIONAL_FILES}; do
      log "  would copy ${tmpl//@NAME@/${OPERATOR_NAME}}"
    done
    remote_script "${VPS_DIR}/data-install.sh" "prepare"
    log "dry run: then scp of each file, sha256 check on the VPS, install"
    return 0
  fi
  collect_files
  local i
  for ((i = 0; i < ${#FILES[@]}; i++)); do
    log "  ${FILES[i]}  $(wc -c <"${C2_DIR}/${FILES[i]}" | tr -d ' ') bytes"
  done

  REMOTE_STATE=""
  if ((!DRY_RUN)); then
    REMOTE_STATE="$(remote_script "${VPS_DIR}/data-install.sh" "state ${OPERATOR_NAME} ${FILES[*]}")" \
      || die "cannot read the state of the VPS (ssh to ${SSH_TARGET} failed: has tools/vps/bootstrap.sh been run?)"
    if [[ "$(printf '%s\n' "${REMOTE_STATE}" | awk '$1 == "chainsvc_running" {print $2}')" == yes ]]; then
      die "the chain service of the stand is running on the VPS: two publishers on one key. Stop it first: tools/vps/deploy.sh stop"
    fi
  fi

  local replace=0 remote_key
  remote_key="$(remote_state_value "file ${OPERATOR_NAME}.json")"
  if [[ -n "${remote_key}" && "${remote_key}" != "-" ]]; then
    if [[ "${remote_key}" == "${HASHES[0]}" ]]; then
      if ((!FORCE_DATA)); then
        log "the operator key is already on the VPS (same sha256). The state files there are the VPS's own now and are NOT touched. (--force-data copies ours over them.)"
        finish_data
        return 0
      fi
      replace=1
    elif ((FORCE_DATA)); then
      warn "the VPS holds a DIFFERENT operator key; --force-data replaces the whole directory (kept as testnet11.bak-<time>)"
      replace=1
    else
      die "the VPS already has an operator key and it is a different one than ${C2_DIR}/${OPERATOR_NAME}.json. Nothing was changed. If that is what you want, --force-data replaces the directory (the old one is kept)."
    fi
  fi
  if ((replace)); then
    warn "replacing the chain data on the VPS with the files from here; the VPS's own state is rewound to ours (the old directory is kept)"
  fi

  remote_script "${VPS_DIR}/data-install.sh" "prepare" >/dev/null
  local install_args="install" manifest=""
  ((!replace)) || install_args="install --replace"
  for ((i = 0; i < ${#FILES[@]}; i++)); do
    remote_copy "${C2_DIR}/${FILES[i]}" ".c2/.incoming/${FILES[i]}"
    manifest="${manifest} ${HASHES[i]}=${FILES[i]}"
  done
  remote_script "${VPS_DIR}/data-install.sh" "${install_args}${manifest}"
  finish_data
}

finish_data() {
  if ((RETIRE_LOCAL)); then
    local target
    target="${C2_DIR}.moved-to-vps-$(date +%Y%m%d)"
    if ((DRY_RUN)); then
      log "dry run: would rename ${C2_DIR} to ${target}"
    elif [[ -e "${target}" ]]; then
      warn "${target} exists already: ${C2_DIR} is left where it is"
    else
      mv "${C2_DIR}" "${target}"
      log "${C2_DIR} -> ${target}. The stand on this machine cannot start on the stale state now; to undo: mv ${target} ${C2_DIR}"
    fi
  else
    log "the copy of the data on this machine is stale from now on: do not start the stand here while the VPS publishes (--retire-local renames the directory to make that impossible by accident)"
  fi
}

if ((!DRY_RUN)); then
  remote "true" || die "cannot log in as ${SSH_TARGET} with ${VPS_KEY}: has tools/vps/bootstrap.sh run (tools/vps/deploy.sh bootstrap)?"
fi
((!DO_ENV)) || step_env
((!DO_DATA)) || step_data
log "done"
