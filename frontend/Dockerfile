# syntax=docker/dockerfile:1.7

# ---- Stage 1: build the Vite bundle --------------------------------------
FROM node:20-alpine AS build
WORKDIR /app

# Install deps first for cache-friendly layers. The npm cache mount persists
# the download cache across image builds on the same host — `npm ci` without
# it re-downloads every package from the registry every build.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

# Copy sources and build. `npm run build` = vue-tsc -b && vite build → dist/.
COPY . .
RUN npm run build

# ---- Stage 2: serve static assets via Nginx ------------------------------
FROM nginx:1.27-alpine

# Minimal SPA config — history-mode fallback, gzip on common asset types.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# nginx default entrypoint/cmd is fine.
