# syntax=docker/dockerfile:1.7

# ---- Stage 1: build the Vite bundle --------------------------------------
FROM node:20-alpine AS build
WORKDIR /app

# Install deps first for cache-friendly layers.
COPY package.json package-lock.json ./
RUN npm ci

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
