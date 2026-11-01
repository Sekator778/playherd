# Task item 5 — multi-stage build for the local Docker stand (D-44, compose project "herd"):
# Node build against the real backend (VITE_API_MODE=real, apiMode.ts) → static nginx serving,
# proxying /api to the Quarkus server (compose service name "server" — chainsvc from the task's
# FIXED INTERFACE (3) sits behind that server, this container never talks to it directly).

# `--platform=$BUILDPLATFORM`: the bundle in `dist/` is plain JS/CSS/WASM — it does not depend on
# the CPU architecture, so a multi-platform build (release.yml: linux/amd64 + linux/arm64 on an
# amd64 runner) runs `npm ci` + the build once, natively, instead of once more under QEMU; only
# the nginx runtime stage below is per target platform.
#
# Node: major 22 is what this image already used and what `package-lock.json`'s engines accept
# (vite/rolldown `^20.19 || >=22.12`, vitest `^22.12 || ^24 || >=26`, jsdom `^22.22.2 || ^24.15 ||
# >=26`; `web/package.json` has no `engines` field); the minor is pinned so a rebuild does not
# silently move to a new Node line — bump it on purpose (`node:22.23-alpine` exists on Docker Hub,
# updated 2026-09-23). The patch still floats for security fixes.
FROM --platform=$BUILDPLATFORM node:22.23-alpine AS build
WORKDIR /app

# Dependencies first (better layer caching across rebuilds while iterating on source only).
COPY package.json package-lock.json ./
RUN npm ci

# D-55: build-time WalletConnect projectId (`wallet/walletConnect.ts`'s
# `getWalletConnectProjectId`) — optional, empty until Alex registers one with Reown Cloud
# (outbound registrations are only ever made with his own go-ahead, `the project notes`). Passed in by
# `docker-compose.yml`'s `web.build.args` and by ci.yml/release.yml from the repository variable of
# the same name; a bare `docker build` leaves it empty — `--build-arg
# VITE_WALLETCONNECT_PROJECT_ID=<id>` is the manual route. `ServerInfoDto.walletConnectProjectId` (a
# runtime override from `server/`, see contract.ts's own doc comment) reaches an already-built
# image without a rebuild at all, which is the whole reason that override exists.
ARG VITE_WALLETCONNECT_PROJECT_ID=""
COPY . .
ENV VITE_API_MODE=real
ENV VITE_WALLETCONNECT_PROJECT_ID=${VITE_WALLETCONNECT_PROJECT_ID}
RUN npm run build

FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
