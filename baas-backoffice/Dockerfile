# syntax=docker/dockerfile:1.6
# Multi-stage: Vite build on Node, served by nginx. Deployment-agnostic OCI image.
# Build:  docker build -t baas-backoffice:local -f baas-backoffice/Dockerfile baas-backoffice
# Run:    docker run --rm -p 3001:3001 baas-backoffice:local

# ─── Stage 1: build ────────────────────────────────────────────────────────────
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ─── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM nginx:1.27-alpine
RUN apk upgrade --no-cache && apk add --no-cache curl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 3001
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:3001/healthz || exit 1
