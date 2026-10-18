#!/usr/bin/env bash
# Ship a commit of this repo and its bench images from the Mac to the bench box (~/herd-bench/repo).
#   tools/bench/ship.sh [--sha <commit>] [--no-image]
# the bench box is air-gapped (no Maven Central, no registry), so the server image is built HERE for linux/amd64
# (docker buildx, build stage on host network because this Mac's default bridge has no egress, see docker-compose.yml)
# and loaded there with `docker load`. The repo tree is `git archive <sha>` (tracked files only, no dirty tree), plus
# the working-tree copy of tools/bench/ (the bench files may not be committed yet). Nothing on the bench box outside
# ~/herd-bench/ is touched, except `docker load` adding image tags.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSH_HOST=${BENCH_SSH_HOST:-stand}
REMOTE=herd-bench
SHA=$(git -C "$ROOT" rev-parse HEAD)
BUILD_IMAGE=1
while [ $# -gt 0 ]; do
  case $1 in
    --sha) SHA=$(git -C "$ROOT" rev-parse "$2"); shift 2 ;;
    --no-image) BUILD_IMAGE=0; shift ;;
    *) echo "usage: ship.sh [--sha <commit>] [--no-image]" >&2; exit 2 ;;
  esac
done
SHA12=${SHA:0:12}
IMAGE="herd-bench-server:${SHA12}"
log() { printf '[bench-ship] %s\n' "$*" >&2; }

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
git -C "$ROOT" archive "$SHA" | tar -x -C "$STAGE"
rm -rf "$STAGE/tools/bench" && mkdir -p "$STAGE/tools" && cp -R "$ROOT/tools/bench" "$STAGE/tools/bench"

if [ "$BUILD_IMAGE" = 1 ]; then
  log "building $IMAGE (linux/amd64)"
  docker buildx build --network host --platform linux/amd64 -f "$STAGE/server/Dockerfile" -t "$IMAGE" --load "$STAGE"
fi

log "syncing the tree of $SHA12 to $SSH_HOST:~/$REMOTE/repo"
ssh "$SSH_HOST" "mkdir -p ~/$REMOTE/repo ~/$REMOTE/results"
COPYFILE_DISABLE=1 tar -C "$STAGE" -c . | ssh "$SSH_HOST" "rm -rf ~/$REMOTE/repo/* && tar -x -C ~/$REMOTE/repo && echo $SHA > ~/$REMOTE/repo/.bench-sha"

if ! ssh "$SSH_HOST" "docker image inspect postgres:16-alpine >/dev/null 2>&1"; then
  log "loading postgres:16-alpine (amd64)"
  docker pull --platform linux/amd64 postgres:16-alpine >/dev/null
  docker save --platform linux/amd64 postgres:16-alpine | ssh "$SSH_HOST" docker load
fi
if [ "$BUILD_IMAGE" = 1 ]; then
  log "loading $IMAGE"
  docker save --platform linux/amd64 "$IMAGE" | ssh "$SSH_HOST" docker load
fi
echo "$SHA"
