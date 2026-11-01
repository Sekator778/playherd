#!/usr/bin/env bash
# tools/github/setup-repo.sh — one-time(-ish) idempotent GitHub repo setup for the branching
# model in CONTRIBUTING.md (Herd, decision from Alex 2026-09-26: develop + main + tags).
#
# WHAT THIS DOES (see CONTRIBUTING.md for the full rationale):
#   1. Creates the `develop` branch from `main` if it doesn't exist yet.
#   2. Optionally sets `develop` as the repo's default branch (new PRs target it).
#   3. Applies repository rulesets: `main` (PR required, ~1 approval, admin bypass on the PR
#      requirement only, required "ci-ok" check, merge commit (develop → main) or squash (hotfix/*), no force-push/
#      delete), `develop` (required "ci-ok", no force-push), tag ruleset on `v*` (immutable:
#      no delete/move).
#   4. Enables Dependabot alerts (security updates stay off, see §4 below).
#   5. Creates `staging`/`production` environments (production optionally gated on a required
#      reviewer).
#   6. Prints the list of secrets/variables someone (Alex) still needs to add by hand — this
#      script never creates or reads secret values.
#
# SAFETY / CONSTRAINTS (task requirement — do not relax these without Alex's say-so):
#   - Defaults to --dry-run: prints the exact `gh api` calls it would make, changes NOTHING.
#   - Real changes require the explicit --apply flag.
#   - Never touches main/tags itself (no `git push`, no `git tag` from this script).
#   - Never prints or stores secret VALUES — only names it recommends adding.
#   - Every mutating call is wrapped so a 403 "Upgrade to GitHub Pro or make this repository
#     public" (confirmed 2026-09-26 on this private Free-plan repo for the rulesets endpoint —
#     see CONTRIBUTING.md §7) is reported clearly and does NOT abort the rest of the
#     script — later steps that don't need that plan tier still run.
#
# Usage:
#   tools/github/setup-repo.sh                 # dry-run (default), full plan
#   tools/github/setup-repo.sh --apply         # actually perform the changes
#   tools/github/setup-repo.sh --apply --yes   # skip the confirmation prompt before --apply
#   tools/github/setup-repo.sh --repo owner/name --min-approvals 1 --keep-default-branch \
#       --no-production-reviewer --skip-environments --skip-dependabot-toggle
#
# Requires: `gh` authenticated with a token that has `repo` scope (and admin on the repo for the
# mutating calls — `gh auth status` / repo settings can confirm).

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults / flags
# ---------------------------------------------------------------------------
REPO="Sekator778/playherd"
DRY_RUN=1
ASSUME_YES=0
MIN_APPROVALS=1
SET_DEFAULT_BRANCH=1
ADMIN_BYPASS=1
CREATE_ENVIRONMENTS=1
PRODUCTION_REVIEWER=1
PRODUCTION_REVIEWER_LOGIN="Sekator778"
TOGGLE_DEPENDABOT_SECURITY=1
APPLY_MAIN_RULESET=1
APPLY_DEVELOP_RULESET=1
APPLY_TAG_RULESET=1
REQUIRED_CHECK_NAME="ci-ok"

usage() {
  sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply) DRY_RUN=0 ;;
    --dry-run) DRY_RUN=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    --repo) REPO="$2"; shift ;;
    --min-approvals) MIN_APPROVALS="$2"; shift ;;
    --required-check) REQUIRED_CHECK_NAME="$2"; shift ;;
    --keep-default-branch) SET_DEFAULT_BRANCH=0 ;;
    --no-admin-bypass) ADMIN_BYPASS=0 ;;
    --skip-environments) CREATE_ENVIRONMENTS=0 ;;
    --no-production-reviewer) PRODUCTION_REVIEWER=0 ;;
    --production-reviewer-login) PRODUCTION_REVIEWER_LOGIN="$2"; shift ;;
    --skip-dependabot-toggle) TOGGLE_DEPENDABOT_SECURITY=0 ;;
    --skip-main-ruleset) APPLY_MAIN_RULESET=0 ;;
    --skip-develop-ruleset) APPLY_DEVELOP_RULESET=0 ;;
    --skip-tag-ruleset) APPLY_TAG_RULESET=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
  shift
