# Task runner (D-66): one entry point over the native build tools — Maven (engine + server),
# Cargo (chain/), npm (web/). Mirrors the CI gates in .github/workflows/ci.yml so a green
# `make check` locally means the same checks CI runs. `make` or `make help` lists the targets.
#
# Local defaults follow the project notes ("Build"): Java 21 from SDKMAN and the open-source Maven
# settings profile. Both are overridable (`make test-java JDK21=… MVN_SETTINGS=`), and both
# fall back to the plain toolchain when the path does not exist (CI runners, other machines).

SHELL := bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

# JAVA_HOME is set explicitly for Maven only: the shell's own JAVA_HOME here is SDKMAN's default
# Java 8, which must stay as is (the project notes ("Build")).
JDK21 ?= $(HOME)/.sdkman/candidates/java/21.0.11-oracle
MVN_JAVA := $(if $(wildcard $(JDK21)),JAVA_HOME=$(JDK21),)

OSS_SETTINGS := $(HOME)/.m2/settings-oss.xml
MVN_SETTINGS ?= $(if $(wildcard $(OSS_SETTINGS)),-s $(OSS_SETTINGS),)
MVN := $(MVN_JAVA) mvn -B $(MVN_SETTINGS) -f pom.xml

# Server tests: Quarkus Dev Services starts a throwaway Postgres by itself unless PG_URL is set
# (CI points it at its `postgres` service container the same way).
PG_URL ?=
PG_USER ?= herd
PG_PASSWORD ?= herd
MVN_PG := $(if $(PG_URL),-Dquarkus.datasource.jdbc.url=$(PG_URL) -Dquarkus.datasource.username=$(PG_USER) -Dquarkus.datasource.password=$(PG_PASSWORD),)

WEB_URL ?= http://localhost:8480
# Oracle singleton launched for the testnet11 demo (docker-compose.yml, chainsvc service).
ORACLE_LAUNCHER ?= c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20

# Android APK: where the WebView loads the game from (web/capacitor.config.ts, read by `cap sync`).
# Empty = the emulator's address of the Mac stand, http://10.0.2.2:8480.
HERD_APP_URL ?=

# verify-oracle needs one non-stdlib package (chia_rs, tools/verify-oracle/requirements.txt). It is
# installed into a venv created on first use; the venv gets its own `*` .gitignore, so git never
# sees it. PYTHON picks the interpreter that creates it (3.9+; Debian/Ubuntu need python3-venv).
PYTHON ?= python3
ORACLE_VENV ?= tools/verify-oracle/.venv
ORACLE_STAMP := $(ORACLE_VENV)/.requirements-installed


.PHONY: help check build test \
        test-java build-java test-chain lint-chain build-chain \
        web-deps test-web build-web apk \
        test-tools stand-up stand-down stand-status stand-logs tunnel tunnel-up tunnel-url tunnel-down e2e e2e-markets verify-oracle \
        

help: ## list targets
	@grep -hE '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

check: test-java test-chain lint-chain test-web build-web ## all CI gates except the e2e smoke

build: build-java build-chain build-web ## build every component

test: test-java test-chain test-web ## unit + integration tests of every component

# --- engine + server (Maven reactor: engine is built and tested before server) ---

test-java: ## mvn verify for engine + bots + server
	$(MVN) -pl bots,server -am verify $(MVN_PG)

build-java: ## package engine + bots + server without tests
	$(MVN) -pl bots,server -am package -DskipTests

# --- chain (Rust workspace) ---

test-chain: ## cargo test --workspace
	cd chain && cargo test --workspace --locked

lint-chain: ## cargo clippy (warnings allowed, same as CI)
	cd chain && cargo clippy --workspace --all-targets --locked

build-chain: ## release build of c2-cli and c2-svc
	cd chain && cargo build --release --locked -p c2-cli -p c2-svc

# --- web ---

web-deps:
	cd web && { [ -d node_modules ] || npm ci; }

test-web: web-deps ## typecheck, lint, vitest
	cd web && npm run typecheck && npm run lint && npm test -- --run

build-web: web-deps ## production web bundle
	cd web && npm run build

apk: web-deps ## Android debug APK (Capacitor); HERD_APP_URL=https://host points it at a real server (default: emulator, http://10.0.2.2:8480)
	cd web && export HERD_APP_URL='$(HERD_APP_URL)' && npm run build:capacitor && npx cap sync android && cd android && $(MVN_JAVA) ./gradlew assembleDebug

# --- local stand (docker compose project "herd", via tools/stand.sh) and e2e ---

