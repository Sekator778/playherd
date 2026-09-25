# Task item 5 — multi-stage build for the local Docker stand (Р-44, compose project "herd"):
# Node build against the real backend (VITE_API_MODE=real, apiMode.ts) → static nginx serving,
# proxying /api to the Quarkus server (compose service name "server" — chainsvc from the task's
# FIXED INTERFACE (3) sits behind that server, this container never talks to it directly).

FROM node:22-alpine AS build
WORKDIR /app

# Dependencies first (better layer caching across rebuilds while iterating on source only).
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
ENV VITE_API_MODE=real
RUN npm run build

FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
