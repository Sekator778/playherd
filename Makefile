# Task runner (Р-66): one entry point over the native build tools — Maven (engine + server),
# Cargo (chain/), npm (web/). Mirrors the CI gates in .github/workflows/ci.yml so a green
# `make check` locally means the same checks CI runs. `make` or `make help` lists the targets.
#
# Local defaults follow CLAUDE.md «Сборка»: Java 21 from SDKMAN and the open-source Maven
# settings profile. Both are overridable (`make test-java JDK21=… MVN_SETTINGS=`), and both
# fall back to the plain toolchain when the path does not exist (CI runners, other machines).

SHELL := bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

# JAVA_HOME is set explicitly for Maven only: the shell's own JAVA_HOME here is SDKMAN's default
# Java 8, which must stay as is (CLAUDE.md «Сборка»).
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

.PHONY: help check build test \
        test-java build-java test-chain lint-chain build-chain \
        web-deps test-web build-web apk \
        stand-up stand-down stand-status stand-logs e2e verify-oracle

help: ## list targets
	@grep -hE '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

check: test-java test-chain lint-chain test-web build-web ## all CI gates except the e2e smoke

build: build-java build-chain build-web ## build every component

test: test-java test-chain test-web ## unit + integration tests of every component

# --- engine + server (Maven reactor: engine is built and tested before server) ---

test-java: ## mvn verify for engine + server
	$(MVN) -pl server -am verify $(MVN_PG)

build-java: ## package engine + server without tests
	$(MVN) -pl server -am package -DskipTests

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

apk: web-deps ## Android debug APK (Capacitor)
	cd web && npm run build:capacitor && npx cap sync android && cd android && ./gradlew assembleDebug

# --- local stand (docker compose project "herd", via tools/stand.sh) and e2e ---

stand-up: ## start the local stand on :8480
	tools/stand.sh up

stand-down: ## remove stand containers (keeps the herd_pg volume)
	tools/stand.sh down

stand-status: ## show stand containers and health
	tools/stand.sh status

stand-logs: ## follow stand logs
	tools/stand.sh logs

e2e: web-deps ## 4-player end-to-end run against WEB_URL (default the local stand)
	node --experimental-wasm-modules tools/e2e/run.mjs $(WEB_URL)

verify-oracle: ## public oracle verifier (testnet11), cross-checked against WEB_URL
	python3 tools/verify-oracle/verify_oracle.py $(ORACLE_LAUNCHER) --stand-url $(WEB_URL)
