# Multi-stage release build for `c2-svc` (FIXED INTERFACE (3): auth/verify +
# oracle publisher, port 8090).
#
# Builder stage cross-compiles: `--platform=$BUILDPLATFORM` pins it to the
# build host's native architecture (never emulated), and it produces a
# binary for `$TARGETARCH` via `cargo build --target <triple>` plus a
# matching cross C toolchain. This matters because `release.yml` builds
# `linux/amd64,linux/arm64` on a single amd64 GitHub Actions runner: without
# this, buildx's default (`FROM rust:1-bookworm` with no `--platform`) runs
# the `linux/arm64` variant's `rustc`/`cc` itself under QEMU, which is what
# made that leg of the v0.1.0 release run take 50+ minutes. `blst` (via
# `chia-wallet-sdk` -> `chia-bls`) and `ring` both have build scripts that
# compile C/assembly through the `cc` crate, so a plain `rustup target add`
# is not enough when cross-compiling -- a target-matching cross gcc plus
# `CC_<triple>`/`AR_<triple>`/`CARGO_TARGET_<TRIPLE>_LINKER` are needed too
# (see the builder `RUN` below). On this Mac (arm64, decision D-44) and in
# any single-platform local build where `TARGETPLATFORM == BUILDPLATFORM`,
# this is a native build as before, just via an explicit `--target`.
#
# Runtime stage always runs on `$TARGETPLATFORM` natively (no `--platform`
# there): `debian:bookworm-slim`, glibc-compatible with the bookworm builder
# regardless of which arch built the binary.
#
# Build context is `chain/` (the cargo workspace root, so the `c2-cli` path
# dependency and the workspace `Cargo.lock` are both in scope), not
# `chain/c2-svc/`:
#
#   docker buildx build --platform linux/amd64,linux/arm64 --push \
#     -f chain/c2-svc/Dockerfile -t <registry>/c2-svc chain/
#
# (a multi-platform build needs `--push`; for a local image build one
# platform at a time with `--load`, e.g. `--platform linux/arm64 --load`)
#
# `docker-compose.yml` (project "herd", D-44) is expected to set this
# service's `build.context` to `./chain` and `build.dockerfile` to
# `c2-svc/Dockerfile` accordingly (week-3 integration work, not this crate).
# It does not set `platform:`, so its build stays on the native path
# (`TARGETPLATFORM == BUILDPLATFORM`, the host's own arch) -- unaffected by
# the change below.

# Toolchain pinned to the minor of the local `rustc` (1.98.1 on 2026-10-01; `rust:1.98-bookworm`
# exists on Docker Hub) instead of the floating `rust:1-bookworm`, so a rebuild cannot change the
# compiler under a fixed `Cargo.lock` unnoticed — bump it on purpose, together with a local
# `cargo test --workspace`. The patch still floats for compiler bug-fix releases. Local builds and
# CI pin the exact version in `chain/rust-toolchain.toml`, which this build does not read (the
# COPY lines below take the manifests and the crates only): keep the minor of the two in step.
FROM --platform=$BUILDPLATFORM rust:1.98-bookworm AS builder
ARG TARGETARCH
ARG BUILDARCH
WORKDIR /build

# Workspace manifest + lockfile first, then every member's sources --
# `sim-spike` and `c2-cli` are needed too: `Cargo.toml`'s `[workspace]`
# lists all three, and `c2-svc` depends on `c2-cli` by path (see
# `c2-svc/Cargo.toml`'s comment on why -- reusing `c2-cli`'s exact
# `OracleState`/`KeyFile` JSON shapes and wallet-key/signing/cost code
# instead of reimplementing them).
COPY Cargo.toml Cargo.lock ./
COPY sim-spike ./sim-spike
COPY c2-cli ./c2-cli
COPY c2-svc ./c2-svc

# Map Docker's arch names to rustc target triples, add that target,
# install a cross C toolchain when cross-compiling (skipped on the native
# path, where the image's own build-essential gcc already works), point
# cargo/cc at it, then build. `--locked`: fail rather than silently
# re-resolve `Cargo.lock` (the same pinned-versions discipline
# `c2-cli/README.md` documents for local builds).
# Note: for amd64 the Debian *package* name (`gcc-x86-64-linux-gnu`) and the
# installed *binary* prefix (`x86_64-linux-gnu-gcc`) disagree on hyphen vs.
# underscore -- `apt_triple`/`bin_triple` are split to account for that
# (arm64's package name and binary prefix happen to match).
RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) rust_triple=x86_64-unknown-linux-gnu; apt_triple=x86-64-linux-gnu; bin_triple=x86_64-linux-gnu ;; \
      arm64) rust_triple=aarch64-unknown-linux-gnu; apt_triple=aarch64-linux-gnu; bin_triple=aarch64-linux-gnu ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    rustup target add "$rust_triple"; \
    env_triple=$(echo "$rust_triple" | tr '-' '_'); \
    env_triple_upper=$(echo "$env_triple" | tr '[:lower:]' '[:upper:]'); \
    if [ "$TARGETARCH" != "$BUILDARCH" ]; then \
      apt-get update; \
      apt-get install -y --no-install-recommends \
        "gcc-${apt_triple}" "libc6-dev-${TARGETARCH}-cross"; \
      rm -rf /var/lib/apt/lists/*; \
      cross_gcc="${bin_triple}-gcc"; \
      export "CC_${env_triple}=${cross_gcc}"; \
      export "AR_${env_triple}=${bin_triple}-ar"; \
      export "CARGO_TARGET_${env_triple_upper}_LINKER=${cross_gcc}"; \
    fi; \
    cargo build --release --locked -p c2-svc --target "$rust_triple"; \
    mkdir -p /build/out; \
    cp "target/${rust_triple}/release/c2-svc" /build/out/c2-svc

FROM debian:bookworm-slim AS runtime
# `reqwest`'s `rustls-tls` feature bundles its own trust roots
# (`webpki-roots`), but `ca-certificates` is kept anyway -- cheap, and
# avoids depending on that not changing across a `reqwest`/rustls version
# bump. **предложение**.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/out/c2-svc /usr/local/bin/c2-svc

# Task instruction: "loads the operator key and oracle state from a data
# dir (env C2_DATA_DIR, default /data)". On the local stand this is
# bind-mounted to `~/.c2/testnet11` (the same directory `c2 oracle-launch`
# already wrote `operator.json`/`operator.oracle.json` into) read-write.
ENV C2_DATA_DIR=/data
VOLUME ["/data"]

# FIXED INTERFACE (3): service name "chainsvc", port 8090.
EXPOSE 8090

# Runs as root inside the container by default (matches `c2-cli`'s own
# local-only, no-untrusted-input threat model -- this service only ever
# talks to this stand's own server and coinset.org, never the public
# internet directly); revisit if/when this ships beyond the local stand,
# **предложение**.
ENTRYPOINT ["/usr/local/bin/c2-svc"]
