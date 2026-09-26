# Task item 5 — multi-stage build for the local Docker stand (Р-44, compose project "herd"):
# Node build against the real backend (VITE_API_MODE=real, apiMode.ts) → static nginx serving,
# proxying /api to the Quarkus server (compose service name "server" — chainsvc from the task's
# FIXED INTERFACE (3) sits behind that server, this container never talks to it directly).

FROM node:22-alpine AS build
WORKDIR /app

# Dependencies first (better layer caching across rebuilds while iterating on source only).
COPY package.json package-lock.json ./
RUN npm ci

# Р-55: build-time WalletConnect projectId (`wallet/walletConnect.ts`'s
# `getWalletConnectProjectId`) — optional, empty until Alex registers one with Reown Cloud
# (outbound registrations are only ever made with his own go-ahead, `CLAUDE.md`). Left unset here
# on purpose: `docker-compose.yml`'s `web` service has no matching `build.args:` entry yet (out of
# this file's own assigned paths) — passing `--build-arg VITE_WALLETCONNECT_PROJECT_ID=<id>` (or
# adding that `args:` line once someone edits docker-compose.yml) is the build-time route once a
# projectId exists. Until then, `ServerInfoDto.walletConnectProjectId` (a runtime override from
# `server/`, once that field exists there — see contract.ts's own doc comment) reaches an already
# -built image without a rebuild at all, which is the whole reason that override exists.
ARG VITE_WALLETCONNECT_PROJECT_ID=""
COPY . .
ENV VITE_API_MODE=real
ENV VITE_WALLETCONNECT_PROJECT_ID=${VITE_WALLETCONNECT_PROJECT_ID}
RUN npm run build

FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
