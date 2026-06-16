#!/usr/bin/env bash
# =============================================================================
# release.sh — Deploy a published GitHub release to the prod VM.
#
# Automates the "On the VM" steps from deploy/README.md:
#   1. git fetch origin --tags && git checkout <version>
#   2. Pin WEREWOLF_VERSION=<version> in .env.prod (replace if present, append
#      otherwise — matches the README footnote).
#   3. docker compose pull
#   4. docker compose up -d --force-recreate backend frontend
#   5. Wait for /api/health=UP, print running images.
#
# Prerequisites:
#   - Run ON the prod VM, not your laptop.
#   - The git tag must already be pushed AND the `Publish Images` workflow
#     must have produced ghcr.io images for it. Verify with `gh run watch`
#     locally before deploying.
#   - .env.prod must exist at $REPO_DIR/.env.prod with a valid
#     POSTGRES_PASSWORD, JWT_SECRET, etc.
#
# Usage:
#   sudo ./deploy/release.sh v0.6.8
#   sudo REPO_DIR=/opt/werewolf-simple ./deploy/release.sh v0.6.8   # override repo path
# =============================================================================

set -euo pipefail

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <vX.Y.Z>" >&2
  exit 1
fi
if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Error: version must look like vX.Y.Z (got: $VERSION)" >&2
  exit 1
fi

REPO_DIR="${REPO_DIR:-/opt/werewolf-simple}"
ENV_FILE=".env.prod"
HEALTH_URL="http://127.0.0.1:8080/api/health"

if [[ ! -d "$REPO_DIR/.git" ]]; then
  echo "Error: $REPO_DIR is not a git checkout" >&2
  exit 1
fi
cd "$REPO_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Error: $REPO_DIR/$ENV_FILE not found — create it from .env.prod.example first" >&2
  exit 1
fi

echo "==> [1/5] Fetching tags from origin"
sudo git fetch origin --tags

echo "==> [2/5] Checking out $VERSION"
sudo git checkout "$VERSION"

echo "==> [3/5] Pinning WEREWOLF_VERSION=$VERSION in $ENV_FILE"
if sudo grep -q '^WEREWOLF_VERSION=' "$ENV_FILE"; then
  sudo sed -i "s/^WEREWOLF_VERSION=.*$/WEREWOLF_VERSION=$VERSION/" "$ENV_FILE"
else
  echo "WEREWOLF_VERSION=$VERSION" | sudo tee -a "$ENV_FILE" >/dev/null
fi
grep '^WEREWOLF_VERSION=' "$ENV_FILE"

echo "==> [4/5] Pulling images and recreating backend + frontend"
sudo docker compose --env-file "$ENV_FILE" pull
sudo docker compose --env-file "$ENV_FILE" up -d --force-recreate backend frontend

echo "==> [5/5] Waiting for backend health (max 60s)"
for _ in $(seq 1 30); do
  if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
    echo "    backend healthy: $(curl -s "$HEALTH_URL")"
    break
  fi
  sleep 2
done
if ! curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
  echo "Error: backend did not respond OK at $HEALTH_URL after 60s" >&2
  echo "Recent backend logs:" >&2
  sudo docker compose --env-file "$ENV_FILE" logs --tail=40 backend >&2 || true
  exit 1
fi

echo
echo "Running images:"
sudo docker compose --env-file "$ENV_FILE" ps --format '{{.Service}}: {{.Image}}'

echo
echo "✓ Deploy of $VERSION complete."
