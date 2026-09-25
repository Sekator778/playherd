# Multi-stage release build for `c2-svc` (FIXED INTERFACE (3): auth/verify +
# oracle publisher, port 8090). Runs on this Mac's Docker (arm64 Linux,
# decision Р-44) -- the official `rust`/`debian` images are multi-arch, so no
# cross-compilation flags are needed; the build simply runs natively inside
# the arm64 Linux container.
#
# Build context is `chain/` (the cargo workspace root, so the `c2-cli` path
# dependency and the workspace `Cargo.lock` are both in scope), not
# `chain/c2-svc/`:
#
#   docker build -f chain/c2-svc/Dockerfile -t c2-svc chain/
#
# `docker-compose.yml` (project "herd", Р-44) is expected to set this
# service's `build.context` to `./chain` and `build.dockerfile` to
# `c2-svc/Dockerfile` accordingly (week-3 integration work, not this crate).

FROM rust:1-bookworm AS builder
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

# `--locked`: fail rather than silently re-resolve `Cargo.lock` (the same
# pinned-versions discipline `c2-cli/README.md` documents for local builds).
RUN cargo build --release --locked -p c2-svc

FROM debian:bookworm-slim AS runtime
# `reqwest`'s `rustls-tls` feature bundles its own trust roots
# (`webpki-roots`), but `ca-certificates` is kept anyway -- cheap, and
# avoids depending on that not changing across a `reqwest`/rustls version
# bump. **предложение**.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/release/c2-svc /usr/local/bin/c2-svc

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