# Tests of the stand script and of the end-to-end runs that need neither docker nor a network: a fake `docker` for
# tools/stand.sh, a fake stand on localhost with rounds of a fraction of a second for tools/e2e, a fake server on localhost for
# `tools/herd-operator.sh feedback` (D-81). Not part of `check`: no CI job runs them.
test-tools: ## tests of tools/stand.sh, of the tools/e2e runs (a fake stand) and of herd-operator.sh feedback; no docker, no network
	bash tools/test-stand.sh
	node --test tools/e2e/*.test.mjs tools/operator.test.mjs
	$(PYTHON) -m unittest tools/audit/test_settlement_audit.py tools/audit/test_bot_stats.py

stand-up: ## start the local stand on :8480
	tools/stand.sh up

stand-down: ## remove stand containers (keeps the herd_pg_live volume)
	tools/stand.sh down

stand-status: ## show stand containers and health
	tools/stand.sh status

stand-logs: ## follow stand logs
	tools/stand.sh logs

# Cloudflare quick tunnel: no account, prints a random https://<x>.trycloudflare.com that changes
# on every start; caffeinate (macOS) keeps the Mac awake while it runs.
tunnel: ## public https link to the local stand (share with testers)
	$(if $(shell command -v caffeinate),caffeinate -i,) cloudflared tunnel --no-autoupdate --url http://localhost:$(or $(HERD_WEB_PORT),8480)

# Same tunnel as a launchd job (macOS): survives closing the terminal / agent session. The URL is
# printed by `make tunnel-url`; it changes on every tunnel-up, so add it to the Reown allowlist
# again (Sage sign-in) and resend it to testers.
TUNNEL_LABEL := com.herd.tunnel
TUNNEL_LOG := $(HOME)/Library/Logs/herd-tunnel.log

tunnel-up: ## start the public tunnel detached from this shell (launchd)
	@launchctl list $(TUNNEL_LABEL) >/dev/null 2>&1 && { echo "already running — make tunnel-url / make tunnel-down"; exit 1; } || true
	@: > $(TUNNEL_LOG)
	launchctl submit -l $(TUNNEL_LABEL) -o $(TUNNEL_LOG) -e $(TUNNEL_LOG) -- /usr/bin/caffeinate -i $(shell command -v cloudflared) tunnel --no-autoupdate --url http://localhost:$(or $(HERD_WEB_PORT),8480)
	@for i in $$(seq 1 30); do grep -qoE 'https://[a-z0-9-]+\.trycloudflare\.com' $(TUNNEL_LOG) && break; sleep 1; done; $(MAKE) -s tunnel-url

tunnel-url: ## print the current public tunnel URL
	@grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' $(TUNNEL_LOG) | tail -1

tunnel-down: ## stop the detached tunnel
	launchctl remove $(TUNNEL_LABEL)

e2e: web-deps ## 4-player end-to-end run against WEB_URL (default the local stand)
	node --experimental-wasm-modules tools/e2e/run.mjs $(WEB_URL)

# One market of each price source (spot crypto, an xyz market, an odds market), side by side in the same pass. Spot is the 24/7
# family: it is skipped only when its market is closed, and a spot market without a live price, or a spot round cancelled for any
# other reason, fails the run; xyz and odds are skipped only for the reasons of their own (a closed market, an event about to
# end, no price while the market behind xyz is closed for the weekend). Needs the stand to be up; see the header of
# tools/e2e/run-markets.mjs.
e2e-markets: web-deps ## one market of each price source (spot crypto, xyz, odds) played in the same pass against WEB_URL
	node --experimental-wasm-modules tools/e2e/run-markets.mjs $(WEB_URL)

$(ORACLE_STAMP): tools/verify-oracle/requirements.txt
	$(PYTHON) -m venv $(ORACLE_VENV)
	printf '*\n' > $(ORACLE_VENV)/.gitignore
	$(ORACLE_VENV)/bin/python -m pip install --quiet --upgrade pip
	$(ORACLE_VENV)/bin/python -m pip install --quiet -r tools/verify-oracle/requirements.txt
	touch $@

verify-oracle: $(ORACLE_STAMP) ## public oracle verifier (testnet11) in its own venv, cross-checked against WEB_URL (WEB_URL= for chain only)
	$(ORACLE_VENV)/bin/python tools/verify-oracle/verify_oracle.py $(ORACLE_LAUNCHER) $(if $(WEB_URL),--stand-url $(WEB_URL),)

# --- public repository  (D-54) ---