done

if ! command -v gh >/dev/null 2>&1; then
  echo "error: gh (GitHub CLI) not found in PATH" >&2
  exit 1
fi

if [[ "$DRY_RUN" -eq 0 && "$ASSUME_YES" -eq 0 ]]; then
  echo "About to make REAL changes to https://github.com/${REPO} (rulesets, default branch," >&2
  echo "environments, Dependabot alerts). This does NOT touch main or tags directly." >&2
  read -r -p "Continue? [y/N] " reply
  case "$reply" in
    y|Y|yes|YES) ;;
    *) echo "Aborted."; exit 1 ;;
  esac
fi

MODE_LABEL="DRY-RUN (no changes made)"
[[ "$DRY_RUN" -eq 0 ]] && MODE_LABEL="APPLY (real changes)"
echo "==> tools/github/setup-repo.sh — ${MODE_LABEL} — repo: ${REPO}"
echo

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# call_api METHOD PATH [JSON_BODY]
# In dry-run mode: prints the call. In apply mode: makes it, and turns a 403 whose body mentions
# "Upgrade to GitHub Pro" into a clearly-labelled skip instead of a hard failure (Free-plan
# private-repo limitation — see CONTRIBUTING.md §7), so the rest of the script still runs.
call_api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "  [dry-run] gh api -X ${method} repos/${REPO}${path}"
    if [[ -n "$body" ]]; then
      echo "$body" | (command -v jq >/dev/null 2>&1 && jq . || cat) | sed 's/^/      /'
    fi
    return 0
  fi
  local out status
  if [[ -n "$body" ]]; then
    if out=$(printf '%s' "$body" | gh api -X "$method" "repos/${REPO}${path}" --input - 2>&1); then
      status=0
    else
      status=$?
    fi
  else
    if out=$(gh api -X "$method" "repos/${REPO}${path}" 2>&1); then
      status=0
    else
      status=$?
    fi
  fi
  if [[ $status -ne 0 ]]; then
    # Free-plan private repo: 403 "Upgrade to GitHub Pro" (rulesets) or 422 "ensure the billing
    # plan supports the required reviewers protection rule" (environment reviewers).
    if echo "$out" | grep -qiE "Upgrade to GitHub Pro|billing plan supports"; then
      echo "  [skipped — plan limit] ${method} ${path}: private repo on GitHub Free can't use this API." >&2
      echo "      -> either upgrade the account plan, or wait for the D-54 public repo; see CONTRIBUTING.md §7." >&2
      return 0
    fi
    echo "  [ERROR] ${method} ${path} failed:" >&2
    echo "$out" | sed 's/^/      /' >&2
    return $status
  fi
  echo "  [ok] ${method} ${path}"
  return 0
}

section() { echo; echo "--- $1 ---"; }

# ---------------------------------------------------------------------------
# 0. Sanity: confirm repo exists and who we're talking to
# ---------------------------------------------------------------------------
section "0. Sanity check"
if ! gh api "repos/${REPO}" >/dev/null 2>&1; then
  echo "error: cannot read repos/${REPO} — check gh auth / repo name" >&2
  exit 1
fi
echo "  [ok] repos/${REPO} is reachable"

# ---------------------------------------------------------------------------
# 1. develop branch from main
# ---------------------------------------------------------------------------
section "1. develop branch"
if gh api "repos/${REPO}/git/ref/heads/develop" >/dev/null 2>&1; then
  echo "  [ok] refs/heads/develop already exists — nothing to do"
