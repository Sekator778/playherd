#!/usr/bin/env bash
# tools/github/setup-repo.sh — one-time(-ish) idempotent GitHub repo setup for the branching
# model in docs/ops/branching.md (Herd, decision from Alex 2026-09-26: develop + main + tags).
#
# WHAT THIS DOES (see docs/ops/branching.md for the full rationale):
#   1. Creates the `develop` branch from `main` if it doesn't exist yet.
#   2. Optionally sets `develop` as the repo's default branch (new PRs/Dependabot target it).
#   3. Applies repository rulesets: `main` (PR required, ~1 approval, admin bypass on the PR
#      requirement only, required "ci-ok" check, linear history, squash-only, no force-push/
#      delete), `develop` (required "ci-ok", no force-push), tag ruleset on `v*` (immutable:
#      no delete/move).
#   4. Enables Dependabot security updates + Dependabot alerts.
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
#     see docs/ops/branching.md §7) is reported clearly and does NOT abort the rest of the
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
REPO="Sekator778/herd"
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
  echo "environments, Dependabot security updates). This does NOT touch main or tags directly." >&2
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
# private-repo limitation — see docs/ops/branching.md §7), so the rest of the script still runs.
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
    if echo "$out" | grep -qi "Upgrade to GitHub Pro"; then
      echo "  [skipped — plan limit] ${method} ${path}: private repo on GitHub Free can't use this API." >&2
      echo "      -> either upgrade the account plan, or wait for the Р-54 public repo; see docs/ops/branching.md §7." >&2
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
    - Dependabot (dependabot.yml) opens PRs against the default branch — with `develop` default,
      dependency-bump PRs land on develop (go through the same `ci-ok` gate and a normal
      develop->main promotion), never straight into main.
    - Downside: the repo's GitHub homepage, `git clone`'s checked-out branch, and anything that
      reads "the default branch" (e.g. some security tab views) will show develop, not main.
      This does NOT affect releases: a git tag on main is a ref in its own right, independent of
      which branch is "default" — `git tag vX.Y.Z main` and GitHub Releases both work exactly
      the same regardless of this setting.
    Alternative (pass --keep-default-branch): keep `main` as default, and instead set
    `target-branch: develop` explicitly for every ecosystem in .github/dependabot.yml (already
    done in this repo's dependabot.yml), and remember to change the PR base by hand each time.
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
  gh api "repos/${REPO}/rulesets" --jq ".[] | select(.name==\"$1\") | .id" 2>/dev/null || true
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
    { "type": "required_linear_history" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": ${MIN_APPROVALS},
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": true,
        "require_last_push_approval": false,
        "required_review_thread_resolution": true,
        "allowed_merge_methods": ["squash"]
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
  # No pull_request rule on purpose (docs/ops/branching.md §6/§7): agents may push straight to
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
# 4. Dependabot security updates + alerts
# ---------------------------------------------------------------------------
section "4. Dependabot security updates"
if [[ "$TOGGLE_DEPENDABOT_SECURITY" -eq 1 ]]; then
  call_api PUT "/vulnerability-alerts" ""
  call_api PUT "/automated-security-fixes" ""
else
  echo "  [skipped] --skip-dependabot-toggle"
fi
echo "  (this is separate from .github/dependabot.yml's version-update schedule, which needs no API call — it's picked up from the file itself once merged)"

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
  # Deployment-branch policy for "production". Two entries, both needed:
  #   - tag v* so a human/manual run against a release tag is allowed;
  #   - branch main because .github/workflows/deploy.yml's production job is triggered by
  #     `workflow_run` (after release.yml finishes) — a workflow_run-triggered run's own
  #     `github.ref` is always the repo's default branch (main), never the tag that triggered
  #     the upstream run. Without the `main` entry here, GitHub rejects the job from using this
  #     environment before deploy.yml's own tag-derivation/guard logic ever runs. See deploy.yml's
  #     "production" job comment and docs/ops/deploy.md "Как это устроено".
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
  Repository variable (NOT secret — Р-58, docs/product/decisions.md: this projectId is public,
  it ships in the web bundle regardless):
    - VITE_WALLETCONNECT_PROJECT_ID = 680e4897ed8a55aa0b10263cc74159dd

  No secret is needed to push images to GHCR: use the workflow-scoped GITHUB_TOKEN with
  `permissions: { packages: write }` in release.yml.

  Environment "production" secrets (VPS deploy — blocked until Р-38's VPS exists, docs/product/
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

  Explicitly NOT a CI/CD secret (per this task's constraint and Р-21, docs/product/decisions.md):
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
  echo "    Nothing was changed. Re-run with --apply (and review docs/ops/branching.md §7 first,"
  echo "    especially the GitHub Free private-repo plan limitation on rulesets/environment"
  echo "    protection) to actually apply this."
fi
