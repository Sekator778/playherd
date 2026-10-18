#!/usr/bin/env bash
# Mac: build the load-generator image (Gatling scenarios + wrk2, tools/bench/gen) for linux/amd64 and, with --ship, load it on the bench box
# (no internet there). Maven only through ~/.m2/settings-oss.xml; the image build uses the host network for apt and the wrk2 clone.
#   tools/bench/build-gen.sh [--ship]        prints the image tag
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GEN="$ROOT/tools/bench/gen"
SSH_HOST=${BENCH_SSH_HOST:-stand}
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-oracle   # the machine default is Java 8
mvn -s ~/.m2/settings-oss.xml -q -B -f "$GEN/pom.xml" clean package >&2
TAG="herd-bench-gen:$(cat "$GEN"/src/main/java/herdbench/*.java "$GEN/wrk/herd.lua" "$GEN/Dockerfile" | shasum | cut -c1-10)"
docker buildx build --network host --platform linux/amd64 -t "$TAG" --load "$GEN" >&2
if [ "${1:-}" = "--ship" ]; then
  docker save --platform linux/amd64 "$TAG" | ssh "$SSH_HOST" docker load >&2
  ssh "$SSH_HOST" "mkdir -p ~/herd-bench && echo $TAG > ~/herd-bench/gen-image" >&2   # run.sh reads the tag from here
fi
echo "$TAG"