else
  MAIN_SHA=$(gh api "repos/${REPO}/git/ref/heads/main" --jq '.object.sha')
  echo "  main is at ${MAIN_SHA}"
  call_api POST "/git/refs" "{\"ref\":\"refs/heads/develop\",\"sha\":\"${MAIN_SHA}\"}"
fi

# ---------------------------------------------------------------------------
# 2. Default branch
# ---------------------------------------------------------------------------
section "2. Default branch"
if [[ "$SET_DEFAULT_BRANCH" -eq 1 ]]; then
  CURRENT_DEFAULT=$(gh api "repos/${REPO}" --jq '.default_branch' 2>/dev/null || echo "main")
  if [[ "$CURRENT_DEFAULT" == "develop" ]]; then
    echo "  [ok] default branch is already develop"
  else
    cat <<'EOF'
  Rationale for switching the default branch to `develop` (explain, as asked):
    - New PRs default their base to the repo's default branch — with `develop` as default,
      `gh pr create` / the GitHub UI default straight into develop, matching the branch model
      (feature/* -> develop) without anyone having to remember to change the base branch.
    - Downside: the repo's GitHub homepage, `git clone`'s checked-out branch, and anything that
      reads "the default branch" (e.g. some security tab views) will show develop, not main.
      This does NOT affect releases: a git tag on main is a ref in its own right, independent of
      which branch is "default" — `git tag vX.Y.Z main` and GitHub Releases both work exactly
      the same regardless of this setting.
    Alternative (pass --keep-default-branch): keep `main` as default, and remember to change the
    PR base by hand each time.
EOF
    call_api PATCH "" "{\"default_branch\":\"develop\"}"
  fi
else
  echo "  [skipped] --keep-default-branch passed"
fi

# ---------------------------------------------------------------------------
# 3. Rulesets
# ---------------------------------------------------------------------------
section "3. Rulesets"

# ruleset_id_by_name NAME -> prints id or nothing
ruleset_id_by_name() {
  # On a 403 (Free-plan private repo) gh still prints the raw error JSON to stdout, which used to
  # be taken for a ruleset id and abort the script at step 3 — accept a numeric id only.
  local out
  out=$(gh api "repos/${REPO}/rulesets" --jq ".[] | select(.name==\"$1\") | .id" 2>/dev/null) || return 0
  if [[ "$out" =~ ^[0-9]+$ ]]; then
    echo "$out"
  fi
  return 0
}

# upsert_ruleset NAME JSON_BODY
upsert_ruleset() {
  local name="$1" body="$2"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "  [dry-run] create-or-update ruleset '${name}':"
    echo "$body" | (command -v jq >/dev/null 2>&1 && jq . || cat) | sed 's/^/      /'
    return 0
  fi
  local existing_id
  existing_id=$(ruleset_id_by_name "$name")
  if [[ -n "$existing_id" ]]; then
    call_api PUT "/rulesets/${existing_id}" "$body"
  else
    call_api POST "/rulesets" "$body"
  fi
}

if [[ "$ADMIN_BYPASS" -eq 1 ]]; then
  # RepositoryRole id 5 = Admin (confirmed against GitHub's ruleset API: bypass_actors entries
  # observed as {"actor_id":5,"actor_type":"RepositoryRole","bypass_mode":"always"} for the
  # built-in admin role). bypass_mode differs per ruleset below on purpose — see comments.
  BYPASS_ALWAYS='[{"actor_id":5,"actor_type":"RepositoryRole","bypass_mode":"always"}]'
  BYPASS_PR_ONLY='[{"actor_id":5,"actor_type":"RepositoryRole","bypass_mode":"pull_request"}]'
else
  BYPASS_ALWAYS='[]'
  BYPASS_PR_ONLY='[]'
fi

if [[ "$APPLY_MAIN_RULESET" -eq 1 ]]; then
  # bypass_mode "pull_request" (not "always"): the solo owner must still open a PR into main
  # (no direct push) but isn't blocked waiting for an approval that, with one collaborator, can
  # never come from anyone else — GitHub does not allow self-approval.
  MAIN_RULESET=$(cat <<EOF
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": ${BYPASS_PR_ONLY},
  "conditions": { "ref_name": { "include": ["refs/heads/main"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": ${MIN_APPROVALS},
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": true,
        "require_last_push_approval": false,
        "required_review_thread_resolution": true,
        "allowed_merge_methods": ["merge", "squash"]
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": true,
        "required_status_checks": [ { "context": "${REQUIRED_CHECK_NAME}" } ]
      }
    }
  ]
}
EOF
)
  upsert_ruleset "main" "$MAIN_RULESET"
else
  echo "  [skipped] --skip-main-ruleset"
fi

if [[ "$APPLY_DEVELOP_RULESET" -eq 1 ]]; then
  # No pull_request rule on purpose (CONTRIBUTING.md §6/§7): agents may push straight to
  # develop. required_status_checks still blocks pushing a commit whose SHA hasn't already
  # recorded a passing "ci-ok" check.
  DEVELOP_RULESET=$(cat <<EOF
{
  "name": "develop",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": ${BYPASS_ALWAYS},
  "conditions": { "ref_name": { "include": ["refs/heads/develop"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [ { "context": "${REQUIRED_CHECK_NAME}" } ]
      }
    }
  ]
}
EOF
)
  upsert_ruleset "develop" "$DEVELOP_RULESET"
else
  echo "  [skipped] --skip-develop-ruleset"
fi

if [[ "$APPLY_TAG_RULESET" -eq 1 ]]; then
  # Immutable release tags: nobody (short of the bypass actor) can delete or move v* once
  # pushed. Creation is intentionally NOT restricted — the only collaborator already has push
  # access, and restricting creation buys nothing extra today.
  TAG_RULESET=$(cat <<EOF
{
  "name": "release tags (v*)",
  "target": "tag",
  "enforcement": "active",
  "bypass_actors": ${BYPASS_ALWAYS},
  "conditions": { "ref_name": { "include": ["refs/tags/v*"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "update" }
  ]
}
EOF
)
  upsert_ruleset "release tags (v*)" "$TAG_RULESET"
else
  echo "  [skipped] --skip-tag-ruleset"
fi

# ---------------------------------------------------------------------------
# 4. Dependabot alerts
# ---------------------------------------------------------------------------
# Alerts only: they run no Actions. Security updates (/automated-security-fixes) stay off: every PR
# they open runs the full CI on the limited Actions minutes (CONTRIBUTING.md §9, D-112).
section "4. Dependabot alerts"
if [[ "$TOGGLE_DEPENDABOT_SECURITY" -eq 1 ]]; then
  call_api PUT "/vulnerability-alerts" ""
else
  echo "  [skipped] --skip-dependabot-toggle"
fi

# ---------------------------------------------------------------------------
# 5. Environments
# ---------------------------------------------------------------------------
section "5. Environments (staging, production)"
if [[ "$CREATE_ENVIRONMENTS" -eq 1 ]]; then
  call_api PUT "/environments/staging" "{}"

  if [[ "$PRODUCTION_REVIEWER" -eq 1 ]]; then
    REVIEWER_ID=$(gh api "users/${PRODUCTION_REVIEWER_LOGIN}" --jq '.id' 2>/dev/null || echo "")
    if [[ -n "$REVIEWER_ID" ]]; then
      PROD_BODY="{\"reviewers\":[{\"type\":\"User\",\"id\":${REVIEWER_ID}}],\"deployment_branch_policy\":{\"protected_branches\":false,\"custom_branch_policies\":true}}"
    else
      echo "  [warn] could not resolve user id for ${PRODUCTION_REVIEWER_LOGIN}; creating production without a required reviewer" >&2
      PROD_BODY='{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}'
    fi
  else
    PROD_BODY='{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}'
  fi
  call_api PUT "/environments/production" "$PROD_BODY"
  # Deployment-branch policy for "production". Two entries, both needed — GitHub checks the run's
  # own ref against them before the job may use the environment:
  #   - tag v*: release.yml's last job dispatches deploy.yml with `--ref vX.Y.Z`;
  #   - branch main: the manual path, `gh workflow run deploy.yml --ref main -f environment=production
  #     -f image_tag=X.Y.Z`.
  # A `workflow_run`-triggered run carries the repo's default branch (develop) and would be rejected
  # by both — deploy.yml's production job therefore no longer listens to one. See deploy.yml's
  # "production" job comment and the deployment notes §0.
  call_api POST "/environments/production/deployment-branch-policies" "{\"name\":\"v*\",\"type\":\"tag\"}"
  call_api POST "/environments/production/deployment-branch-policies" "{\"name\":\"main\",\"type\":\"branch\"}"
else
  echo "  [skipped] --skip-environments"
fi

# ---------------------------------------------------------------------------
# 6. Secrets / variables to add by hand (never created by this script)
# ---------------------------------------------------------------------------
section "6. Secrets / variables checklist (add these yourself — this script does not set values)"
cat <<'EOF'
  Repository variable (NOT secret — D-58, the decision log: this projectId is public,
  it ships in the web bundle regardless):
    - VITE_WALLETCONNECT_PROJECT_ID = 680e4897ed8a55aa0b10263cc74159dd

  Repository variable, optional (D-67: Actions minutes are limited, so image publishing is OFF
  until this is set):
    - HERD_PUBLISH_DEVELOP = true   — ci.yml pushes the :develop and :sha-<short> images to ghcr.io
                                       after every green push to develop (the staging deploy pulls
                                       :develop). Leave unset for a one-off publish instead:
                                       gh workflow run ci.yml --ref develop -f publish=true
                                       (CONTRIBUTING.md §2, §7)

  No secret is needed to push images to GHCR: use the workflow-scoped GITHUB_TOKEN with
  `permissions: { packages: write }` in release.yml.

  Environment "production" secrets (VPS deploy — blocked until D-38's VPS exists, docs/product/
  decisions.md; add once Alex has provisioned it — names must match .github/workflows/deploy.yml
  exactly, which is what actually reads them):
    - VPS_HOST         — VPS hostname/IP
    - VPS_USER         — deploy-only SSH user (not Alex's personal account)
    - VPS_SSH_KEY      — private key for that deploy-only user (generate a NEW keypair for CI;
                          never reuse Alex's own key or the testnet operator key)
    - VPS_KNOWN_HOSTS  — output of `ssh-keyscan -p <port> <host>`, so deploy.yml can pin the
                          host key instead of disabling StrictHostKeyChecking
    - VPS_SSH_PORT     — only if not 22

  Same four secrets (VPS_HOST/VPS_USER/VPS_SSH_KEY/VPS_KNOWN_HOSTS, + VPS_SSH_PORT if not 22)
  again on environment "staging" once it has its own deploy target — deploy.yml's staging job
  reads them from there, independently of production's copies.

  Explicitly NOT a CI/CD secret (per this task's constraint and D-21, the decision log):
  the Chia testnet11 operator/oracle key. It stays only on whichever machine runs chainsvc
  (today: Atlas; later: the VPS itself, provisioned directly there) and never touches GitHub
  Actions.

  Environment "staging" — no secrets identified yet; add here if/when staging gets its own
  deploy target distinct from production.
EOF

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo
echo "==> Done (${MODE_LABEL})."
if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "    Nothing was changed. Re-run with --apply (and review CONTRIBUTING.md §7 first,"
  echo "    especially the GitHub Free private-repo plan limitation on rulesets/environment"
  echo "    protection) to actually apply this."
fi
