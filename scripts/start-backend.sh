#!/usr/bin/env bash
# =============================================================================
# start-backend.sh — Start the Spring Boot backend in dev mode.
#
# Uses dev profile which:
#   - Stubs OAuth2 credentials (no real Google client ID needed)
#   - Enables Flyway with clean-on-start (drops + recreates all tables each run)
#   - Enables SQL logging
#
# Prerequisites:
#   - Docker running: docker ps | grep werewolf-db
#   - DB credentials from backend/.env  (POSTGRES_PASSWORD=werewolf)
#
# Usage:
#   ./scripts/start-backend.sh          # start on :8080
#   ./scripts/start-backend.sh --clean  # explicit: no-op (dev always cleans)
# =============================================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/backend/.env"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RESET='\033[0m'; BOLD='\033[1m'

# ── load .env ─────────────────────────────────────────────────────────────────
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
else
  echo -e "${RED}✗  $ENV_FILE not found${RESET}"
  echo "   Create it with: POSTGRES_PASSWORD=werewolf"
  exit 1
fi

# ── verify docker DB is running ───────────────────────────────────────────────
if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "werewolf-db"; then
  echo -e "${RED}✗  werewolf-db container is not running${RESET}"
  echo "   Start it with: docker compose -f backend/docker-compose.yml up -d"
  exit 1
fi

echo -e "${BOLD}${YELLOW}══  Starting backend (dev profile)${RESET}"
echo -e "  DB      : ${POSTGRES_DB:-werewolf} @ localhost:5432"
echo -e "  Profile : dev (Flyway clean + recreate on start)"
echo -e "  Port    : 8080"
echo ""

cd "$ROOT/backend"
SPRING_PROFILES_ACTIVE=dev exec ./gradlew bootRun
